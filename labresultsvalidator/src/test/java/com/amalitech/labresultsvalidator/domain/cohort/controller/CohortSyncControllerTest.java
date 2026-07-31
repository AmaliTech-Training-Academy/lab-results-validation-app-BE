package com.amalitech.labresultsvalidator.domain.cohort.controller;

import com.amalitech.labresultsvalidator.common.exceptions.GlobalExceptionHandler;
import com.amalitech.labresultsvalidator.common.exceptions.ResourceNotFoundException;
import com.amalitech.labresultsvalidator.domain.cohort.dto.FileIngestionSummary;
import com.amalitech.labresultsvalidator.domain.cohort.dto.GradingSyncOverviewResponse;
import com.amalitech.labresultsvalidator.domain.cohort.dto.SyncRunResponse;
import com.amalitech.labresultsvalidator.domain.cohort.entity.CohortSyncJobStatus;
import com.amalitech.labresultsvalidator.domain.cohort.entity.IngestionRun;
import com.amalitech.labresultsvalidator.domain.cohort.service.CohortSyncService;
import com.amalitech.labresultsvalidator.domain.cohort.service.SseGateEventStreamer;
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
import java.util.List;
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
    private SyncEventService syncEventService;

    @Mock
    private SseGateEventStreamer sseStreamer;

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
        SyncRunResponse response = new SyncRunResponse(
            jobId, cohortId, CohortSyncJobStatus.COMPLETED, OffsetDateTime.now(), OffsetDateTime.now(), null);
        when(cohortSyncService.getRun(cohortId, jobId)).thenReturn(response);

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
        when(cohortSyncService.getRun(cohortId, jobId))
            .thenThrow(new ResourceNotFoundException("No sync job found with ID " + jobId + " for cohort " + cohortId));

        mockMvc.perform(get(BASE_URL + "/" + cohortId + "/sync/runs/" + jobId))
            .andExpect(status().isNotFound());
    }

    @Test
    void getGradingSyncOverview_existingJob_returnsAggregatedCountsAndPerFileBreakdown() throws Exception {
        UUID cohortId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();

        FileIngestionSummary file1 = FileIngestionSummary.from(
            IngestionRun.builder()
                .cohortId(cohortId).syncJobId(jobId).workbookFilename("Instructor1.xlsx")
                .status("completed").rowsRead(3).committedNew(2).updatedCount(1)
                .skippedInvalid(0).skippedUnchanged(0).conflictsCount(0).build());
        String errorReportJson = "[{\"file\":\"Instructor2.xlsx\",\"location\":\"sheet BEM01 row 3\","
            + "\"rule\":\"R1-UNKNOWN-NSP\",\"message\":\"NSP 'Not A Learner' does not match any learner.\"}]";
        FileIngestionSummary file2 = FileIngestionSummary.from(
            IngestionRun.builder()
                .cohortId(cohortId).syncJobId(jobId).workbookFilename("Instructor2.xlsx")
                .status("partial").rowsRead(2).committedNew(0).updatedCount(0)
                .skippedInvalid(1).skippedUnchanged(0).conflictsCount(1)
                .errorReportJson(errorReportJson).build());

        GradingSyncOverviewResponse response = new GradingSyncOverviewResponse(
            jobId, cohortId, CohortSyncJobStatus.COMPLETED, OffsetDateTime.now(), OffsetDateTime.now(),
            2, 5, 2, 1, 1, 0, 1, 0, List.of(file1, file2));
        when(cohortSyncService.getGradingSyncOverview(cohortId, jobId)).thenReturn(response);

        mockMvc.perform(get(BASE_URL + "/" + cohortId + "/sync/runs/" + jobId + "/overview"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.jobId").value(jobId.toString()))
            .andExpect(jsonPath("$.data.cohortId").value(cohortId.toString()))
            .andExpect(jsonPath("$.data.filesProcessed").value(2))
            .andExpect(jsonPath("$.data.rowsRead").value(5))
            .andExpect(jsonPath("$.data.committedNew").value(2))
            .andExpect(jsonPath("$.data.updatedCount").value(1))
            .andExpect(jsonPath("$.data.skippedInvalid").value(1))
            .andExpect(jsonPath("$.data.conflictsCount").value(1))
            .andExpect(jsonPath("$.data.files.length()").value(2))
            .andExpect(jsonPath("$.data.files[0].workbookFilename").value("Instructor1.xlsx"))
            .andExpect(jsonPath("$.data.files[0].issues.length()").value(0))
            .andExpect(jsonPath("$.data.files[1].issues[0].rule").value("R1-UNKNOWN-NSP"))
            .andExpect(jsonPath("$.data.files[1].issues[0].message")
                .value("NSP 'Not A Learner' does not match any learner."))
            .andExpect(jsonPath("$.data.files[1].rejectionReasons[0].rule").value("R1-UNKNOWN-NSP"))
            .andExpect(jsonPath("$.data.files[1].rejectionReasons[0].count").value(1));
    }

    @Test
    void getGradingSyncOverview_unknownOrMismatchedCohort_returns404() throws Exception {
        UUID cohortId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        when(cohortSyncService.getGradingSyncOverview(cohortId, jobId))
            .thenThrow(new ResourceNotFoundException("No sync job found with ID " + jobId + " for cohort " + cohortId));

        mockMvc.perform(get(BASE_URL + "/" + cohortId + "/sync/runs/" + jobId + "/overview"))
            .andExpect(status().isNotFound());
    }
}
