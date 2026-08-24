package com.catalogue.verg.departmenttype.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.catalogue.verg.core.dto.CustomResponse;
import com.catalogue.verg.core.dto.LifecycleRequest;
import com.catalogue.verg.core.elasticsearch.dto.SearchCriteria;
import org.springframework.web.multipart.MultipartFile;


public interface DepartmenttypeService {

    CustomResponse createDepartmenttype(JsonNode departmenttypeEntity);

    CustomResponse updateDepartmenttype(String id, JsonNode departmenttypeEntity);

    // Lifecycle: create an incomplete DRAFT (relaxed validation)
    CustomResponse draftDepartmenttype(JsonNode departmenttypeEntity);

    // Lifecycle: (re-)submit a DRAFT/REWORK record for approval -> PENDING (full validation)
    CustomResponse addDepartmenttype(String id, JsonNode departmenttypeEntity);

    // Lifecycle: PENDING -> APPROVED | REJECTED | REWORK
    CustomResponse approveDepartmenttype(LifecycleRequest request);

    // Lifecycle: APPROVED -> ACTIVE(published) | REJECTED | REWORK | PENDING
    CustomResponse reviewDepartmenttype(LifecycleRequest request);

    // Toggle a live record between ACTIVE and INACTIVE (rejects any other status)
    CustomResponse toggleStatus(String id);

    CustomResponse searchDepartmenttype(SearchCriteria searchCriteria);

    CustomResponse assignDepartmenttype(JsonNode departmenttypeEntity, String token);

    CustomResponse read(String id);

    CustomResponse delete(String id);

    CustomResponse importData(MultipartFile file);

    // Drops the ES index and rebuilds it from the primary store (Postgres); skips DELETED records
    CustomResponse loadFromPrimaryDepartmenttype();
}