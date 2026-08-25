package com.catalogue.verg.orgtype.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.catalogue.verg.core.dto.CustomResponse;
import com.catalogue.verg.core.dto.LifecycleRequest;
import com.catalogue.verg.core.elasticsearch.dto.SearchCriteria;
import org.springframework.web.multipart.MultipartFile;


public interface OrgtypeService {

    CustomResponse createOrgtype(JsonNode orgtypeEntity);

    CustomResponse updateOrgtype(String id, JsonNode orgtypeEntity);

    // Lifecycle: create an incomplete DRAFT (relaxed validation)
    CustomResponse draftOrgtype(JsonNode orgtypeEntity);

    // Lifecycle: (re-)submit a DRAFT/REWORK record for approval -> PENDING (full validation)
    CustomResponse addOrgtype(String id, JsonNode orgtypeEntity);

    // Lifecycle: PENDING -> APPROVED | REJECTED | REWORK
    CustomResponse approveOrgtype(LifecycleRequest request);

    // Lifecycle: APPROVED -> ACTIVE(published) | REJECTED | REWORK | PENDING
    CustomResponse reviewOrgtype(LifecycleRequest request);

    // Toggle a live record between ACTIVE and INACTIVE (rejects any other status)
    CustomResponse toggleStatus(String id);

    CustomResponse searchOrgtype(SearchCriteria searchCriteria);

    CustomResponse assignOrgtype(JsonNode orgtypeEntity, String token);

    CustomResponse read(String id);

    CustomResponse delete(String id);

    CustomResponse importData(MultipartFile file);

    // Drops the ES index and rebuilds it from the primary store (Postgres); skips DELETED records
    CustomResponse loadFromPrimaryOrgtype();
}