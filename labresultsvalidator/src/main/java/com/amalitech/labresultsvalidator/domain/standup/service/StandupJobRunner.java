package com.amalitech.labresultsvalidator.domain.standup.service;

import com.amalitech.labresultsvalidator.domain.standup.dto.StandupResultDto;
import com.amalitech.labresultsvalidator.domain.standup.entity.CohortStandUpJobStatus;
import com.amalitech.labresultsvalidator.domain.standup.gate.ReferenceSummary;
import com.amalitech.labresultsvalidator.domain.notification.service.NotificationAlertService;
import com.amalitech.labresultsvalidator.domain.standup.repository.CohortStandUpJobRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class StandupJobRunner {

    private static final Logger LOG = LoggerFactory.getLogger(StandupJobRunner.class);
    private static final String FAILED_STATE = "FAILED";

    private final StandupPipelineService standupPipelineService;
    private final CohortStandUpJobRepository standUpJobRepository;
    private final StandupEventService standupEventService;
    private final StandupSseRegistry sseRegistry;
    private final NotificationAlertService notificationAlertService;

    @Async("standupTaskExecutor")
    public void run(UUID cohortId, UUID jobId, UUID actorId) {
        LOG.info("[standup] job={} cohort={} STARTED", jobId, cohortId);
        CohortStandUpJobStatus finalStatus;
        StandupResultDto result = null;
        try {
            result = standupPipelineService.runGates123(cohortId, jobId, actorId);
            boolean passed = "PASSED".equals(result.gate3().state());
            finalStatus = passed ? CohortStandUpJobStatus.COMPLETED : CohortStandUpJobStatus.FAILED;
            if (passed) {
                LOG.info("[standup] job={} cohort={} COMPLETED — all gates passed", jobId, cohortId);
            } else {
                LOG.warn("[standup] job={} cohort={} FAILED — gate1={} gate2={} gate3={}",
                    jobId, cohortId,
                    result.gate1().state(), result.gate2().state(), result.gate3().state());
                // C5 AC1 — one alert for the run, not one per gate.
                alertFailure(cohortId, jobId, result);
            }
        } catch (Exception ex) {
            LOG.error("[standup] job={} cohort={} FAILED unexpectedly: {}", jobId, cohortId, ex.getMessage(), ex);
            finalStatus = CohortStandUpJobStatus.FAILED;
            // No gate results survive an unexpected throw, so the alert can only say the run broke.
            alertFailure(cohortId, jobId, null);
        }

        CohortStandUpJobStatus pipelineStatus = finalStatus;
        Map<String, Object> donePayload = new LinkedHashMap<>();
        donePayload.put("status", pipelineStatus.name());
        if (result != null && result.summary() != null) {
            ReferenceSummary s = result.summary();
            donePayload.put("specs", s.specializationCount());
            donePayload.put("modules", s.moduleCount());
            donePayload.put("labs", s.labCount());
            donePayload.put("learners", s.learnerCount());
            donePayload.put("instructorCount", s.instructorCount());
        }
        standupEventService.emit(jobId, "pipeline.done", donePayload);
        sseRegistry.complete(jobId);

        standUpJobRepository.findById(jobId).ifPresent(job -> {
            job.setStatus(pipelineStatus);
            job.setCompletedAt(OffsetDateTime.now());
            standUpJobRepository.save(job);
        });
    }

    /**
     * Alerts on the first gate that did not pass — that is the one an admin has to act on, and the
     * later gates never ran. Wrapped so a notification failure cannot alter the job's outcome.
     */
    private void alertFailure(UUID cohortId, UUID jobId, StandupResultDto result) {
        try {
            if (result == null) {
                notificationAlertService.alertStandupFailure(cohortId, "stand-up", List.of());
            } else if (FAILED_STATE.equals(result.gate1().state())) {
                notificationAlertService.alertStandupFailure(cohortId,
                    "Gate 1 (SharePoint link)", result.gate1().errors());
            } else if (FAILED_STATE.equals(result.gate2().state())) {
                notificationAlertService.alertStandupFailure(cohortId,
                    "Gate 2 (folder structure)", result.gate2().errors());
            } else {
                notificationAlertService.alertStandupFailure(cohortId,
                    "Gate 3 (reference data)", result.gate3().errors());
            }
        } catch (RuntimeException ex) {
            LOG.error("[standup] job={} cohort={} could not stage failure notification: {}",
                jobId, cohortId, ex.getMessage(), ex);
        }
    }
}