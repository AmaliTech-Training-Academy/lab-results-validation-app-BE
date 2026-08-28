package com.amalitech.labresultsvalidator.domain.standup.service;

import com.amalitech.labresultsvalidator.common.exceptions.ResourceNotFoundException;
import com.amalitech.labresultsvalidator.domain.auditlog.service.AuditEventService;

import com.amalitech.labresultsvalidator.domain.cohort.entity.Cohort;
import com.amalitech.labresultsvalidator.domain.standup.entity.CohortGate4JobStatus;
import com.amalitech.labresultsvalidator.domain.cohort.entity.CohortLifecycleState;
import com.amalitech.labresultsvalidator.domain.standup.gate.Gate4Result;
import com.amalitech.labresultsvalidator.domain.standup.gate.Gate4ScoreSheetValidator;
import com.amalitech.labresultsvalidator.domain.standup.repository.CohortGate4JobRepository;
import com.amalitech.labresultsvalidator.domain.cohort.repository.CohortRepository;
import com.amalitech.labresultsvalidator.domain.notification.service.NotificationAlertService;
import com.amalitech.labresultsvalidator.infrastructure.graph.DriveItemInfo;
import com.amalitech.labresultsvalidator.infrastructure.graph.GraphDriveService;
import com.amalitech.labresultsvalidator.infrastructure.graph.SharePointProperties;
import com.amalitech.labresultsvalidator.infrastructure.graph.exception.GraphAccessException;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class Gate4JobRunner {

    private static final Logger LOG = LoggerFactory.getLogger(Gate4JobRunner.class);

    private final Gate4ScoreSheetValidator gate4Validator;
    private final Gate4EventService gate4EventService;
    private final StandupSseRegistry sseRegistry;
    private final CohortGate4JobRepository gate4JobRepository;
    private final CohortRepository cohortRepository;
    private final AuditEventService auditEventService;
    private final GraphDriveService graphDriveService;
    private final SharePointProperties sharePointProperties;
    private final NotificationAlertService notificationAlertService;

    @Async("standupTaskExecutor")
    public void run(UUID cohortId, UUID jobId, UUID actorId) {
        LOG.info("[gate4] job={} cohort={} STARTED", jobId, cohortId);
        CohortGate4JobStatus finalStatus = CohortGate4JobStatus.FAILED;

        try {
            Cohort cohort = cohortRepository.findById(cohortId)
                .orElseThrow(() -> new ResourceNotFoundException("Cohort " + cohortId + " not found"));

            String driveId = cohort.getSharepointDriveId();
            String parentItemId = cohort.getSharepointItemId();

            List<DriveItemInfo> children;
            try {
                children = graphDriveService.listChildren(driveId, parentItemId);
            } catch (GraphAccessException ex) {
                throw new RuntimeException("Cannot list cohort folder: " + ex.getMessage(), ex);
            }

            String scoresFolderName = sharePointProperties.scoresFolder();
            String scoresFolderItemId = children.stream()
                .filter(c -> c.isFolder() && scoresFolderName.equalsIgnoreCase(c.name()))
                .map(DriveItemInfo::itemId)
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException(
                    "Scores folder '" + scoresFolderName + "' not found."));

            Gate4Result result = gate4Validator.validate(
                driveId, scoresFolderItemId, cohortId, jobId, gate4EventService);
            boolean passed = result.gate().passed();
            finalStatus = passed ? CohortGate4JobStatus.COMPLETED : CohortGate4JobStatus.FAILED;

            if (passed) {
                cohort.setLifecycleState(CohortLifecycleState.STOOD_UP);
                cohort.setUpdatedBy(actorId);
                cohortRepository.save(cohort);
                auditEventService.record("STOOD_UP", cohortId, actorId,
                    Map.of("cohortName", cohort.getName()));
                LOG.info("[gate4] job={} cohort={} COMPLETED — all score sheets valid", jobId, cohortId);
                // C5 AC2 — in-app confirmation, no email.
                stageAlert(() -> notificationAlertService.confirmStoodUp(cohortId),
                    cohortId, jobId);
            } else {
                LOG.warn("[gate4] job={} cohort={} FAILED — score sheet errors found", jobId, cohortId);
                // Matches gates 1-3's convention (StandupPipelineService) so GATE_FAILED rows are
                // distinguished by the "gate" payload key rather than a separate event type.
                auditEventService.record("GATE_FAILED", cohortId, actorId,
                    Map.of("gate", 4, "errors", result.gate().errors()));
                // C5 AC1 — this branch previously reached nobody outside the audit log.
                stageAlert(() -> notificationAlertService.alertStandupFailure(cohortId,
                    "Gate 4 (score sheets)", result.gate().errors()), cohortId, jobId);
            }
        } catch (Exception ex) {
            LOG.error("[gate4] job={} cohort={} FAILED unexpectedly: {}", jobId, cohortId, ex.getMessage(), ex);
            auditEventService.record("GATE_FAILED", cohortId, actorId,
                Map.of("gate", 4, "error", String.valueOf(ex.getMessage())));
        }

        gate4EventService.emit(jobId, "gate4.done", Map.of("status", finalStatus.name()));
        sseRegistry.complete(jobId);

        CohortGate4JobStatus status = finalStatus;
        gate4JobRepository.findById(jobId).ifPresent(job -> {
            job.setStatus(status);
            job.setCompletedAt(OffsetDateTime.now());
            gate4JobRepository.save(job);
        });
    }

    /** A notification must never be able to change the gate's outcome. */
    private void stageAlert(Runnable staging, UUID cohortId, UUID jobId) {
        try {
            staging.run();
        } catch (RuntimeException ex) {
            LOG.error("[gate4] job={} cohort={} could not stage notification: {}",
                jobId, cohortId, ex.getMessage(), ex);
        }
    }
}
