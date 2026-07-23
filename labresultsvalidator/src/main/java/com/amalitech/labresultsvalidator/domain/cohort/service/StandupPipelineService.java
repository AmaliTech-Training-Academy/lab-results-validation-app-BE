package com.amalitech.labresultsvalidator.domain.cohort.service;

import com.amalitech.labresultsvalidator.common.exceptions.ResourceNotFoundException;
import com.amalitech.labresultsvalidator.common.exceptions.UnprocessableEntityException;
import com.amalitech.labresultsvalidator.domain.cohort.dto.GateStateDto;
import com.amalitech.labresultsvalidator.domain.cohort.dto.StandupResultDto;
import com.amalitech.labresultsvalidator.domain.cohort.entity.Cohort;
import com.amalitech.labresultsvalidator.domain.cohort.entity.CohortStandupPending;
import com.amalitech.labresultsvalidator.domain.cohort.gate.Gate1LinkValidator;
import com.amalitech.labresultsvalidator.domain.cohort.gate.Gate1Result;
import com.amalitech.labresultsvalidator.domain.cohort.gate.Gate2FolderValidator;
import com.amalitech.labresultsvalidator.domain.cohort.gate.Gate2Result;
import com.amalitech.labresultsvalidator.domain.cohort.gate.Gate3ReferenceValidator;
import com.amalitech.labresultsvalidator.domain.cohort.gate.Gate3Result;
import com.amalitech.labresultsvalidator.domain.cohort.gate.ReferenceSummary;
import com.amalitech.labresultsvalidator.domain.cohort.gate.ValidatedReferenceBundle;
import com.amalitech.labresultsvalidator.domain.cohort.repository.CohortRepository;
import com.amalitech.labresultsvalidator.domain.cohort.repository.CohortStandupPendingRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

@Service
public class StandupPipelineService {

    private final Gate1LinkValidator gate1;
    private final Gate2FolderValidator gate2;
    private final Gate3ReferenceValidator gate3;
    private final CohortRepository cohortRepository;
    private final CohortStandupPendingRepository pendingRepository;
    private final AuditEventService auditEventService;
    private final ObjectMapper objectMapper;
    private final long pendingBundleTtlSeconds;

    public StandupPipelineService(
        Gate1LinkValidator gate1,
        Gate2FolderValidator gate2,
        Gate3ReferenceValidator gate3,
        CohortRepository cohortRepository,
        CohortStandupPendingRepository pendingRepository,
        AuditEventService auditEventService,
        ObjectMapper objectMapper,
        @Value("${labgate.standup.pending-bundle-ttl-seconds}") long pendingBundleTtlSeconds
    ) {
        this.gate1 = gate1;
        this.gate2 = gate2;
        this.gate3 = gate3;
        this.cohortRepository = cohortRepository;
        this.pendingRepository = pendingRepository;
        this.auditEventService = auditEventService;
        this.objectMapper = objectMapper;
        this.pendingBundleTtlSeconds = pendingBundleTtlSeconds;
    }

    @Transactional
    public StandupResultDto runGates123(UUID cohortId, UUID actorUserId) {
        Cohort cohort = cohortRepository.findByIdAndIsActiveTrue(cohortId)
            .orElseThrow(() -> new ResourceNotFoundException("Cohort not found with id: " + cohortId));

        if (!"DRAFT".equals(cohort.getLifecycleState())) {
            throw new UnprocessableEntityException("Cohort must be in DRAFT state to run stand-up.");
        }

        if (cohort.getSharepointFolderUrl() == null || cohort.getSharepointFolderUrl().isBlank()) {
            throw new UnprocessableEntityException("No SharePoint link set on this cohort.");
        }

        Gate1Result g1 = gate1.validate(cohort.getSharepointFolderUrl());
        if (!g1.gate().passed()) {
            auditEventService.record("GATE_FAILED", cohortId, actorUserId,
                Map.of("gate", 1, "errors", g1.gate().errors()));
            return new StandupResultDto(
                GateStateDto.failed(g1.gate().errors()),
                GateStateDto.pending(),
                GateStateDto.pending(),
                null
            );
        }

        cohort.setSharepointDriveId(g1.driveItem().driveId());
        cohort.setSharepointItemId(g1.driveItem().itemId());
        cohortRepository.save(cohort);

        String driveId = g1.driveItem().driveId();
        String parentItemId = g1.driveItem().itemId();

        Gate2Result g2 = gate2.validate(driveId, parentItemId);
        if (!g2.gate().passed()) {
            auditEventService.record("GATE_FAILED", cohortId, actorUserId,
                Map.of("gate", 2, "errors", g2.gate().errors()));
            return new StandupResultDto(
                GateStateDto.passed(),
                GateStateDto.failed(g2.gate().errors()),
                GateStateDto.pending(),
                null
            );
        }

        Gate3Result g3 = gate3.validate(driveId, g2.referenceFolderItemId());
        if (!g3.gate().passed()) {
            auditEventService.record("GATE_FAILED", cohortId, actorUserId,
                Map.of("gate", 3, "errors", g3.gate().errors()));
            return new StandupResultDto(
                GateStateDto.passed(),
                GateStateDto.passed(),
                GateStateDto.failed(g3.gate().errors()),
                null
            );
        }

        ValidatedReferenceBundle bundle = g3.bundle();
        String bundleJson;
        try {
            bundleJson = objectMapper.writeValueAsString(bundle);
        } catch (JsonProcessingException ex) {
            throw new UnprocessableEntityException("Failed to serialize validated reference bundle.");
        }

        OffsetDateTime now = OffsetDateTime.now();
        CohortStandupPending pending = CohortStandupPending.builder()
            .cohortId(cohortId)
            .bundleJson(bundleJson)
            .passedAt(now)
            .expiresAt(now.plusSeconds(pendingBundleTtlSeconds))
            .build();
        pendingRepository.save(pending);

        ReferenceSummary summary = new ReferenceSummary(
            bundle.specializations().size(),
            bundle.modules().size(),
            bundle.labs().size(),
            bundle.learners().size(),
            bundle.instructors().size()
        );

        auditEventService.record("GATE_PASSED", cohortId, actorUserId,
            Map.of("gate", 3, "summary", Map.of(
                "specsCount", summary.specializationCount(),
                "modulesCount", summary.moduleCount(),
                "labsCount", summary.labCount(),
                "learnersCount", summary.learnerCount(),
                "instructorsCount", summary.instructorContactCount()
            )));

        return new StandupResultDto(
            GateStateDto.passed(),
            GateStateDto.passed(),
            GateStateDto.passed(),
            summary
        );
    }
}
