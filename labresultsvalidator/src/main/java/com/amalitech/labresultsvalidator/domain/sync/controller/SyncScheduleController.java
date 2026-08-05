package com.amalitech.labresultsvalidator.domain.sync.controller;

import com.amalitech.labresultsvalidator.common.response.ApiResponse;
import com.amalitech.labresultsvalidator.domain.sync.dto.SyncScheduleRequest;
import com.amalitech.labresultsvalidator.domain.sync.dto.SyncScheduleResponse;
import com.amalitech.labresultsvalidator.domain.sync.service.SyncScheduleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/sync-schedules")
@RequiredArgsConstructor
@Tag(name = "Sync Schedules", description = "User-defined recurring schedules that trigger score sheet sync runs")
public class SyncScheduleController {

    private final SyncScheduleService syncScheduleService;

    @Operation(summary = "Create a sync schedule",
        description = "Registers a new recurring schedule and activates it immediately. Omit cohortId to run "
            + "the 'all eligible cohorts' batch; provide it to schedule sync for a single cohort.")
    @PostMapping
    public ResponseEntity<ApiResponse<SyncScheduleResponse>> create(@Valid @RequestBody SyncScheduleRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse.success("Sync schedule created.", syncScheduleService.create(request)));
    }

    @Operation(summary = "List sync schedules")
    @GetMapping
    public ResponseEntity<ApiResponse<List<SyncScheduleResponse>>> list() {
        return ResponseEntity.ok(ApiResponse.success("Sync schedules retrieved.", syncScheduleService.list()));
    }

    @Operation(summary = "Get a sync schedule")
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<SyncScheduleResponse>> get(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.success("Sync schedule retrieved.", syncScheduleService.get(id)));
    }

    @Operation(summary = "Update a sync schedule",
        description = "Full replace of the schedule's configuration, including its enabled flag. "
            + "Re-registers the schedule immediately if enabled, cancels it if disabled.")
    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<SyncScheduleResponse>> update(
        @PathVariable UUID id,
        @Valid @RequestBody SyncScheduleRequest request
    ) {
        return ResponseEntity.ok(
            ApiResponse.success("Sync schedule updated.", syncScheduleService.update(id, request)));
    }

    @Operation(summary = "Delete a sync schedule")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        syncScheduleService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
