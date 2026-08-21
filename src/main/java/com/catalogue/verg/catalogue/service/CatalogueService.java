package com.catalogue.verg.catalogue.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.catalogue.verg.core.dto.CustomResponse;
import com.catalogue.verg.core.dto.LifecycleRequest;
import com.catalogue.verg.core.elasticsearch.dto.SearchCriteria;
import org.springframework.web.multipart.MultipartFile;


public interface CatalogueService {

    CustomResponse createCatalogue(JsonNode catalogueEntity);

    CustomResponse updateCatalogue(String id, JsonNode catalogueEntity);

    // Lifecycle: create an incomplete DRAFT (relaxed validation)
    CustomResponse draftCatalogue(JsonNode catalogueEntity);

    // Lifecycle: (re-)submit a DRAFT/REWORK record for approval -> PENDING (full validation)
    CustomResponse addCatalogue(String id, JsonNode catalogueEntity);

    // Lifecycle: PENDING -> APPROVED | REJECTED | REWORK
    CustomResponse approveCatalogue(LifecycleRequest request);

    // Lifecycle: APPROVED -> ACTIVE(published) | REJECTED | REWORK | PENDING
    CustomResponse reviewCatalogue(LifecycleRequest request);

    // Toggle a live record between ACTIVE and INACTIVE (rejects any other status)
    CustomResponse toggleStatus(String id);

    CustomResponse searchCatalogue(SearchCriteria searchCriteria);

    CustomResponse assignCatalogue(JsonNode catalogueEntity, String token);

    CustomResponse read(String id);

    CustomResponse delete(String id);

    CustomResponse importData(MultipartFile file);

    // Drops the ES index and rebuilds it from the primary store (Postgres); skips DELETED records
    CustomResponse loadFromPrimaryCatalogue();
}