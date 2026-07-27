package com.amalitech.labresultsvalidator.domain.cohort.service;

import com.amalitech.labresultsvalidator.domain.cohort.dto.StandupResultDto;
import com.amalitech.labresultsvalidator.domain.cohort.entity.CohortStandUpJobStatus;
import com.amalitech.labresultsvalidator.domain.cohort.gate.ReferenceSummary;
import com.amalitech.labresultsvalidator.domain.cohort.repository.CohortStandUpJobRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class StandupJobRunner {

    private static final Logger LOG = LoggerFactory.getLogger(StandupJobRunner.class);

    private final StandupPipelineService standupPipelineService;
    private final CohortStandUpJobRepository standUpJobRepository;
    private final StandupEventService standupEventService;
    private final StandupSseRegistry sseRegistry;

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
            }
        } catch (Exception ex) {
            LOG.error("[standup] job={} cohort={} FAILED unexpectedly: {}", jobId, cohortId, ex.getMessage(), ex);
            finalStatus = CohortStandUpJobStatus.FAILED;
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
            donePayload.put("quizReferencePresent", s.quizReferencePresent());
        }
        standupEventService.emit(jobId, "pipeline.done", donePayload);
        sseRegistry.complete(jobId);

        standUpJobRepository.findById(jobId).ifPresent(job -> {
            job.setStatus(pipelineStatus);
            job.setCompletedAt(OffsetDateTime.now());
            standUpJobRepository.save(job);
        });
    }
}