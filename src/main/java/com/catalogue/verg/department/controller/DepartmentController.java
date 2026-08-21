package com.catalogue.verg.department.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.catalogue.verg.core.config.LifecyclePolicy;
import com.catalogue.verg.core.dto.CustomResponse;
import com.catalogue.verg.core.dto.LifecycleRequest;
import com.catalogue.verg.core.elasticsearch.dto.SearchCriteria;
import com.catalogue.verg.department.service.DepartmentService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/department")
public class DepartmentController {
    @Autowired
    private DepartmentService departmentService;

    @Autowired
    private LifecyclePolicy lifecyclePolicy;

    /** Key this catalogue is looked up by in the lifecycle switches. */
    private static final String CATALOGUE_NAME = "department";

    //@PostMapping("/v1/create")
    public ResponseEntity<CustomResponse> create(@RequestBody JsonNode departmentDetails) {
        CustomResponse response = departmentService.createDepartment(departmentDetails);
        return new ResponseEntity<>(response, response.getResponseCode());
    }

    // Lifecycle: create an incomplete DRAFT (relaxed validation)
    @PostMapping("/v1/draft")
    public ResponseEntity<CustomResponse> draft(@RequestBody JsonNode departmentDetails) {
        lifecyclePolicy.requireEnabled(CATALOGUE_NAME);
        CustomResponse response = departmentService.draftDepartment(departmentDetails);
        return new ResponseEntity<>(response, response.getResponseCode());
    }

    // Creates a new record (full validation). With the lifecycle on it lands PENDING and has
    // to be approved then reviewed; with the lifecycle off it lands ACTIVE straight away.
    @PostMapping("/v1/add")
    public ResponseEntity<CustomResponse> add(@RequestBody JsonNode departmentDetails) {
        CustomResponse response = departmentService.createDepartment(departmentDetails);
        return new ResponseEntity<>(response, response.getResponseCode());
    }

    // Lifecycle: (re-)submit an existing DRAFT/REWORK record for approval (PENDING, full validation)
    @PutMapping("/v1/add/{id}")
    public ResponseEntity<CustomResponse> addById(@PathVariable String id, @RequestBody JsonNode departmentDetails) {
        lifecyclePolicy.requireEnabled(CATALOGUE_NAME);
        CustomResponse response = departmentService.addDepartment(id, departmentDetails);
        return new ResponseEntity<>(response, response.getResponseCode());
    }

    // Lifecycle: PENDING -> APPROVED | REJECTED | REWORK
    @PutMapping("/v1/approve")
    public ResponseEntity<CustomResponse> approve(@RequestBody LifecycleRequest request) {
        lifecyclePolicy.requireEnabled(CATALOGUE_NAME);
        CustomResponse response = departmentService.approveDepartment(request);
        return new ResponseEntity<>(response, response.getResponseCode());
    }

    // Lifecycle: APPROVED -> ACTIVE(published) | REJECTED | REWORK
    @PutMapping("/v1/review")
    public ResponseEntity<CustomResponse> review(@RequestBody LifecycleRequest request) {
        lifecyclePolicy.requireEnabled(CATALOGUE_NAME);
        CustomResponse response = departmentService.reviewDepartment(request);
        return new ResponseEntity<>(response, response.getResponseCode());
    }

    // Toggle a live record between ACTIVE and INACTIVE (rejects any other status).
    // Deliberately NOT gated: plain activate/deactivate, not part of the approval chain, and
    // with the lifecycle off it is the only way to take a record offline short of deleting it.
    @PutMapping("/v1/toggle/{id}")
    public ResponseEntity<CustomResponse> toggle(@PathVariable String id) {
        CustomResponse response = departmentService.toggleStatus(id);
        return new ResponseEntity<>(response, response.getResponseCode());
    }

    @PostMapping("/v1/search")
    public ResponseEntity<?> search(@RequestBody SearchCriteria searchCriteria) {
        CustomResponse response = departmentService.searchDepartment(searchCriteria);
        return new ResponseEntity<>(response, response.getResponseCode());
    }

    @GetMapping("/v1/read/{id}")
    public ResponseEntity<?> read(@PathVariable String id) {
        CustomResponse response = departmentService.read(id);
        return new ResponseEntity<>(response, HttpStatus.OK);
    }

    @PutMapping("/v1/update/{id}")
    public ResponseEntity<CustomResponse> update(@PathVariable String id, @RequestBody JsonNode departmentDetails) {
        CustomResponse response = departmentService.updateDepartment(id, departmentDetails);
        return new ResponseEntity<>(response, response.getResponseCode());
    }

    @DeleteMapping("/v1/delete/{id}")
    public ResponseEntity<?> delete(@PathVariable String id) {
        CustomResponse response = departmentService.delete(id);
        return new ResponseEntity<>(response, HttpStatus.OK);
    }

    @PostMapping("/v1/import")
    public ResponseEntity<CustomResponse> importData(@RequestParam("file") MultipartFile file) {
        CustomResponse response = departmentService.importData(file);
        return new ResponseEntity<>(response, response.getResponseCode());
    }

    // Drops the ES index and rebuilds it from the primary store (Postgres); skips DELETED records
    @PostMapping("/v1/loadFromPrimary")
    public ResponseEntity<CustomResponse> loadFromPrimary() {
        CustomResponse response = departmentService.loadFromPrimaryDepartment();
        return new ResponseEntity<>(response, response.getResponseCode());
    }
}