package com.amalitech.labresultsvalidator.domain.cohort.controller;

import com.amalitech.labresultsvalidator.common.response.ApiResponse;
import com.amalitech.labresultsvalidator.common.response.PagedResponse;
import com.amalitech.labresultsvalidator.domain.cohort.dto.AttachSharePointLinkRequest;
import com.amalitech.labresultsvalidator.domain.cohort.dto.CohortResponse;
import com.amalitech.labresultsvalidator.domain.cohort.dto.CreateCohortRequest;
import com.amalitech.labresultsvalidator.domain.cohort.dto.StandUpJobResponse;
import com.amalitech.labresultsvalidator.domain.cohort.service.CohortService;
import com.amalitech.labresultsvalidator.domain.cohort.service.CohortStandUpService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@Tag(name = "Cohorts", description = "Cohort creation and stand-up (admin only)")
@RestController
@RequestMapping("/api/v1/admin/cohorts")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
public class CohortController {

    private final CohortService cohortService;
    private final CohortStandUpService cohortStandUpService;

    @Operation(summary = "Create a cohort", description = "Creates a cohort in DRAFT state.")
    @PostMapping
    public ResponseEntity<ApiResponse<CohortResponse>> createCohort(
            @Valid @RequestBody CreateCohortRequest request) {
        CohortResponse response = cohortService.createCohort(request);
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse.success("Cohort created", response));
    }

    @Operation(summary = "List cohorts", description = "Paged list of all cohorts.")
    @GetMapping
    public ResponseEntity<ApiResponse<PagedResponse<CohortResponse>>> getCohorts(Pageable pageable) {
        return ResponseEntity.ok(ApiResponse.success("Cohorts retrieved", cohortService.getCohorts(pageable)));
    }

    @Operation(
        summary = "Attach a SharePoint folder link",
        description = "Stores the SharePoint folder link against a DRAFT cohort."
    )
    @PatchMapping("/{id}/sharepoint-link")
    public ResponseEntity<ApiResponse<CohortResponse>> attachSharePointLink(
            @PathVariable UUID id,
            @Valid @RequestBody AttachSharePointLinkRequest request) {
        CohortResponse response = cohortService.attachSharePointLink(id, request);
        return ResponseEntity.ok(ApiResponse.success("SharePoint link attached", response));
    }

    @Operation(
        summary = "Start a stand-up job",
        description = "Starts a stand-up validation job for the cohort. Rejected if one is already running."
    )
    @PostMapping("/{id}/standup")
    public ResponseEntity<ApiResponse<StandUpJobResponse>> startStandUp(@PathVariable UUID id) {
        StandUpJobResponse response = cohortStandUpService.startStandUp(id);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
            .body(ApiResponse.success("Stand-up job started", response));
    }
}
