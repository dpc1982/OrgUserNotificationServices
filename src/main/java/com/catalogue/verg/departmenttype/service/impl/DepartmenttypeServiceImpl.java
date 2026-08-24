package com.catalogue.verg.departmenttype.service.impl;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.datastax.oss.driver.api.core.uuid.Uuids;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.catalogue.verg.core.cache.CacheService;
import com.catalogue.verg.core.config.LifecyclePolicy;
import com.catalogue.verg.core.dto.CustomResponse;
import com.catalogue.verg.core.dto.LifecycleRequest;
import com.catalogue.verg.core.dto.RespParam;
import com.catalogue.verg.core.elasticsearch.dto.SearchCriteria;
import com.catalogue.verg.core.elasticsearch.dto.SearchResult;
import com.catalogue.verg.core.elasticsearch.service.ESUtilService;
import com.catalogue.verg.core.exception.CustomException;
import com.catalogue.verg.core.util.Constants;
import com.catalogue.verg.core.util.LifecycleUtil;
import com.catalogue.verg.core.util.PayloadValidation;
import com.catalogue.verg.core.util.VergProperties;
// import com.catalogue.verg.core.service.AuditLogService;
import com.catalogue.verg.core.service.ImportService;
import com.catalogue.verg.core.service.LoadFromPrimaryService;
import com.catalogue.verg.core.util.PrimaryKeyUtil;
import com.catalogue.verg.departmenttype.entity.DepartmenttypeEntity;
import com.catalogue.verg.departmenttype.repository.DepartmenttypeRepository;
import com.catalogue.verg.departmenttype.service.DepartmenttypeService;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import org.springframework.web.multipart.MultipartFile;

import java.sql.Timestamp;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
// import java.util.UUID;
import java.util.concurrent.TimeUnit;


@Service
@Slf4j
public class DepartmenttypeServiceImpl implements DepartmenttypeService {
    @Autowired
    private PayloadValidation payloadValidation;

    @Autowired
    private PrimaryKeyUtil primaryKeyUtil;

    @Autowired
    private DepartmenttypeRepository departmenttypeRepository;

    @Autowired
    private ESUtilService esUtilService;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private CacheService cacheService;

    @Autowired
    private RedisTemplate<String, SearchResult> redisTemplate;

    @Autowired
    private VergProperties vergProperties;

    @Autowired
    private ImportService importService;

    @Autowired
    private LoadFromPrimaryService loadFromPrimaryService;

    // @Autowired
    // private AuditLogService auditLogService;

    @Autowired
    private LifecyclePolicy lifecyclePolicy;

    /**
     * Catalogue name recorded on every audit row emitted by this service. Doubles as the key
     * this catalogue is looked up by in the lifecycle switches ({@link LifecyclePolicy}).
     */
    private static final String AUDIT_ENTITY_NAME = "departmenttype";

    private Logger logger = LoggerFactory.getLogger(DepartmenttypeServiceImpl.class);

    @Value("${spring.redis.cacheTtl}")
    private long searchResultRedisTtl;

    @Override
    public CustomResponse createDepartmenttype(JsonNode departmenttypeEntity) {
        log.info("DepartmenttypeServiceImpl::createDepartmenttype:entered the method: " + departmenttypeEntity);
        CustomResponse response = new CustomResponse();
        payloadValidation.validatePayload(Constants.DEPARTMENTTYPE_VALIDATION_FILE_JSON, departmenttypeEntity);

        log.debug("DepartmenttypeServiceImpl::createDepartmenttype:validated the payload");
        try {
            log.info("DepartmenttypeServiceImpl::createDepartmenttype:creating departmenttype");
            DepartmenttypeEntity departmenttypeEntity1 = new DepartmenttypeEntity();
            // Generate Primary Key
            String primaryID = primaryKeyUtil.generateKey(Constants.DEPARTMENTTYPE_VALIDATION_FILE_JSON);
            departmenttypeEntity1.setDepartmenttypeId(primaryID);
            // Create Parameters like createdDate / updateDate / Data and Status
            Timestamp currentTime = new Timestamp(System.currentTimeMillis());
            
            String initialStatus = lifecyclePolicy.initialStatus(AUDIT_ENTITY_NAME);
            departmenttypeEntity1.setCreatedOn(currentTime);
            departmenttypeEntity1.setUpdatedOn(currentTime);
            departmenttypeEntity1.setStatus(initialStatus);
            departmenttypeEntity1.setData(departmenttypeEntity);

            departmenttypeRepository.save(departmenttypeEntity1);

            log.info("DepartmenttypeServiceImpl::createDepartmenttype::persisted departmenttype in postgres");
            ObjectNode jsonNode = buildDocument(departmenttypeEntity, initialStatus, currentTime, currentTime);
            Map<String, Object> map = objectMapper.convertValue(jsonNode, Map.class);
            esUtilService.addDocument(Constants.DEPARTMENTTYPE_INDEX_NAME, Constants.INDEX_TYPE,
                    String.valueOf(primaryID), map, vergProperties.getElasticDepartmenttypeJsonPath());
            cacheService.putCache(primaryID, jsonNode);
            response.setMessage(Constants.SUCCESSFULLY_CREATED);
            map.put(Constants.DEPARTMENTTYPE_ID_RQST, primaryID);
            response.setResult(map);
            response.setResponseCode(HttpStatus.OK);
            log.info("DepartmenttypeServiceImpl::createDepartmenttype::persisted departmenttype in OAS");
            // auditLogService.logAudit(primaryID, AUDIT_ENTITY_NAME, "create", initialStatus,
            //         objectMapper.createObjectNode(), departmenttypeEntity,
            //         departmenttypeEntity1.getCreatedOn(), departmenttypeEntity1.getUpdatedOn());
            return response;

        } catch (Exception e) {
            throw new CustomException("error while processing", e.getMessage(),
                    HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @Override
    public CustomResponse searchDepartmenttype(SearchCriteria searchCriteria) {
        log.info("DepartmenttypeServiceImpl::searchDepartmenttype");
        CustomResponse response = new CustomResponse();
        SearchResult searchResult = redisTemplate.opsForValue()
                .get(generateRedisJwtTokenKey(searchCriteria));
        if (searchResult != null) {
            log.info("DepartmenttypeServiceImpl::searchDepartmenttype: departmenttype search result fetched from redis");
            response.getResult().put(Constants.RESULT, searchResult);
            createSuccessResponse(response);
            // auditLogService.logAudit(null, AUDIT_ENTITY_NAME, "search", null, null,
            //         objectMapper.valueToTree(searchResult), null, null);
            return response;
        }
        String searchString = searchCriteria.getSearchString();
        if (searchString != null && searchString.length() < 2) {
            createErrorResponse(response, "Minimum 3 characters are required to search",
                    HttpStatus.BAD_REQUEST,
                    Constants.FAILED_CONST);
            return response;
        }
        try {
            searchResult =
                    esUtilService.searchDocuments(Constants.DEPARTMENTTYPE_INDEX_NAME, searchCriteria);
            response.getResult().put(Constants.RESULT, searchResult);
            createSuccessResponse(response);
            // auditLogService.logAudit(null, AUDIT_ENTITY_NAME, "search", null, null,
            //         objectMapper.valueToTree(searchResult), null, null);
            return response;
        } catch (Exception e) {
            createErrorResponse(response, e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR,
                    Constants.FAILED_CONST);
            redisTemplate.opsForValue()
                    .set(generateRedisJwtTokenKey(searchCriteria), searchResult, searchResultRedisTtl,
                            TimeUnit.SECONDS);
            return response;
        }
    }

    @Override
    public CustomResponse assignDepartmenttype(JsonNode departmenttypeEntity, String token) {
        return null;
    }

    @Override
    public CustomResponse read(String id) {
        log.info("DepartmenttypeServiceImpl::read:inside the method");
        CustomResponse response = new CustomResponse();
        if (StringUtils.isEmpty(id)) {
            response.setResponseCode(HttpStatus.INTERNAL_SERVER_ERROR);
            response.setMessage(Constants.ID_NOT_FOUND);
            return response;
        }
        JsonNode auditAfter = null;
        Timestamp auditCreatedOn = null;
        Timestamp auditUpdatedOn = null;
        try {
            String cachedJson = cacheService.getCache(id);
            if (StringUtils.isNotEmpty(cachedJson)) {
                log.info("DepartmenttypeServiceImpl::read:Record coming from redis cache");
                response.setMessage(Constants.SUCCESSFULLY_READING);
                response
                        .getResult()
                        .put(Constants.RESULT, objectMapper.readValue(cachedJson, new TypeReference<Object>() {
                        }));
                auditAfter = objectMapper.readTree(cachedJson);
            } else {
                Optional<DepartmenttypeEntity> entityOptional = departmenttypeRepository.findById(id);
                if (entityOptional.isPresent()) {
                    DepartmenttypeEntity departmenttypeEntity = entityOptional.get();
                    ObjectNode jsonNode = buildDocument(departmenttypeEntity.getData(),
                            departmenttypeEntity.getStatus(), departmenttypeEntity.getCreatedOn(),
                            departmenttypeEntity.getUpdatedOn());
                    cacheService.putCache(id, jsonNode);
                    log.info("DepartmenttypeServiceImpl::read:Record coming from postgres db");
                    response.setMessage(Constants.SUCCESSFULLY_READING);
                    response
                            .getResult()
                            .put(Constants.RESULT,
                                    objectMapper.convertValue(
                                            jsonNode, new TypeReference<Object>() {
                                            }));
                    auditAfter = jsonNode;
                    auditCreatedOn = departmenttypeEntity.getCreatedOn();
                    auditUpdatedOn = departmenttypeEntity.getUpdatedOn();
                } else {
                    response.setResponseCode(HttpStatus.NOT_FOUND);
                    response.setMessage(Constants.INVALID_ID);
                }
            }
        } catch (Exception e) {
            throw new CustomException(Constants.ERROR, "error while processing",
                    HttpStatus.INTERNAL_SERVER_ERROR);
        }
        // if (auditAfter != null) {
        //     auditLogService.logAudit(id, AUDIT_ENTITY_NAME, "read", null, null, auditAfter,
        //             auditCreatedOn, auditUpdatedOn);
        // }
        return response;
    }

    @Override
    public CustomResponse updateDepartmenttype(String id, JsonNode departmenttypeEntity) {
        log.info("DepartmenttypeServiceImpl::updateDepartmenttype:entered the method with id: {}", id);
        CustomResponse response = new CustomResponse();

        // Validate that the ID is not null or empty
        if (StringUtils.isEmpty(id)) {
            log.warn("DepartmenttypeServiceImpl::updateDepartmenttype:id is null or empty");
            response.setResponseCode(HttpStatus.BAD_REQUEST);
            response.setMessage(Constants.ID_NOT_FOUND);
            return response;
        }

        // Validate the incoming payload against the entity schema (same as create)
        payloadValidation.validatePayload(Constants.DEPARTMENTTYPE_VALIDATION_FILE_JSON, departmenttypeEntity);
        log.debug("DepartmenttypeServiceImpl::updateDepartmenttype:validated the payload");

        try {
            // Check if the entity exists in the database
            Optional<DepartmenttypeEntity> entityOptional = departmenttypeRepository.findById(id);
            if (entityOptional.isEmpty()) {
                log.warn("DepartmenttypeServiceImpl::updateDepartmenttype:no record found for id: {}", id);
                response.setResponseCode(HttpStatus.NOT_FOUND);
                response.setMessage(Constants.INVALID_ID);
                return response;
            }

            DepartmenttypeEntity departmenttypeEntity1 = entityOptional.get();

            // Reject updates on soft-deleted (DELETED) records
            if (Constants.DELETED.equals(departmenttypeEntity1.getStatus())) {
                log.warn("DepartmenttypeServiceImpl::updateDepartmenttype:record already deleted for id: {}", id);
                response.setResponseCode(HttpStatus.BAD_REQUEST);
                response.setMessage("Record is already deleted");
                return response;
            }

            // Replace payload; preserve id / createdOn / status, bump updatedOn
            Timestamp currentTime = new Timestamp(System.currentTimeMillis());
            departmenttypeEntity1.setData(departmenttypeEntity);
            departmenttypeEntity1.setUpdatedOn(currentTime);
            departmenttypeRepository.save(departmenttypeEntity1);
            log.info("DepartmenttypeServiceImpl::updateDepartmenttype:updated record in postgres for id: {}", id);

            // Re-index the document in Elasticsearch (filtered to whitelisted fields)
            ObjectNode jsonNode = buildDocument(departmenttypeEntity, departmenttypeEntity1.getStatus(),
                    departmenttypeEntity1.getCreatedOn(), currentTime);
            Map<String, Object> map = objectMapper.convertValue(jsonNode, Map.class);
            esUtilService.updateDocument(Constants.DEPARTMENTTYPE_INDEX_NAME, Constants.INDEX_TYPE,
                    id, map, vergProperties.getElasticDepartmenttypeJsonPath());
            log.info("DepartmenttypeServiceImpl::updateDepartmenttype:updated document in elasticsearch for id: {}", id);

            // Refresh the Redis cache
            cacheService.putCache(id, jsonNode);
            log.info("DepartmenttypeServiceImpl::updateDepartmenttype:refreshed cache for id: {}", id);

            map.put(Constants.DEPARTMENTTYPE_ID_RQST, id);
            response.setResult(map);
            response.setMessage(Constants.SUCCESSFULLY_UPDATED);
            response.setResponseCode(HttpStatus.OK);
            return response;

        } catch (Exception e) {
            log.error("DepartmenttypeServiceImpl::updateDepartmenttype:error while updating record for id: {}", id, e);
            throw new CustomException("error while processing", e.getMessage(),
                    HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @Override
    public CustomResponse delete(String id) {
        log.info("DepartmenttypeServiceImpl::delete:inside the method with id: {}", id);
        CustomResponse response = new CustomResponse();

        // Validate that the ID is not null or empty
        if (StringUtils.isEmpty(id)) {
            log.warn("DepartmenttypeServiceImpl::delete:id is null or empty");
            response.setResponseCode(HttpStatus.BAD_REQUEST);
            response.setMessage(Constants.ID_NOT_FOUND);
            return response;
        }

        try {
            // Check if the entity exists in the database
            Optional<DepartmenttypeEntity> entityOptional = departmenttypeRepository.findById(id);
            if (entityOptional.isEmpty()) {
                log.warn("DepartmenttypeServiceImpl::delete:no record found for id: {}", id);
                response.setResponseCode(HttpStatus.NOT_FOUND);
                response.setMessage(Constants.INVALID_ID);
                return response;
            }

            DepartmenttypeEntity departmenttypeEntity = entityOptional.get();

            // Check if the entity is already deleted
            if (Constants.DELETED.equals(departmenttypeEntity.getStatus())) {
                log.warn("DepartmenttypeServiceImpl::delete:record already deleted for id: {}", id);
                response.setResponseCode(HttpStatus.BAD_REQUEST);
                response.setMessage("Record is already deleted");
                return response;
            }

            // Soft delete: mark the status DELETED and set updatedOn timestamp
            departmenttypeEntity.setStatus(Constants.DELETED);
            departmenttypeEntity.setUpdatedOn(new Timestamp(System.currentTimeMillis()));
            departmenttypeRepository.save(departmenttypeEntity);
            log.info("DepartmenttypeServiceImpl::delete:soft deleted record in postgres for id: {}", id);

            // Remove document from Elasticsearch
            esUtilService.deleteDocument(id, Constants.DEPARTMENTTYPE_INDEX_NAME);
            log.info("DepartmenttypeServiceImpl::delete:deleted document from elasticsearch for id: {}", id);

            // Remove from Redis cache
            cacheService.deleteCache(id);
            log.info("DepartmenttypeServiceImpl::delete:evicted cache for id: {}", id);

            response.setMessage(Constants.SUCCESSFULLY_DELETED);
            response.setResponseCode(HttpStatus.OK);
            // auditLogService.logAudit(id, AUDIT_ENTITY_NAME, "delete", Constants.DELETED,
            //         departmenttypeEntity.getData(), departmenttypeEntity.getData(),
            //         departmenttypeEntity.getCreatedOn(), departmenttypeEntity.getUpdatedOn());
            return response;

        } catch (Exception e) {
            log.error("DepartmenttypeServiceImpl::delete:error while deleting record for id: {}", id, e);
            throw new CustomException(Constants.ERROR, "error while deleting record",
                    HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @Override
    public CustomResponse importData(MultipartFile file) {
        log.info("DepartmenttypeServiceImpl::importData::started");
        return importService.processBulkImport(
                file,
                Constants.DEPARTMENTTYPE_VALIDATION_FILE_JSON,
                this::createDepartmenttype
        );
    }

    @Override
    public CustomResponse loadFromPrimaryDepartmenttype() {
        log.info("DepartmenttypeServiceImpl::loadFromPrimaryDepartmenttype::started");
        return loadFromPrimaryService.loadFromPrimary(
                Constants.DEPARTMENTTYPE_INDEX_NAME,
                vergProperties.getElasticDepartmenttypeJsonPath(),
                departmenttypeRepository.findAll(),
                DepartmenttypeEntity::getDepartmenttypeId,
                e -> objectMapper.convertValue(
                        buildDocument(e.getData(), e.getStatus(), e.getCreatedOn(), e.getUpdatedOn()),
                        Map.class),
                e -> !Constants.DELETED.equals(e.getStatus()));   // skip DELETED; INACTIVE is indexed
    }

    @Override
    public CustomResponse draftDepartmenttype(JsonNode departmenttypeEntity) {
        log.info("DepartmenttypeServiceImpl::draftDepartmenttype:entered the method: " + departmenttypeEntity);
        // Guard before the try block: the 404 must not be swallowed by the catch below
        lifecyclePolicy.requireEnabled(AUDIT_ENTITY_NAME);
        CustomResponse response = new CustomResponse();
        // Relaxed validation: types/structure enforced, but required fields may be missing
        payloadValidation.validatePayloadRelaxed(Constants.DEPARTMENTTYPE_VALIDATION_FILE_JSON, departmenttypeEntity);
        log.debug("DepartmenttypeServiceImpl::draftDepartmenttype:validated the payload (relaxed)");
        try {
            DepartmenttypeEntity departmenttypeEntity1 = new DepartmenttypeEntity();
            String primaryID = primaryKeyUtil.generateKey(Constants.DEPARTMENTTYPE_VALIDATION_FILE_JSON);
            departmenttypeEntity1.setDepartmenttypeId(primaryID);
            Timestamp currentTime = new Timestamp(System.currentTimeMillis());
            departmenttypeEntity1.setCreatedOn(currentTime);
            departmenttypeEntity1.setUpdatedOn(currentTime);
            departmenttypeEntity1.setStatus(Constants.DRAFT);
            departmenttypeEntity1.setData(departmenttypeEntity);

            departmenttypeRepository.save(departmenttypeEntity1);
            log.info("DepartmenttypeServiceImpl::draftDepartmenttype::persisted draft in postgres");

            ObjectNode jsonNode = buildDocument(departmenttypeEntity, Constants.DRAFT, currentTime, currentTime);
            Map<String, Object> map = objectMapper.convertValue(jsonNode, Map.class);
            esUtilService.addDocument(Constants.DEPARTMENTTYPE_INDEX_NAME, Constants.INDEX_TYPE,
                    String.valueOf(primaryID), map, vergProperties.getElasticDepartmenttypeJsonPath());
            cacheService.putCache(primaryID, jsonNode);
            map.put(Constants.DEPARTMENTTYPE_ID_RQST, primaryID);
            response.setResult(map);
            response.setMessage(Constants.SUCCESSFULLY_CREATED);
            response.setResponseCode(HttpStatus.OK);
            // auditLogService.logAudit(primaryID, AUDIT_ENTITY_NAME, "draft", Constants.DRAFT,
            //         objectMapper.createObjectNode(), departmenttypeEntity,
            //         departmenttypeEntity1.getCreatedOn(), departmenttypeEntity1.getUpdatedOn());
            return response;
        } catch (Exception e) {
            throw new CustomException("error while processing", e.getMessage(),
                    HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @Override
    public CustomResponse addDepartmenttype(String id, JsonNode departmenttypeEntity) {
        log.info("DepartmenttypeServiceImpl::addDepartmenttype:entered the method with id: {}", id);
        // Guard before the try block: the 404 must not be swallowed by the catch below
        lifecyclePolicy.requireEnabled(AUDIT_ENTITY_NAME);
        CustomResponse response = new CustomResponse();
        if (StringUtils.isEmpty(id)) {
            response.setResponseCode(HttpStatus.BAD_REQUEST);
            response.setMessage(Constants.ID_NOT_FOUND);
            return response;
        }
        // Full validation: all required fields must be present to submit for approval
        payloadValidation.validatePayload(Constants.DEPARTMENTTYPE_VALIDATION_FILE_JSON, departmenttypeEntity);
        log.debug("DepartmenttypeServiceImpl::addDepartmenttype:validated the payload");
        try {
            Optional<DepartmenttypeEntity> entityOptional = departmenttypeRepository.findById(id);
            if (entityOptional.isEmpty()) {
                response.setResponseCode(HttpStatus.NOT_FOUND);
                response.setMessage(Constants.INVALID_ID);
                return response;
            }
            DepartmenttypeEntity departmenttypeEntity1 = entityOptional.get();
            // Only DRAFT or REWORK records can be (re-)submitted for approval
            if (!LifecycleUtil.ADD_PROMOTABLE.contains(departmenttypeEntity1.getStatus())) {
                log.warn("DepartmenttypeServiceImpl::addDepartmenttype:record {} not in DRAFT/REWORK (status={})",
                        id, departmenttypeEntity1.getStatus());
                response.setResponseCode(HttpStatus.CONFLICT);
                response.setMessage(Constants.INVALID_STATUS_TRANSITION);
                return response;
            }
            Timestamp currentTime = new Timestamp(System.currentTimeMillis());
            JsonNode auditBefore = departmenttypeEntity1.getData();
            departmenttypeEntity1.setData(departmenttypeEntity);
            departmenttypeEntity1.setStatus(Constants.PENDING);
            departmenttypeEntity1.setUpdatedOn(currentTime);
            departmenttypeRepository.save(departmenttypeEntity1);
            log.info("DepartmenttypeServiceImpl::addDepartmenttype:submitted record {} for approval (PENDING)", id);

            ObjectNode jsonNode = buildDocument(departmenttypeEntity, Constants.PENDING,
                    departmenttypeEntity1.getCreatedOn(), currentTime);
            Map<String, Object> map = objectMapper.convertValue(jsonNode, Map.class);
            esUtilService.updateDocument(Constants.DEPARTMENTTYPE_INDEX_NAME, Constants.INDEX_TYPE,
                    id, map, vergProperties.getElasticDepartmenttypeJsonPath());
            cacheService.putCache(id, jsonNode);
            map.put(Constants.DEPARTMENTTYPE_ID_RQST, id);
            response.setResult(map);
            response.setMessage(Constants.SUCCESSFULLY_UPDATED);
            response.setResponseCode(HttpStatus.OK);
            // auditLogService.logAudit(id, AUDIT_ENTITY_NAME, "add-promote", Constants.PENDING,
            //         auditBefore, departmenttypeEntity,
            //         departmenttypeEntity1.getCreatedOn(), departmenttypeEntity1.getUpdatedOn());
            return response;
        } catch (Exception e) {
            throw new CustomException("error while processing", e.getMessage(),
                    HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @Override
    public CustomResponse approveDepartmenttype(LifecycleRequest request) {
        log.info("DepartmenttypeServiceImpl::approveDepartmenttype:entered the method");
        lifecyclePolicy.requireEnabled(AUDIT_ENTITY_NAME);
        return transitionStatus(request, "approve", LifecycleUtil.APPROVE_FROM, LifecycleUtil.APPROVE_TARGETS);
    }

    @Override
    public CustomResponse reviewDepartmenttype(LifecycleRequest request) {
        log.info("DepartmenttypeServiceImpl::reviewDepartmenttype:entered the method");
        lifecyclePolicy.requireEnabled(AUDIT_ENTITY_NAME);
        return transitionStatus(request, "review", LifecycleUtil.REVIEW_FROM, LifecycleUtil.REVIEW_TARGETS);
    }

    @Override
    public CustomResponse toggleStatus(String id) {
        log.info("DepartmenttypeServiceImpl::toggleStatus:entered the method with id: {}", id);
        CustomResponse response = new CustomResponse();
        if (StringUtils.isEmpty(id)) {
            response.setResponseCode(HttpStatus.BAD_REQUEST);
            response.setMessage(Constants.ID_NOT_FOUND);
            return response;
        }
        try {
            Optional<DepartmenttypeEntity> entityOptional = departmenttypeRepository.findById(id);
            if (entityOptional.isEmpty()) {
                response.setResponseCode(HttpStatus.NOT_FOUND);
                response.setMessage(Constants.INVALID_ID);
                return response;
            }
            DepartmenttypeEntity departmenttypeEntity1 = entityOptional.get();
            String currentStatus = departmenttypeEntity1.getStatus();
            String newStatus;
            if (Constants.ACTIVE.equals(currentStatus)) {
                newStatus = Constants.IN_ACTIVE;
            } else if (Constants.IN_ACTIVE.equals(currentStatus)) {
                newStatus = Constants.ACTIVE;
            } else {
                // Only a published (ACTIVE) or deactivated (INACTIVE) record can be toggled
                log.warn("DepartmenttypeServiceImpl::toggleStatus:record {} is {}, can only toggle ACTIVE<->INACTIVE",
                        id, currentStatus);
                response.setResponseCode(HttpStatus.CONFLICT);
                response.setMessage(Constants.INVALID_STATUS_TRANSITION);
                return response;
            }
            Timestamp currentTime = new Timestamp(System.currentTimeMillis());
            departmenttypeEntity1.setStatus(newStatus);
            departmenttypeEntity1.setUpdatedOn(currentTime);
            departmenttypeRepository.save(departmenttypeEntity1);
            log.info("DepartmenttypeServiceImpl::toggleStatus:record {} toggled {} -> {}", id, currentStatus, newStatus);

            ObjectNode jsonNode = buildDocument(departmenttypeEntity1.getData(), newStatus,
                    departmenttypeEntity1.getCreatedOn(), currentTime);
            Map<String, Object> map = objectMapper.convertValue(jsonNode, Map.class);
            esUtilService.updateDocument(Constants.DEPARTMENTTYPE_INDEX_NAME, Constants.INDEX_TYPE,
                    id, map, vergProperties.getElasticDepartmenttypeJsonPath());
            cacheService.putCache(id, jsonNode);
            map.put(Constants.DEPARTMENTTYPE_ID_RQST, id);
            response.setResult(map);
            response.setMessage(Constants.SUCCESSFULLY_UPDATED);
            response.setResponseCode(HttpStatus.OK);
            // auditLogService.logAudit(id, AUDIT_ENTITY_NAME, "toggle", newStatus,
            //         departmenttypeEntity1.getData(), departmenttypeEntity1.getData(),
            //         departmenttypeEntity1.getCreatedOn(), departmenttypeEntity1.getUpdatedOn());
            return response;
        } catch (Exception e) {
            throw new CustomException("error while processing", e.getMessage(),
                    HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    /**
     * Shared status-transition logic for approve/review. Validates the id and requested target status,
     * enforces the required current status, then persists the new status to Postgres, ES and Redis.
     */
    private CustomResponse transitionStatus(LifecycleRequest request, String operation,
                                            String requiredCurrentStatus, Set<String> allowedTargets) {
        CustomResponse response = new CustomResponse();
        if (request == null || StringUtils.isEmpty(request.getId())) {
            response.setResponseCode(HttpStatus.BAD_REQUEST);
            response.setMessage(Constants.ID_NOT_FOUND);
            return response;
        }
        String id = request.getId();
        String targetStatus = LifecycleUtil.normalizeTarget(request.getStatus());
        if (targetStatus == null || !allowedTargets.contains(targetStatus)) {
            log.warn("DepartmenttypeServiceImpl::transitionStatus:invalid target status '{}' for id {}",
                    request.getStatus(), id);
            response.setResponseCode(HttpStatus.BAD_REQUEST);
            response.setMessage(Constants.INVALID_STATUS);
            return response;
        }
        try {
            Optional<DepartmenttypeEntity> entityOptional = departmenttypeRepository.findById(id);
            if (entityOptional.isEmpty()) {
                response.setResponseCode(HttpStatus.NOT_FOUND);
                response.setMessage(Constants.INVALID_ID);
                return response;
            }
            DepartmenttypeEntity departmenttypeEntity1 = entityOptional.get();
            if (!requiredCurrentStatus.equals(departmenttypeEntity1.getStatus())) {
                log.warn("DepartmenttypeServiceImpl::transitionStatus:record {} is {}, requires {}",
                        id, departmenttypeEntity1.getStatus(), requiredCurrentStatus);
                response.setResponseCode(HttpStatus.CONFLICT);
                response.setMessage(Constants.INVALID_STATUS_TRANSITION);
                return response;
            }
            Timestamp currentTime = new Timestamp(System.currentTimeMillis());
            departmenttypeEntity1.setStatus(targetStatus);
            departmenttypeEntity1.setUpdatedOn(currentTime);
            departmenttypeRepository.save(departmenttypeEntity1);
            log.info("DepartmenttypeServiceImpl::transitionStatus:record {} moved {} -> {}",
                    id, requiredCurrentStatus, targetStatus);

            ObjectNode jsonNode = buildDocument(departmenttypeEntity1.getData(), targetStatus,
                    departmenttypeEntity1.getCreatedOn(), currentTime);
            Map<String, Object> map = objectMapper.convertValue(jsonNode, Map.class);
            esUtilService.updateDocument(Constants.DEPARTMENTTYPE_INDEX_NAME, Constants.INDEX_TYPE,
                    id, map, vergProperties.getElasticDepartmenttypeJsonPath());
            cacheService.putCache(id, jsonNode);
            map.put(Constants.DEPARTMENTTYPE_ID_RQST, id);
            response.setResult(map);
            response.setMessage(Constants.SUCCESSFULLY_UPDATED);
            response.setResponseCode(HttpStatus.OK);
            // auditLogService.logAudit(id, AUDIT_ENTITY_NAME, operation, targetStatus,
            //         departmenttypeEntity1.getData(), departmenttypeEntity1.getData(),
            //         departmenttypeEntity1.getCreatedOn(), departmenttypeEntity1.getUpdatedOn());
            return response;
        } catch (Exception e) {
            throw new CustomException("error while processing", e.getMessage(),
                    HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    /**
     * Builds the projection stored in Elasticsearch and Redis (and returned by read): the payload
     * plus the lifecycle status and the Postgres createdOn/updatedOn timestamps (ISO-8601). ES keeps
     * only whitelisted keys, so status/createdOn/updatedOn must be present in esDepartmenttypeRequiredFields.json.
     */
    private ObjectNode buildDocument(JsonNode data, String status, Timestamp createdOn, Timestamp updatedOn) {
        ObjectNode node = objectMapper.createObjectNode();
        if (data != null && data.isObject()) {
            node.setAll((ObjectNode) data);
        }
        node.put(Constants.STATUS, status);
        if (createdOn != null) {
            node.put(Constants.CREATED_ON, createdOn.toInstant().toString());
        }
        if (updatedOn != null) {
            node.put(Constants.UPDATED_ON, updatedOn.toInstant().toString());
        }
        return node;
    }

    public void createSuccessResponse(CustomResponse response) {
        response.setParams(new RespParam());
        response.getParams().setStatus(Constants.SUCCESS);
        response.setResponseCode(HttpStatus.OK);
    }

    public String generateRedisJwtTokenKey(Object requestPayload) {
        if (requestPayload != null) {
            try {
                String reqJsonString = objectMapper.writeValueAsString(requestPayload);
                return JWT.create()
                        .withClaim(Constants.REQUEST_PAYLOAD, reqJsonString)
                        .sign(Algorithm.HMAC256(Constants.JWT_SECRET_KEY));
            } catch (JsonProcessingException e) {
                // logger.error("Error occurred while converting json object to json string", e);
            }
        }
        return "";
    }

    public void createErrorResponse(
            CustomResponse response, String errorMessage, HttpStatus httpStatus, String status) {
        response.setParams(new RespParam());
        response.getParams().setStatus(status);
        response.setResponseCode(httpStatus);
    }
}