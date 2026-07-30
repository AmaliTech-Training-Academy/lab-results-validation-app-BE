package com.amalitech.labresultsvalidator.domain.cohort.controller;

import com.amalitech.labresultsvalidator.common.exceptions.GlobalExceptionHandler;
import com.amalitech.labresultsvalidator.domain.cohort.entity.Cohort;
import com.amalitech.labresultsvalidator.domain.cohort.entity.CohortSyncJob;
import com.amalitech.labresultsvalidator.domain.cohort.entity.CohortSyncJobStatus;
import com.amalitech.labresultsvalidator.domain.cohort.repository.CohortSyncJobRepository;
import com.amalitech.labresultsvalidator.domain.cohort.service.CohortSyncService;
import com.amalitech.labresultsvalidator.domain.cohort.service.StandupSseRegistry;
import com.amalitech.labresultsvalidator.domain.cohort.service.SyncEventService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class CohortSyncControllerTest {

    private MockMvc mockMvc;

    @Mock
    private CohortSyncService cohortSyncService;

    @Mock
    private CohortSyncJobRepository syncJobRepository;

    @Mock
    private SyncEventService syncEventService;

    @Mock
    private StandupSseRegistry sseRegistry;

    @InjectMocks
    private CohortSyncController cohortSyncController;

    private static final String BASE_URL = "/api/v1/cohorts";

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
            .standaloneSetup(cohortSyncController)
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
    }

    @Test
    void getSyncRun_existingJob_returns200() throws Exception {
        UUID cohortId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        CohortSyncJob job = CohortSyncJob.builder()
            .id(jobId)
            .cohort(Cohort.builder().id(cohortId).build())
            .status(CohortSyncJobStatus.COMPLETED)
            .startedAt(OffsetDateTime.now())
            .build();
        when(syncJobRepository.findByIdAndCohortId(jobId, cohortId)).thenReturn(Optional.of(job));

        mockMvc.perform(get(BASE_URL + "/" + cohortId + "/sync/runs/" + jobId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.id").value(jobId.toString()))
            .andExpect(jsonPath("$.data.cohortId").value(cohortId.toString()))
            .andExpect(jsonPath("$.data.status").value("COMPLETED"));
    }

    @Test
    void getSyncRun_unknownOrMismatchedCohort_returns404() throws Exception {
        UUID cohortId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        when(syncJobRepository.findByIdAndCohortId(jobId, cohortId)).thenReturn(Optional.empty());

        mockMvc.perform(get(BASE_URL + "/" + cohortId + "/sync/runs/" + jobId))
            .andExpect(status().isNotFound());
    }
}
