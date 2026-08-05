package com.amalitech.labresultsvalidator.domain.standup.service;

import com.amalitech.labresultsvalidator.domain.auditlog.service.AuditEventService;

import com.amalitech.labresultsvalidator.common.exceptions.ResourceNotFoundException;
import com.amalitech.labresultsvalidator.common.exceptions.UnprocessableEntityException;
import com.amalitech.labresultsvalidator.domain.standup.dto.GateStateDto;
import com.amalitech.labresultsvalidator.domain.standup.dto.StandupResultDto;
import com.amalitech.labresultsvalidator.domain.cohort.entity.Cohort;
import com.amalitech.labresultsvalidator.domain.cohort.entity.CohortLifecycleState;
import com.amalitech.labresultsvalidator.domain.standup.entity.CohortStandupPending;
import com.amalitech.labresultsvalidator.domain.standup.gate.Gate1LinkValidator;
import com.amalitech.labresultsvalidator.domain.standup.gate.Gate1Result;
import com.amalitech.labresultsvalidator.domain.standup.gate.Gate2FolderValidator;
import com.amalitech.labresultsvalidator.domain.standup.gate.Gate2Result;
import com.amalitech.labresultsvalidator.domain.standup.gate.Gate3ReferenceValidator;
import com.amalitech.labresultsvalidator.domain.standup.gate.Gate3Result;
import com.amalitech.labresultsvalidator.domain.standup.gate.ReferenceSummary;
import com.amalitech.labresultsvalidator.domain.standup.gate.ValidatedReferenceBundle;
import com.amalitech.labresultsvalidator.domain.cohort.repository.CohortRepository;
import com.amalitech.labresultsvalidator.domain.standup.repository.CohortStandupPendingRepository;
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

    private static final org.slf4j.Logger LOG =
        org.slf4j.LoggerFactory.getLogger(StandupPipelineService.class);

    private final Gate1LinkValidator gate1;
    private final Gate2FolderValidator gate2;
    private final Gate3ReferenceValidator gate3;
    private final CohortRepository cohortRepository;
    private final CohortStandupPendingRepository pendingRepository;
    private final AuditEventService auditEventService;
    private final StandupEventService standupEventService;
    private final ObjectMapper objectMapper;
    private final long pendingBundleTtlSeconds;

    public StandupPipelineService(
        Gate1LinkValidator gate1,
        Gate2FolderValidator gate2,
        Gate3ReferenceValidator gate3,
        CohortRepository cohortRepository,
        CohortStandupPendingRepository pendingRepository,
        AuditEventService auditEventService,
        StandupEventService standupEventService,
        ObjectMapper objectMapper,
        @Value("${labgate.standup.pending-bundle-ttl-seconds}") long pendingBundleTtlSeconds
    ) {
        this.gate1 = gate1;
        this.gate2 = gate2;
        this.gate3 = gate3;
        this.cohortRepository = cohortRepository;
        this.pendingRepository = pendingRepository;
        this.auditEventService = auditEventService;
        this.standupEventService = standupEventService;
        this.objectMapper = objectMapper;
        this.pendingBundleTtlSeconds = pendingBundleTtlSeconds;
    }

    @Transactional
    public StandupResultDto runGates123(UUID cohortId, UUID jobId, UUID actorUserId) {
        Cohort cohort = cohortRepository.findByIdAndIsActiveTrue(cohortId)
            .orElseThrow(() -> new ResourceNotFoundException("Cohort not found with id: " + cohortId));

        if (cohort.getLifecycleState() != CohortLifecycleState.DRAFT) {
            throw new UnprocessableEntityException("Cohort must be in DRAFT state to run stand-up.");
        }

        if (cohort.getSharepointFolderUrl() == null || cohort.getSharepointFolderUrl().isBlank()) {
            throw new UnprocessableEntityException("No SharePoint link set on this cohort.");
        }

        LOG.info("[standup] cohort={} Gate 1 — validating SharePoint link", cohortId);
        Gate1Result g1 = gate1.validate(cohort.getSharepointFolderUrl());
        if (!g1.gate().passed()) {
            LOG.warn("[standup] cohort={} Gate 1 FAILED: {}", cohortId, g1.gate().errors());
            auditEventService.record("GATE_FAILED", cohortId, actorUserId,
                Map.of("gate", 1, "errors", g1.gate().errors()));
            standupEventService.emit(jobId, "gate.failed",
                Map.of("gate", 1, "errors", g1.gate().errors()));
            return new StandupResultDto(
                GateStateDto.failed(g1.gate().errors()),
                GateStateDto.pending(),
                GateStateDto.pending(),
                null
            );
        }
        LOG.info("[standup] cohort={} Gate 1 PASSED — driveId={} itemId={}",
            cohortId, g1.driveItem().driveId(), g1.driveItem().itemId());
        standupEventService.emit(jobId, "gate.passed",
            Map.of("gate", 1, "driveId", g1.driveItem().driveId(), "itemId", g1.driveItem().itemId()));

        cohort.setSharepointDriveId(g1.driveItem().driveId());
        cohort.setSharepointItemId(g1.driveItem().itemId());
        cohortRepository.save(cohort);

        String driveId = g1.driveItem().driveId();
        String parentItemId = g1.driveItem().itemId();

        LOG.info("[standup] cohort={} Gate 2 — checking folder structure", cohortId);
        Gate2Result g2 = gate2.validate(driveId, parentItemId);
        if (!g2.gate().passed()) {
            LOG.warn("[standup] cohort={} Gate 2 FAILED: {}", cohortId, g2.gate().errors());
            auditEventService.record("GATE_FAILED", cohortId, actorUserId,
                Map.of("gate", 2, "errors", g2.gate().errors()));
            standupEventService.emit(jobId, "gate.failed",
                Map.of("gate", 2, "errors", g2.gate().errors()));
            return new StandupResultDto(
                GateStateDto.passed(),
                GateStateDto.failed(g2.gate().errors()),
                GateStateDto.pending(),
                null
            );
        }
        LOG.info("[standup] cohort={} Gate 2 PASSED — reference folder itemId={}",
            cohortId, g2.referenceFolderItemId());
        standupEventService.emit(jobId, "gate.passed",
            Map.of("gate", 2, "referenceFolderItemId", g2.referenceFolderItemId()));

        LOG.info("[standup] cohort={} Gate 3 — validating reference files", cohortId);
        Gate3Result g3 = gate3.validate(driveId, g2.referenceFolderItemId());
        if (!g3.gate().passed()) {
            LOG.warn("[standup] cohort={} Gate 3 FAILED: {}", cohortId, g3.gate().errors());
            auditEventService.record("GATE_FAILED", cohortId, actorUserId,
                Map.of("gate", 3, "errors", g3.gate().errors()));
            standupEventService.emit(jobId, "gate.failed",
                Map.of("gate", 3, "errors", g3.gate().errors()));
            return new StandupResultDto(
                GateStateDto.passed(),
                GateStateDto.passed(),
                GateStateDto.failed(g3.gate().errors()),
                null
            );
        }
        LOG.info("[standup] cohort={} Gate 3 PASSED", cohortId);

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

        LOG.info("[standup] cohort={} Gates 1-3 all PASSED — specs={} modules={} labs={} learners={} instructors={}",
            cohortId, summary.specializationCount(), summary.moduleCount(),
            summary.labCount(), summary.learnerCount(), summary.instructorCount());

        standupEventService.emit(jobId, "gate.passed", Map.of(
            "gate", 3,
            "specs", summary.specializationCount(),
            "modules", summary.moduleCount(),
            "labs", summary.labCount(),
            "learners", summary.learnerCount(),
            "instructorCount", summary.instructorCount()
        ));

        auditEventService.record("GATE_PASSED", cohortId, actorUserId,
            Map.of("gate", 3, "summary", Map.of(
                "specsCount", summary.specializationCount(),
                "modulesCount", summary.moduleCount(),
                "labsCount", summary.labCount(),
                "learnersCount", summary.learnerCount(),
                "instructorCount", summary.instructorCount()
            )));

        return new StandupResultDto(
            GateStateDto.passed(),
            GateStateDto.passed(),
            GateStateDto.passed(),
            summary
        );
    }
}
