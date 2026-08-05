package com.amalitech.labresultsvalidator.domain.standup.service;

import com.amalitech.labresultsvalidator.domain.auditlog.service.AuditEventService;

import com.amalitech.labresultsvalidator.domain.cohort.entity.Cohort;
import com.amalitech.labresultsvalidator.domain.standup.entity.CohortGate4Job;
import com.amalitech.labresultsvalidator.domain.standup.entity.CohortGate4JobStatus;
import com.amalitech.labresultsvalidator.domain.cohort.entity.CohortLifecycleState;
import com.amalitech.labresultsvalidator.domain.standup.gate.Gate4Result;
import com.amalitech.labresultsvalidator.domain.standup.gate.Gate4ScoreSheetValidator;
import com.amalitech.labresultsvalidator.domain.standup.gate.GateError;
import com.amalitech.labresultsvalidator.domain.standup.gate.GateResult;
import com.amalitech.labresultsvalidator.domain.standup.repository.CohortGate4JobRepository;
import com.amalitech.labresultsvalidator.domain.cohort.repository.CohortRepository;
import com.amalitech.labresultsvalidator.infrastructure.graph.DriveItemInfo;
import com.amalitech.labresultsvalidator.infrastructure.graph.GraphDriveService;
import com.amalitech.labresultsvalidator.infrastructure.graph.SharePointProperties;
import com.amalitech.labresultsvalidator.infrastructure.graph.exception.GraphAccessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class Gate4JobRunnerTest {

    private static final String DRIVE_ID = "drive-1";
    private static final String ROOT_ITEM_ID = "root-item";
    private static final String SCORES_FOLDER_NAME = "Lab Scores";

    @Mock
    private Gate4ScoreSheetValidator gate4Validator;
    @Mock
    private Gate4EventService gate4EventService;
    @Mock
    private StandupSseRegistry sseRegistry;
    @Mock
    private CohortGate4JobRepository gate4JobRepository;
    @Mock
    private CohortRepository cohortRepository;
    @Mock
    private AuditEventService auditEventService;
    @Mock
    private GraphDriveService graphDriveService;
    @Mock
    private SharePointProperties sharePointProperties;

    private Gate4JobRunner runner;

    private UUID cohortId;
    private UUID jobId;
    private UUID actorId;
    private Cohort cohort;
    private CohortGate4Job jobEntity;

    @BeforeEach
    void setUp() {
        runner = new Gate4JobRunner(gate4Validator, gate4EventService, sseRegistry, gate4JobRepository,
            cohortRepository, auditEventService, graphDriveService, sharePointProperties);

        cohortId = UUID.randomUUID();
        jobId = UUID.randomUUID();
        actorId = UUID.randomUUID();

        cohort = Cohort.builder()
            .id(cohortId)
            .name("Test Cohort")
            .lifecycleState(CohortLifecycleState.REFERENCE_ACCEPTED)
            .sharepointDriveId(DRIVE_ID)
            .sharepointItemId(ROOT_ITEM_ID)
            .build();

        jobEntity = CohortGate4Job.builder().id(jobId).cohort(cohort).status(CohortGate4JobStatus.RUNNING).build();

        when(cohortRepository.findById(cohortId)).thenReturn(Optional.of(cohort));
        when(gate4JobRepository.findById(jobId)).thenReturn(Optional.of(jobEntity));
        lenient().when(sharePointProperties.scoresFolder()).thenReturn(SCORES_FOLDER_NAME);
        lenient().when(graphDriveService.listChildren(DRIVE_ID, ROOT_ITEM_ID))
            .thenReturn(List.of(new DriveItemInfo(DRIVE_ID, "scores-1", SCORES_FOLDER_NAME, true, "site-1")));
    }

    @Test
    void run_gate4Passes_standsUpTheCohortAndRecordsStoodUp() {
        when(gate4Validator.validate(eq(DRIVE_ID), eq("scores-1"), eq(cohortId), eq(jobId), eq(gate4EventService)))
            .thenReturn(new Gate4Result(GateResult.pass()));

        runner.run(cohortId, jobId, actorId);

        assertThat(cohort.getLifecycleState()).isEqualTo(CohortLifecycleState.STOOD_UP);
        verify(auditEventService).record(eq("STOOD_UP"), eq(cohortId), eq(actorId), any());
        verify(auditEventService, never()).record(eq("GATE_FAILED"), any(), any(), any());
        assertThat(jobEntity.getStatus()).isEqualTo(CohortGate4JobStatus.COMPLETED);
    }

    @Test
    void run_gate4Fails_recordsGateFailedWithGateFourAndErrorsButDoesNotStandUp() {
        GateError error = new GateError("BEM01.xlsx", "row 4", "G4-INVALID-SCORE", "not numeric");
        when(gate4Validator.validate(eq(DRIVE_ID), eq("scores-1"), eq(cohortId), eq(jobId), eq(gate4EventService)))
            .thenReturn(new Gate4Result(GateResult.fail(List.of(error))));

        runner.run(cohortId, jobId, actorId);

        assertThat(cohort.getLifecycleState()).isEqualTo(CohortLifecycleState.REFERENCE_ACCEPTED);
        verify(auditEventService, never()).record(eq("STOOD_UP"), any(), any(), any());

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(auditEventService).record(eq("GATE_FAILED"), eq(cohortId), eq(actorId), captor.capture());
        assertThat(captor.getValue()).containsEntry("gate", 4);
        assertThat(captor.getValue()).containsEntry("errors", List.of(error));
        assertThat(jobEntity.getStatus()).isEqualTo(CohortGate4JobStatus.FAILED);
    }

    @Test
    void run_scoresFolderAccessThrows_recordsGateFailedWithGateFourAndTheExceptionMessage() {
        when(graphDriveService.listChildren(DRIVE_ID, ROOT_ITEM_ID))
            .thenThrow(new GraphAccessException("throttled after retries"));

        runner.run(cohortId, jobId, actorId);

        assertThat(cohort.getLifecycleState()).isEqualTo(CohortLifecycleState.REFERENCE_ACCEPTED);

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(auditEventService).record(eq("GATE_FAILED"), eq(cohortId), eq(actorId), captor.capture());
        assertThat(captor.getValue()).containsEntry("gate", 4);
        assertThat(captor.getValue().get("error")).asString().contains("throttled after retries");
        assertThat(jobEntity.getStatus()).isEqualTo(CohortGate4JobStatus.FAILED);
    }

    @Test
    void run_scoresFolderMissing_recordsGateFailedRatherThanStayingSilent() {
        when(graphDriveService.listChildren(DRIVE_ID, ROOT_ITEM_ID))
            .thenReturn(List.of(new DriveItemInfo(DRIVE_ID, "other", "Reference Data", true, "site-1")));

        runner.run(cohortId, jobId, actorId);

        verify(auditEventService).record(eq("GATE_FAILED"), eq(cohortId), eq(actorId), any());
        assertThat(jobEntity.getStatus()).isEqualTo(CohortGate4JobStatus.FAILED);
    }
}
