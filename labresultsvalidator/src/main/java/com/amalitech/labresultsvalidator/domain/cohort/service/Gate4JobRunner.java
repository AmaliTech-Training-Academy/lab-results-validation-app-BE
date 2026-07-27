package com.amalitech.labresultsvalidator.domain.cohort.service;

import com.amalitech.labresultsvalidator.domain.cohort.entity.Cohort;
import com.amalitech.labresultsvalidator.domain.cohort.entity.CohortGate4JobStatus;
import com.amalitech.labresultsvalidator.domain.cohort.entity.CohortLifecycleState;
import com.amalitech.labresultsvalidator.domain.cohort.gate.Gate4Result;
import com.amalitech.labresultsvalidator.domain.cohort.gate.Gate4ScoreSheetValidator;
import com.amalitech.labresultsvalidator.domain.cohort.repository.CohortGate4JobRepository;
import com.amalitech.labresultsvalidator.domain.cohort.repository.CohortRepository;
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

    @Async("standupTaskExecutor")
    public void run(UUID cohortId, UUID jobId, UUID actorId) {
        LOG.info("[gate4] job={} cohort={} STARTED", jobId, cohortId);
        CohortGate4JobStatus finalStatus = CohortGate4JobStatus.FAILED;

        try {
            Cohort cohort = cohortRepository.findById(cohortId)
                .orElseThrow(() -> new RuntimeException("Cohort " + cohortId + " not found"));

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
                .orElseThrow(() -> new RuntimeException(
                    "Scores folder '" + scoresFolderName + "' not found."));

            Gate4Result result = gate4Validator.validate(driveId, scoresFolderItemId, cohortId, jobId, gate4EventService);
            boolean passed = result.gate().passed();
            finalStatus = passed ? CohortGate4JobStatus.COMPLETED : CohortGate4JobStatus.FAILED;

            if (passed) {
                cohort.setLifecycleState(CohortLifecycleState.STOOD_UP);
                cohort.setUpdatedBy(actorId);
                cohortRepository.save(cohort);
                auditEventService.record("STOOD_UP", cohortId, actorId,
                    Map.of("cohortName", cohort.getName()));
                LOG.info("[gate4] job={} cohort={} COMPLETED — all score sheets valid", jobId, cohortId);
            } else {
                LOG.warn("[gate4] job={} cohort={} FAILED — score sheet errors found", jobId, cohortId);
            }
        } catch (Exception ex) {
            LOG.error("[gate4] job={} cohort={} FAILED unexpectedly: {}", jobId, cohortId, ex.getMessage(), ex);
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
}
