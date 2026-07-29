package com.amalitech.labresultsvalidator.domain.cohort.controller;

import com.amalitech.labresultsvalidator.common.exceptions.DuplicateResourceException;
import com.amalitech.labresultsvalidator.common.response.ApiResponse;
import com.amalitech.labresultsvalidator.domain.cohort.dto.AttachSharePointLinkRequest;
import com.amalitech.labresultsvalidator.domain.cohort.dto.CohortReferenceResponse;
import com.amalitech.labresultsvalidator.domain.cohort.dto.CohortResponse;
import com.amalitech.labresultsvalidator.domain.cohort.dto.CreateCohortRequest;
import com.amalitech.labresultsvalidator.domain.cohort.dto.Gate4JobResponse;
import com.amalitech.labresultsvalidator.domain.cohort.dto.StandUpJobResponse;
import com.amalitech.labresultsvalidator.domain.cohort.dto.StandupRunResponse;
import com.amalitech.labresultsvalidator.domain.cohort.repository.CohortStandUpJobRepository;
import com.amalitech.labresultsvalidator.domain.cohort.service.CohortGate4Service;
import com.amalitech.labresultsvalidator.domain.cohort.service.CohortReferenceQueryService;
import com.amalitech.labresultsvalidator.domain.cohort.service.CohortService;
import com.amalitech.labresultsvalidator.domain.cohort.service.CohortStandUpService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/cohorts")
@RequiredArgsConstructor
@Tag(name = "Cohorts", description = "Cohort management and stand-up pipeline")
public class CohortController {

    private static final Logger LOG = LoggerFactory.getLogger(CohortController.class);

    private final CohortService cohortService;
    private final CohortStandUpService cohortStandUpService;
    private final CohortStandUpJobRepository standUpJobRepository;
    private final CohortGate4Service cohortGate4Service;
    private final CohortReferenceQueryService cohortReferenceQueryService;

    @Operation(summary = "List all cohorts", description = "Returns a paginated list of all cohorts.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
        description = "Cohort list returned")
    @GetMapping
    public ResponseEntity<ApiResponse<Page<CohortResponse>>> getCohorts(
        @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable
    ) {
        return ResponseEntity.ok(ApiResponse.success("Cohorts retrieved.", cohortService.getCohorts(pageable)));
    }

    @Operation(summary = "Create a new cohort", description = "Creates a cohort in DRAFT lifecycle state.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201",
            description = "Cohort created successfully"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
            description = "Validation error"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409",
            description = "Cohort with same name already exists")
    })
    @PostMapping
    public ResponseEntity<ApiResponse<CohortResponse>> createCohort(
        @Valid @RequestBody CreateCohortRequest req
    ) {
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse.success("Cohort created successfully.", cohortService.createCohort(req)));
    }

    @Operation(summary = "Get cohort details", description = "Returns the current state of a cohort.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
            description = "Cohort details returned"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
            description = "Cohort not found")
    })
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<CohortResponse>> getCohort(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.success("Cohort retrieved.", cohortService.getCohort(id)));
    }

    @Operation(summary = "Attach SharePoint folder link",
        description = "Associates a SharePoint folder URL with a cohort and immediately initiates "
            + "the stand-up pipeline asynchronously. Cohort must be in DRAFT state.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
            description = "SharePoint link attached and stand-up pipeline initiated"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
            description = "Cohort not found"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "422",
            description = "Cohort is not in DRAFT state")
    })
    @PatchMapping("/{id}/sharepoint-link")
    public ResponseEntity<ApiResponse<CohortResponse>> attachSharePointLink(
        @PathVariable UUID id,
        @Valid @RequestBody AttachSharePointLinkRequest req
    ) {
        CohortResponse cohort = cohortService.attachSharePointLink(id, req);
        try {
            LOG.info("Starting stand-up pipeline for cohort {}", id);
            cohortStandUpService.startStandUp(id);
        } catch (DuplicateResourceException ex) {
            LOG.warn("Stand-up already running for cohort {}; skipping auto-trigger after URL update.", id);
        }
        return ResponseEntity.ok(ApiResponse.success("SharePoint link attached. Stand-up pipeline initiated.", cohort));
    }

    @Operation(summary = "Re-trigger stand-up pipeline",
        description = "Manually creates a new stand-up job and runs the pipeline. "
            + "Use to re-run after fixing SharePoint content without changing the URL. "
            + "Enforces one running job per cohort.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "202",
            description = "Stand-up job created and pipeline running"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
            description = "Cohort not found"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409",
            description = "A stand-up job is already running for this cohort"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "422",
            description = "Cohort is not in DRAFT state or has no SharePoint link")
    })
    @PostMapping("/{id}/standup")
    public ResponseEntity<ApiResponse<StandUpJobResponse>> startStandUp(@PathVariable UUID id) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
            .body(ApiResponse.success("Stand-up job started.", cohortStandUpService.startStandUp(id)));
    }

    @Operation(summary = "Accept reference data",
        description = "Commits the validated reference bundle and transitions cohort to REFERENCE_ACCEPTED.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
            description = "Reference data accepted and committed"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "422",
            description = "No validated bundle found or bundle has expired")
    })
    @PostMapping("/{id}/accept")
    public ResponseEntity<ApiResponse<Void>> acceptReference(@PathVariable UUID id) {
        cohortService.acceptReference(id);
        return ResponseEntity.ok(ApiResponse.success("Reference data accepted and committed.", null));
    }

    @Operation(summary = "List stand-up runs",
        description = "Returns a paginated list of all stand-up jobs for a cohort, newest first.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
            description = "Stand-up runs returned")
    })
    @GetMapping("/{id}/standup/runs")
    public ResponseEntity<ApiResponse<Page<StandupRunResponse>>> listStandupRuns(
        @PathVariable UUID id,
        @PageableDefault(size = 20) Pageable pageable
    ) {
        Page<StandupRunResponse> runs = standUpJobRepository
            .findByCohortIdOrderByStartedAtDesc(id, pageable)
            .map(StandupRunResponse::from);
        return ResponseEntity.ok(ApiResponse.success("Stand-up runs retrieved.", runs));
    }

    @Operation(summary = "Discard accepted reference data",
        description = "Deletes committed reference data and resets cohort to DRAFT. "
            + "Only allowed when cohort is in REFERENCE_ACCEPTED state (before Gate 4 runs).")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
            description = "Reference data discarded and cohort reset to DRAFT"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
            description = "Cohort not found"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "422",
            description = "Cohort is not in REFERENCE_ACCEPTED state")
    })
    @DeleteMapping("/{id}/accept")
    public ResponseEntity<ApiResponse<Void>> discardReference(@PathVariable UUID id) {
        cohortService.discardReference(id);
        return ResponseEntity.ok(ApiResponse.success("Reference data discarded. Cohort reset to DRAFT.", null));
    }

    @Operation(summary = "Start Gate 4 — score sheet validation",
        description = "Asynchronously validates score sheets against the committed reference data. "
            + "On pass, transitions cohort to STOOD_UP. Stream results via GET /{id}/gate4/stream.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "202",
            description = "Gate 4 job created and validation running"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
            description = "Cohort not found"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409",
            description = "A Gate 4 job is already running for this cohort"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "422",
            description = "Cohort is not in REFERENCE_ACCEPTED state or missing SharePoint reference")
    })
    @PostMapping("/{id}/gate4")
    public ResponseEntity<ApiResponse<Gate4JobResponse>> startGate4(@PathVariable UUID id) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
            .body(ApiResponse.success("Gate 4 job started.", cohortGate4Service.startGate4(id)));
    }

    @Operation(summary = "Get cohort reference bundle",
        description = "Returns the committed reference data for a cohort in one call: "
            + "specializations (with nested modules and labs), learners, and instructor contacts.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
            description = "Cohort reference data returned"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
            description = "Cohort not found")
    })
    @GetMapping("/{id}/reference")
    public ResponseEntity<ApiResponse<CohortReferenceResponse>> getCohortReference(@PathVariable UUID id) {
        return ResponseEntity.ok(
            ApiResponse.success("Cohort reference data retrieved.", cohortReferenceQueryService.getCohortReference(id)));
    }
}
