package com.amalitech.labresultsvalidator.domain.cohort.controller;

import com.amalitech.labresultsvalidator.common.response.ApiResponse;
import com.amalitech.labresultsvalidator.domain.cohort.dto.CohortResponse;
import com.amalitech.labresultsvalidator.domain.cohort.dto.CreateCohortRequest;
import com.amalitech.labresultsvalidator.domain.cohort.dto.Gate4ResultDto;
import com.amalitech.labresultsvalidator.domain.cohort.dto.SetSharePointLinkRequest;
import com.amalitech.labresultsvalidator.domain.cohort.dto.StandupResultDto;
import com.amalitech.labresultsvalidator.domain.cohort.service.CohortService;
import com.amalitech.labresultsvalidator.domain.user.entity.User;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/cohorts")
@RequiredArgsConstructor
@Tag(name = "Cohorts", description = "Cohort management and stand-up pipeline")
public class CohortController {

    private final CohortService cohortService;

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
        @Valid @RequestBody CreateCohortRequest req,
        Authentication auth
    ) {
        CohortResponse response = cohortService.createCohort(req, resolveUserId(auth));
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse.success("Cohort created successfully.", response));
    }

    @Operation(summary = "Set SharePoint folder link",
        description = "Associates a SharePoint folder URL with a cohort. Cohort must be in DRAFT state.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
            description = "SharePoint link updated"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
            description = "Cohort not found"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "422",
            description = "Cohort is not in DRAFT state")
    })
    @PutMapping("/{id}/link")
    public ResponseEntity<ApiResponse<CohortResponse>> setLink(
        @PathVariable UUID id,
        @Valid @RequestBody SetSharePointLinkRequest req,
        Authentication auth
    ) {
        CohortResponse response = cohortService.setSharePointLink(id, req, resolveUserId(auth));
        return ResponseEntity.ok(ApiResponse.success("SharePoint link updated.", response));
    }

    @Operation(summary = "Run stand-up Gates 1–3",
        description = "Validates the SharePoint link, folder structure, and reference data files.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
            description = "Stand-up gate results returned (check gate1/gate2/gate3 state for pass/fail)"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
            description = "Cohort not found"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "422",
            description = "Cohort is not in DRAFT state or has no SharePoint link")
    })
    @PostMapping("/{id}/standup")
    public ResponseEntity<ApiResponse<StandupResultDto>> runStandup(
        @PathVariable UUID id,
        Authentication auth
    ) {
        StandupResultDto result = cohortService.runStandup(id, resolveUserId(auth));
        return ResponseEntity.ok(ApiResponse.success("Stand-up gates completed.", result));
    }

    @Operation(summary = "Accept reference data",
        description = "Commits the validated reference bundle to the database and transitions "
            + "cohort to REFERENCE_ACCEPTED state.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
            description = "Reference data accepted and committed"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
            description = "Cohort not found"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "422",
            description = "No validated bundle found or bundle has expired")
    })
    @PostMapping("/{id}/accept")
    public ResponseEntity<ApiResponse<Void>> acceptReference(
        @PathVariable UUID id,
        Authentication auth
    ) {
        cohortService.acceptReference(id, resolveUserId(auth));
        return ResponseEntity.ok(ApiResponse.success("Reference data accepted and committed.", null));
    }

    @Operation(summary = "Run Gate 4 — score sheet validation",
        description = "Validates the scores folder XLSX files against the committed reference data. "
            + "On success, transitions cohort to STOOD_UP state.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
            description = "Gate 4 results returned (check gate4 state for pass/fail)"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
            description = "Cohort not found"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "422",
            description = "Cohort is not in REFERENCE_ACCEPTED state")
    })
    @PostMapping("/{id}/gate4")
    public ResponseEntity<ApiResponse<Gate4ResultDto>> runGate4(
        @PathVariable UUID id,
        Authentication auth
    ) {
        Gate4ResultDto result = cohortService.runGate4(id, resolveUserId(auth));
        return ResponseEntity.ok(ApiResponse.success("Gate 4 completed.", result));
    }

    @Operation(summary = "Get cohort details", description = "Returns the current state of a cohort.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
            description = "Cohort details returned"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
            description = "Cohort not found")
    })
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<CohortResponse>> getCohort(
        @PathVariable UUID id,
        Authentication auth
    ) {
        CohortResponse response = cohortService.getCohort(id);
        return ResponseEntity.ok(ApiResponse.success("Cohort retrieved.", response));
    }

    private UUID resolveUserId(Authentication auth) {
        User user = (User) auth.getPrincipal();
        return user.getId();
    }
}
