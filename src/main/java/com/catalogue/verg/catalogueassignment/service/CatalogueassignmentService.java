package com.catalogue.verg.catalogueassignment.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.catalogue.verg.core.dto.CustomResponse;
import com.catalogue.verg.core.dto.LifecycleRequest;
import com.catalogue.verg.core.elasticsearch.dto.SearchCriteria;
import org.springframework.web.multipart.MultipartFile;


public interface CatalogueassignmentService {

    CustomResponse createCatalogueassignment(JsonNode catalogueassignmentEntity);

    CustomResponse updateCatalogueassignment(String id, JsonNode catalogueassignmentEntity);

    // Lifecycle: create an incomplete DRAFT (relaxed validation)
    CustomResponse draftCatalogueassignment(JsonNode catalogueassignmentEntity);

    // Lifecycle: (re-)submit a DRAFT/REWORK record for approval -> PENDING (full validation)
    CustomResponse addCatalogueassignment(String id, JsonNode catalogueassignmentEntity);

    // Lifecycle: PENDING -> APPROVED | REJECTED | REWORK
    CustomResponse approveCatalogueassignment(LifecycleRequest request);

    // Lifecycle: APPROVED -> ACTIVE(published) | REJECTED | REWORK | PENDING
    CustomResponse reviewCatalogueassignment(LifecycleRequest request);

    // Toggle a live record between ACTIVE and INACTIVE (rejects any other status)
    CustomResponse toggleStatus(String id);

    CustomResponse searchCatalogueassignment(SearchCriteria searchCriteria);

    CustomResponse assignCatalogueassignment(JsonNode catalogueassignmentEntity, String token);

    CustomResponse read(String id);

    CustomResponse delete(String id);

    CustomResponse importData(MultipartFile file);

    // Drops the ES index and rebuilds it from the primary store (Postgres); skips DELETED records
    CustomResponse loadFromPrimaryCatalogueassignment();
}