package com.catalogue.verg.department.service.impl;

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
import com.catalogue.verg.department.entity.DepartmentEntity;
import com.catalogue.verg.department.repository.DepartmentRepository;
import com.catalogue.verg.department.service.DepartmentService;
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
public class DepartmentServiceImpl implements DepartmentService {
    @Autowired
    private PayloadValidation payloadValidation;

    @Autowired
    private PrimaryKeyUtil primaryKeyUtil;

    @Autowired
    private DepartmentRepository departmentRepository;

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
    private static final String AUDIT_ENTITY_NAME = "department";

    private Logger logger = LoggerFactory.getLogger(DepartmentServiceImpl.class);

    @Value("${spring.redis.cacheTtl}")
    private long searchResultRedisTtl;

    @Override
    public CustomResponse createDepartment(JsonNode departmentEntity) {
        log.info("DepartmentServiceImpl::createDepartment:entered the method: " + departmentEntity);
        CustomResponse response = new CustomResponse();
        payloadValidation.validatePayload(Constants.DEPARTMENT_VALIDATION_FILE_JSON, departmentEntity);

        log.debug("DepartmentServiceImpl::createDepartment:validated the payload");
        try {
            log.info("DepartmentServiceImpl::createDepartment:creating department");
            DepartmentEntity departmentEntity1 = new DepartmentEntity();
            // Generate Primary Key
            String primaryID = primaryKeyUtil.generateKey(Constants.DEPARTMENT_VALIDATION_FILE_JSON);
            departmentEntity1.setDepartmentId(primaryID);
            // Create Parameters like createdDate / updateDate / Data and Status
            Timestamp currentTime = new Timestamp(System.currentTimeMillis());
            
            String initialStatus = lifecyclePolicy.initialStatus(AUDIT_ENTITY_NAME);
            departmentEntity1.setCreatedOn(currentTime);
            departmentEntity1.setUpdatedOn(currentTime);
            departmentEntity1.setStatus(initialStatus);
            departmentEntity1.setData(departmentEntity);

            departmentRepository.save(departmentEntity1);

            log.info("DepartmentServiceImpl::createDepartment::persisted department in postgres");
            ObjectNode jsonNode = buildDocument(departmentEntity, initialStatus, currentTime, currentTime);
            Map<String, Object> map = objectMapper.convertValue(jsonNode, Map.class);
            esUtilService.addDocument(Constants.DEPARTMENT_INDEX_NAME, Constants.INDEX_TYPE,
                    String.valueOf(primaryID), map, vergProperties.getElasticDepartmentJsonPath());
            cacheService.putCache(primaryID, jsonNode);
            response.setMessage(Constants.SUCCESSFULLY_CREATED);
            map.put(Constants.DEPARTMENT_ID_RQST, primaryID);
            response.setResult(map);
            response.setResponseCode(HttpStatus.OK);
            log.info("DepartmentServiceImpl::createDepartment::persisted department in OAS");
            // auditLogService.logAudit(primaryID, AUDIT_ENTITY_NAME, "create", initialStatus,
            //         objectMapper.createObjectNode(), departmentEntity,
            //         departmentEntity1.getCreatedOn(), departmentEntity1.getUpdatedOn());
            return response;

        } catch (Exception e) {
            throw new CustomException("error while processing", e.getMessage(),
                    HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @Override
    public CustomResponse searchDepartment(SearchCriteria searchCriteria) {
        log.info("DepartmentServiceImpl::searchDepartment");
        CustomResponse response = new CustomResponse();
        SearchResult searchResult = redisTemplate.opsForValue()
                .get(generateRedisJwtTokenKey(searchCriteria));
        if (searchResult != null) {
            log.info("DepartmentServiceImpl::searchDepartment: department search result fetched from redis");
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
                    esUtilService.searchDocuments(Constants.DEPARTMENT_INDEX_NAME, searchCriteria);
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
    public CustomResponse assignDepartment(JsonNode departmentEntity, String token) {
        return null;
    }

    @Override
    public CustomResponse read(String id) {
        log.info("DepartmentServiceImpl::read:inside the method");
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
                log.info("DepartmentServiceImpl::read:Record coming from redis cache");
                response.setMessage(Constants.SUCCESSFULLY_READING);
                response
                        .getResult()
                        .put(Constants.RESULT, objectMapper.readValue(cachedJson, new TypeReference<Object>() {
                        }));
                auditAfter = objectMapper.readTree(cachedJson);
            } else {
                Optional<DepartmentEntity> entityOptional = departmentRepository.findById(id);
                if (entityOptional.isPresent()) {
                    DepartmentEntity departmentEntity = entityOptional.get();
                    ObjectNode jsonNode = buildDocument(departmentEntity.getData(),
                            departmentEntity.getStatus(), departmentEntity.getCreatedOn(),
                            departmentEntity.getUpdatedOn());
                    cacheService.putCache(id, jsonNode);
                    log.info("DepartmentServiceImpl::read:Record coming from postgres db");
                    response.setMessage(Constants.SUCCESSFULLY_READING);
                    response
                            .getResult()
                            .put(Constants.RESULT,
                                    objectMapper.convertValue(
                                            jsonNode, new TypeReference<Object>() {
                                            }));
                    auditAfter = jsonNode;
                    auditCreatedOn = departmentEntity.getCreatedOn();
                    auditUpdatedOn = departmentEntity.getUpdatedOn();
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
    public CustomResponse updateDepartment(String id, JsonNode departmentEntity) {
        log.info("DepartmentServiceImpl::updateDepartment:entered the method with id: {}", id);
        CustomResponse response = new CustomResponse();

        // Validate that the ID is not null or empty
        if (StringUtils.isEmpty(id)) {
            log.warn("DepartmentServiceImpl::updateDepartment:id is null or empty");
            response.setResponseCode(HttpStatus.BAD_REQUEST);
            response.setMessage(Constants.ID_NOT_FOUND);
            return response;
        }

        // Validate the incoming payload against the entity schema (same as create)
        payloadValidation.validatePayload(Constants.DEPARTMENT_VALIDATION_FILE_JSON, departmentEntity);
        log.debug("DepartmentServiceImpl::updateDepartment:validated the payload");

        try {
            // Check if the entity exists in the database
            Optional<DepartmentEntity> entityOptional = departmentRepository.findById(id);
            if (entityOptional.isEmpty()) {
                log.warn("DepartmentServiceImpl::updateDepartment:no record found for id: {}", id);
                response.setResponseCode(HttpStatus.NOT_FOUND);
                response.setMessage(Constants.INVALID_ID);
                return response;
            }

            DepartmentEntity departmentEntity1 = entityOptional.get();

            // Reject updates on soft-deleted (DELETED) records
            if (Constants.DELETED.equals(departmentEntity1.getStatus())) {
                log.warn("DepartmentServiceImpl::updateDepartment:record already deleted for id: {}", id);
                response.setResponseCode(HttpStatus.BAD_REQUEST);
                response.setMessage("Record is already deleted");
                return response;
            }

            // Replace payload; preserve id / createdOn / status, bump updatedOn
            Timestamp currentTime = new Timestamp(System.currentTimeMillis());
            departmentEntity1.setData(departmentEntity);
            departmentEntity1.setUpdatedOn(currentTime);
            departmentRepository.save(departmentEntity1);
            log.info("DepartmentServiceImpl::updateDepartment:updated record in postgres for id: {}", id);

            // Re-index the document in Elasticsearch (filtered to whitelisted fields)
            ObjectNode jsonNode = buildDocument(departmentEntity, departmentEntity1.getStatus(),
                    departmentEntity1.getCreatedOn(), currentTime);
            Map<String, Object> map = objectMapper.convertValue(jsonNode, Map.class);
            esUtilService.updateDocument(Constants.DEPARTMENT_INDEX_NAME, Constants.INDEX_TYPE,
                    id, map, vergProperties.getElasticDepartmentJsonPath());
            log.info("DepartmentServiceImpl::updateDepartment:updated document in elasticsearch for id: {}", id);

            // Refresh the Redis cache
            cacheService.putCache(id, jsonNode);
            log.info("DepartmentServiceImpl::updateDepartment:refreshed cache for id: {}", id);

            map.put(Constants.DEPARTMENT_ID_RQST, id);
            response.setResult(map);
            response.setMessage(Constants.SUCCESSFULLY_UPDATED);
            response.setResponseCode(HttpStatus.OK);
            return response;

        } catch (Exception e) {
            log.error("DepartmentServiceImpl::updateDepartment:error while updating record for id: {}", id, e);
            throw new CustomException("error while processing", e.getMessage(),
                    HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @Override
    public CustomResponse delete(String id) {
        log.info("DepartmentServiceImpl::delete:inside the method with id: {}", id);
        CustomResponse response = new CustomResponse();

        // Validate that the ID is not null or empty
        if (StringUtils.isEmpty(id)) {
            log.warn("DepartmentServiceImpl::delete:id is null or empty");
            response.setResponseCode(HttpStatus.BAD_REQUEST);
            response.setMessage(Constants.ID_NOT_FOUND);
            return response;
        }

        try {
            // Check if the entity exists in the database
            Optional<DepartmentEntity> entityOptional = departmentRepository.findById(id);
            if (entityOptional.isEmpty()) {
                log.warn("DepartmentServiceImpl::delete:no record found for id: {}", id);
                response.setResponseCode(HttpStatus.NOT_FOUND);
                response.setMessage(Constants.INVALID_ID);
                return response;
            }

            DepartmentEntity departmentEntity = entityOptional.get();

            // Check if the entity is already deleted
            if (Constants.DELETED.equals(departmentEntity.getStatus())) {
                log.warn("DepartmentServiceImpl::delete:record already deleted for id: {}", id);
                response.setResponseCode(HttpStatus.BAD_REQUEST);
                response.setMessage("Record is already deleted");
                return response;
            }

            // Soft delete: mark the status DELETED and set updatedOn timestamp
            departmentEntity.setStatus(Constants.DELETED);
            departmentEntity.setUpdatedOn(new Timestamp(System.currentTimeMillis()));
            departmentRepository.save(departmentEntity);
            log.info("DepartmentServiceImpl::delete:soft deleted record in postgres for id: {}", id);

            // Remove document from Elasticsearch
            esUtilService.deleteDocument(id, Constants.DEPARTMENT_INDEX_NAME);
            log.info("DepartmentServiceImpl::delete:deleted document from elasticsearch for id: {}", id);

            // Remove from Redis cache
            cacheService.deleteCache(id);
            log.info("DepartmentServiceImpl::delete:evicted cache for id: {}", id);

            response.setMessage(Constants.SUCCESSFULLY_DELETED);
            response.setResponseCode(HttpStatus.OK);
            // auditLogService.logAudit(id, AUDIT_ENTITY_NAME, "delete", Constants.DELETED,
            //         departmentEntity.getData(), departmentEntity.getData(),
            //         departmentEntity.getCreatedOn(), departmentEntity.getUpdatedOn());
            return response;

        } catch (Exception e) {
            log.error("DepartmentServiceImpl::delete:error while deleting record for id: {}", id, e);
            throw new CustomException(Constants.ERROR, "error while deleting record",
                    HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @Override
    public CustomResponse importData(MultipartFile file) {
        log.info("DepartmentServiceImpl::importData::started");
        return importService.processBulkImport(
                file,
                Constants.DEPARTMENT_VALIDATION_FILE_JSON,
                this::createDepartment
        );
    }

    @Override
    public CustomResponse loadFromPrimaryDepartment() {
        log.info("DepartmentServiceImpl::loadFromPrimaryDepartment::started");
        return loadFromPrimaryService.loadFromPrimary(
                Constants.DEPARTMENT_INDEX_NAME,
                vergProperties.getElasticDepartmentJsonPath(),
                departmentRepository.findAll(),
                DepartmentEntity::getDepartmentId,
                e -> objectMapper.convertValue(
                        buildDocument(e.getData(), e.getStatus(), e.getCreatedOn(), e.getUpdatedOn()),
                        Map.class),
                e -> !Constants.DELETED.equals(e.getStatus()));   // skip DELETED; INACTIVE is indexed
    }

    @Override
    public CustomResponse draftDepartment(JsonNode departmentEntity) {
        log.info("DepartmentServiceImpl::draftDepartment:entered the method: " + departmentEntity);
        // Guard before the try block: the 404 must not be swallowed by the catch below
        lifecyclePolicy.requireEnabled(AUDIT_ENTITY_NAME);
        CustomResponse response = new CustomResponse();
        // Relaxed validation: types/structure enforced, but required fields may be missing
        payloadValidation.validatePayloadRelaxed(Constants.DEPARTMENT_VALIDATION_FILE_JSON, departmentEntity);
        log.debug("DepartmentServiceImpl::draftDepartment:validated the payload (relaxed)");
        try {
            DepartmentEntity departmentEntity1 = new DepartmentEntity();
            String primaryID = primaryKeyUtil.generateKey(Constants.DEPARTMENT_VALIDATION_FILE_JSON);
            departmentEntity1.setDepartmentId(primaryID);
            Timestamp currentTime = new Timestamp(System.currentTimeMillis());
            departmentEntity1.setCreatedOn(currentTime);
            departmentEntity1.setUpdatedOn(currentTime);
            departmentEntity1.setStatus(Constants.DRAFT);
            departmentEntity1.setData(departmentEntity);

            departmentRepository.save(departmentEntity1);
            log.info("DepartmentServiceImpl::draftDepartment::persisted draft in postgres");

            ObjectNode jsonNode = buildDocument(departmentEntity, Constants.DRAFT, currentTime, currentTime);
            Map<String, Object> map = objectMapper.convertValue(jsonNode, Map.class);
            esUtilService.addDocument(Constants.DEPARTMENT_INDEX_NAME, Constants.INDEX_TYPE,
                    String.valueOf(primaryID), map, vergProperties.getElasticDepartmentJsonPath());
            cacheService.putCache(primaryID, jsonNode);
            map.put(Constants.DEPARTMENT_ID_RQST, primaryID);
            response.setResult(map);
            response.setMessage(Constants.SUCCESSFULLY_CREATED);
            response.setResponseCode(HttpStatus.OK);
            // auditLogService.logAudit(primaryID, AUDIT_ENTITY_NAME, "draft", Constants.DRAFT,
            //         objectMapper.createObjectNode(), departmentEntity,
            //         departmentEntity1.getCreatedOn(), departmentEntity1.getUpdatedOn());
            return response;
        } catch (Exception e) {
            throw new CustomException("error while processing", e.getMessage(),
                    HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @Override
    public CustomResponse addDepartment(String id, JsonNode departmentEntity) {
        log.info("DepartmentServiceImpl::addDepartment:entered the method with id: {}", id);
        // Guard before the try block: the 404 must not be swallowed by the catch below
        lifecyclePolicy.requireEnabled(AUDIT_ENTITY_NAME);
        CustomResponse response = new CustomResponse();
        if (StringUtils.isEmpty(id)) {
            response.setResponseCode(HttpStatus.BAD_REQUEST);
            response.setMessage(Constants.ID_NOT_FOUND);
            return response;
        }
        // Full validation: all required fields must be present to submit for approval
        payloadValidation.validatePayload(Constants.DEPARTMENT_VALIDATION_FILE_JSON, departmentEntity);
        log.debug("DepartmentServiceImpl::addDepartment:validated the payload");
        try {
            Optional<DepartmentEntity> entityOptional = departmentRepository.findById(id);
            if (entityOptional.isEmpty()) {
                response.setResponseCode(HttpStatus.NOT_FOUND);
                response.setMessage(Constants.INVALID_ID);
                return response;
            }
            DepartmentEntity departmentEntity1 = entityOptional.get();
            // Only DRAFT or REWORK records can be (re-)submitted for approval
            if (!LifecycleUtil.ADD_PROMOTABLE.contains(departmentEntity1.getStatus())) {
                log.warn("DepartmentServiceImpl::addDepartment:record {} not in DRAFT/REWORK (status={})",
                        id, departmentEntity1.getStatus());
                response.setResponseCode(HttpStatus.CONFLICT);
                response.setMessage(Constants.INVALID_STATUS_TRANSITION);
                return response;
            }
            Timestamp currentTime = new Timestamp(System.currentTimeMillis());
            JsonNode auditBefore = departmentEntity1.getData();
            departmentEntity1.setData(departmentEntity);
            departmentEntity1.setStatus(Constants.PENDING);
            departmentEntity1.setUpdatedOn(currentTime);
            departmentRepository.save(departmentEntity1);
            log.info("DepartmentServiceImpl::addDepartment:submitted record {} for approval (PENDING)", id);

            ObjectNode jsonNode = buildDocument(departmentEntity, Constants.PENDING,
                    departmentEntity1.getCreatedOn(), currentTime);
            Map<String, Object> map = objectMapper.convertValue(jsonNode, Map.class);
            esUtilService.updateDocument(Constants.DEPARTMENT_INDEX_NAME, Constants.INDEX_TYPE,
                    id, map, vergProperties.getElasticDepartmentJsonPath());
            cacheService.putCache(id, jsonNode);
            map.put(Constants.DEPARTMENT_ID_RQST, id);
            response.setResult(map);
            response.setMessage(Constants.SUCCESSFULLY_UPDATED);
            response.setResponseCode(HttpStatus.OK);
            // auditLogService.logAudit(id, AUDIT_ENTITY_NAME, "add-promote", Constants.PENDING,
            //         auditBefore, departmentEntity,
            //         departmentEntity1.getCreatedOn(), departmentEntity1.getUpdatedOn());
            return response;
        } catch (Exception e) {
            throw new CustomException("error while processing", e.getMessage(),
                    HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @Override
    public CustomResponse approveDepartment(LifecycleRequest request) {
        log.info("DepartmentServiceImpl::approveDepartment:entered the method");
        lifecyclePolicy.requireEnabled(AUDIT_ENTITY_NAME);
        return transitionStatus(request, "approve", LifecycleUtil.APPROVE_FROM, LifecycleUtil.APPROVE_TARGETS);
    }

    @Override
    public CustomResponse reviewDepartment(LifecycleRequest request) {
        log.info("DepartmentServiceImpl::reviewDepartment:entered the method");
        lifecyclePolicy.requireEnabled(AUDIT_ENTITY_NAME);
        return transitionStatus(request, "review", LifecycleUtil.REVIEW_FROM, LifecycleUtil.REVIEW_TARGETS);
    }

    @Override
    public CustomResponse toggleStatus(String id) {
        log.info("DepartmentServiceImpl::toggleStatus:entered the method with id: {}", id);
        CustomResponse response = new CustomResponse();
        if (StringUtils.isEmpty(id)) {
            response.setResponseCode(HttpStatus.BAD_REQUEST);
            response.setMessage(Constants.ID_NOT_FOUND);
            return response;
        }
        try {
            Optional<DepartmentEntity> entityOptional = departmentRepository.findById(id);
            if (entityOptional.isEmpty()) {
                response.setResponseCode(HttpStatus.NOT_FOUND);
                response.setMessage(Constants.INVALID_ID);
                return response;
            }
            DepartmentEntity departmentEntity1 = entityOptional.get();
            String currentStatus = departmentEntity1.getStatus();
            String newStatus;
            if (Constants.ACTIVE.equals(currentStatus)) {
                newStatus = Constants.IN_ACTIVE;
            } else if (Constants.IN_ACTIVE.equals(currentStatus)) {
                newStatus = Constants.ACTIVE;
            } else {
                // Only a published (ACTIVE) or deactivated (INACTIVE) record can be toggled
                log.warn("DepartmentServiceImpl::toggleStatus:record {} is {}, can only toggle ACTIVE<->INACTIVE",
                        id, currentStatus);
                response.setResponseCode(HttpStatus.CONFLICT);
                response.setMessage(Constants.INVALID_STATUS_TRANSITION);
                return response;
            }
            Timestamp currentTime = new Timestamp(System.currentTimeMillis());
            departmentEntity1.setStatus(newStatus);
            departmentEntity1.setUpdatedOn(currentTime);
            departmentRepository.save(departmentEntity1);
            log.info("DepartmentServiceImpl::toggleStatus:record {} toggled {} -> {}", id, currentStatus, newStatus);

            ObjectNode jsonNode = buildDocument(departmentEntity1.getData(), newStatus,
                    departmentEntity1.getCreatedOn(), currentTime);
            Map<String, Object> map = objectMapper.convertValue(jsonNode, Map.class);
            esUtilService.updateDocument(Constants.DEPARTMENT_INDEX_NAME, Constants.INDEX_TYPE,
                    id, map, vergProperties.getElasticDepartmentJsonPath());
            cacheService.putCache(id, jsonNode);
            map.put(Constants.DEPARTMENT_ID_RQST, id);
            response.setResult(map);
            response.setMessage(Constants.SUCCESSFULLY_UPDATED);
            response.setResponseCode(HttpStatus.OK);
            // auditLogService.logAudit(id, AUDIT_ENTITY_NAME, "toggle", newStatus,
            //         departmentEntity1.getData(), departmentEntity1.getData(),
            //         departmentEntity1.getCreatedOn(), departmentEntity1.getUpdatedOn());
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
            log.warn("DepartmentServiceImpl::transitionStatus:invalid target status '{}' for id {}",
                    request.getStatus(), id);
            response.setResponseCode(HttpStatus.BAD_REQUEST);
            response.setMessage(Constants.INVALID_STATUS);
            return response;
        }
        try {
            Optional<DepartmentEntity> entityOptional = departmentRepository.findById(id);
            if (entityOptional.isEmpty()) {
                response.setResponseCode(HttpStatus.NOT_FOUND);
                response.setMessage(Constants.INVALID_ID);
                return response;
            }
            DepartmentEntity departmentEntity1 = entityOptional.get();
            if (!requiredCurrentStatus.equals(departmentEntity1.getStatus())) {
                log.warn("DepartmentServiceImpl::transitionStatus:record {} is {}, requires {}",
                        id, departmentEntity1.getStatus(), requiredCurrentStatus);
                response.setResponseCode(HttpStatus.CONFLICT);
                response.setMessage(Constants.INVALID_STATUS_TRANSITION);
                return response;
            }
            Timestamp currentTime = new Timestamp(System.currentTimeMillis());
            departmentEntity1.setStatus(targetStatus);
            departmentEntity1.setUpdatedOn(currentTime);
            departmentRepository.save(departmentEntity1);
            log.info("DepartmentServiceImpl::transitionStatus:record {} moved {} -> {}",
                    id, requiredCurrentStatus, targetStatus);

            ObjectNode jsonNode = buildDocument(departmentEntity1.getData(), targetStatus,
                    departmentEntity1.getCreatedOn(), currentTime);
            Map<String, Object> map = objectMapper.convertValue(jsonNode, Map.class);
            esUtilService.updateDocument(Constants.DEPARTMENT_INDEX_NAME, Constants.INDEX_TYPE,
                    id, map, vergProperties.getElasticDepartmentJsonPath());
            cacheService.putCache(id, jsonNode);
            map.put(Constants.DEPARTMENT_ID_RQST, id);
            response.setResult(map);
            response.setMessage(Constants.SUCCESSFULLY_UPDATED);
            response.setResponseCode(HttpStatus.OK);
            // auditLogService.logAudit(id, AUDIT_ENTITY_NAME, operation, targetStatus,
            //         departmentEntity1.getData(), departmentEntity1.getData(),
            //         departmentEntity1.getCreatedOn(), departmentEntity1.getUpdatedOn());
            return response;
        } catch (Exception e) {
            throw new CustomException("error while processing", e.getMessage(),
                    HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    /**
     * Builds the projection stored in Elasticsearch and Redis (and returned by read): the payload
     * plus the lifecycle status and the Postgres createdOn/updatedOn timestamps (ISO-8601). ES keeps
     * only whitelisted keys, so status/createdOn/updatedOn must be present in esDepartmentRequiredFields.json.
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