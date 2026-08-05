package com.amalitech.labresultsvalidator.domain.sync.service;

import com.amalitech.labresultsvalidator.domain.standup.service.StandupSseRegistry;
import com.amalitech.labresultsvalidator.domain.auditlog.service.AuditEventService;

import com.amalitech.labresultsvalidator.common.SystemUser;
import com.amalitech.labresultsvalidator.domain.cohort.entity.Cohort;
import com.amalitech.labresultsvalidator.domain.sync.entity.CohortSyncFile;
import com.amalitech.labresultsvalidator.domain.sync.entity.CohortSyncJob;
import com.amalitech.labresultsvalidator.domain.sync.entity.CohortSyncJobStatus;
import com.amalitech.labresultsvalidator.domain.grading.entity.IngestionRun;
import com.amalitech.labresultsvalidator.domain.sync.entity.SyncFileChangeState;
import com.amalitech.labresultsvalidator.domain.grading.ingestion.GradingIngestionService;
import com.amalitech.labresultsvalidator.domain.cohort.repository.CohortRepository;
import com.amalitech.labresultsvalidator.domain.sync.repository.CohortSyncFileRepository;
import com.amalitech.labresultsvalidator.domain.sync.repository.CohortSyncJobRepository;
import com.amalitech.labresultsvalidator.domain.grading.sync.FetchOutcome;
import com.amalitech.labresultsvalidator.domain.grading.sync.FetchedWorkbook;
import com.amalitech.labresultsvalidator.domain.grading.sync.WorkbookFetchService;
import com.amalitech.labresultsvalidator.domain.grading.sync.WorkbookParseException;
import com.amalitech.labresultsvalidator.domain.notification.service.NotificationStagingService;
import com.amalitech.labresultsvalidator.infrastructure.graph.DriveItemDetails;
import com.amalitech.labresultsvalidator.infrastructure.graph.DriveItemInfo;
import com.amalitech.labresultsvalidator.infrastructure.graph.GraphDriveService;
import com.amalitech.labresultsvalidator.infrastructure.graph.SharePointProperties;
import com.amalitech.labresultsvalidator.infrastructure.graph.exception.GraphAccessException;
import com.amalitech.labresultsvalidator.infrastructure.storage.S3StorageService;
import com.amalitech.labresultsvalidator.infrastructure.storage.exception.S3StorageException;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CohortSyncJobRunnerTest {

    private static final String DRIVE_ID = "drive-1";
    private static final String ROOT_ITEM_ID = "root-item";
    private static final String SCORES_FOLDER_NAME = "Lab Scores";

    @Mock
    private CohortRepository cohortRepository;
    @Mock
    private CohortSyncJobRepository syncJobRepository;
    @Mock
    private CohortSyncFileRepository syncFileRepository;
    @Mock
    private SyncEventService syncEventService;
    @Mock
    private StandupSseRegistry sseRegistry;
    @Mock
    private AuditEventService auditEventService;
    @Mock
    private GraphDriveService graphDriveService;
    @Mock
    private SharePointProperties sharePointProperties;
    @Mock
    private S3StorageService s3StorageService;
    @Mock
    private WorkbookFetchService workbookFetchService;
    @Mock
    private GradingIngestionService gradingIngestionService;
    @Mock
    private NotificationStagingService notificationStagingService;

    private CohortSyncJobRunner runner;

    private UUID cohortId;
    private UUID jobId;
    private UUID actorId;
    private Cohort cohort;
    private CohortSyncJob jobEntity;

    @BeforeEach
    void setUp() {
        runner = new CohortSyncJobRunner(
            cohortRepository, syncJobRepository, syncFileRepository, syncEventService,
            sseRegistry, auditEventService, graphDriveService, sharePointProperties,
            s3StorageService, workbookFetchService, gradingIngestionService, notificationStagingService
        );

        cohortId = UUID.randomUUID();
        jobId = UUID.randomUUID();
        actorId = UUID.randomUUID();

        cohort = Cohort.builder()
            .id(cohortId)
            .name("Test Cohort")
            .sharepointDriveId(DRIVE_ID)
            .sharepointItemId(ROOT_ITEM_ID)
            .build();

        jobEntity = CohortSyncJob.builder()
            .id(jobId)
            .cohort(cohort)
            .status(CohortSyncJobStatus.RUNNING)
            .startedAt(OffsetDateTime.now())
            .build();

        when(cohortRepository.findById(cohortId)).thenReturn(Optional.of(cohort));
        when(syncJobRepository.findById(jobId)).thenReturn(Optional.of(jobEntity));
    }

    private DriveItemInfo item(String itemId, String name, boolean isFolder) {
        return new DriveItemInfo(DRIVE_ID, itemId, name, isFolder, "site-1");
    }

    private DriveItemDetails details(String name, String parentFolder) {
        return new DriveItemDetails(name, parentFolder, "quickxor-" + name, "cTag-" + name,
            1024L, "https://sp/" + name);
    }

    private static Workbook emptyWorkbook() {
        Workbook wb = new XSSFWorkbook();
        wb.createSheet("BEM01");
        return wb;
    }

    private FetchOutcome changed(String fileName, String s3Key, byte[] content) {
        return new FetchOutcome(SyncFileChangeState.CHANGED, "sha-" + fileName,
            new FetchedWorkbook(fileName, "item-of-" + fileName, s3Key, content, emptyWorkbook()));
    }

    /** Points the folder walk at a single .xlsx sitting directly in the scores folder. */
    private void stubFolderWithOneFile(String itemId, String fileName) {
        when(sharePointProperties.scoresFolder()).thenReturn(SCORES_FOLDER_NAME);
        when(graphDriveService.listChildren(DRIVE_ID, ROOT_ITEM_ID))
            .thenReturn(List.of(item("scores-1", SCORES_FOLDER_NAME, true)));
        when(graphDriveService.listChildren(DRIVE_ID, "scores-1"))
            .thenReturn(List.of(item(itemId, fileName, false)));
        when(graphDriveService.getItem(DRIVE_ID, itemId))
            .thenReturn(details(fileName, SCORES_FOLDER_NAME));
    }

    @Test
    void archivesChangedFilesAndRecordsProvenanceOnEachRow() throws Exception {
        when(sharePointProperties.scoresFolder()).thenReturn(SCORES_FOLDER_NAME);
        when(syncJobRepository.getReferenceById(jobId)).thenReturn(jobEntity);

        when(graphDriveService.listChildren(DRIVE_ID, ROOT_ITEM_ID))
            .thenReturn(List.of(item("scores-1", SCORES_FOLDER_NAME, true)));
        when(graphDriveService.listChildren(DRIVE_ID, "scores-1"))
            .thenReturn(List.of(item("file-1", "Direct.xlsx", false), item("scenario-1", "Scenario 1", true)));
        when(graphDriveService.listChildren(DRIVE_ID, "scenario-1"))
            .thenReturn(List.of(item("file-2", "Results.xlsx", false)));

        when(graphDriveService.getItem(DRIVE_ID, "file-1")).thenReturn(details("Direct.xlsx", SCORES_FOLDER_NAME));
        when(graphDriveService.getItem(DRIVE_ID, "file-2")).thenReturn(details("Results.xlsx", "Scenario 1"));

        String directKey = "cohorts/" + cohortId + "/scores/Direct.xlsx";
        String scenarioKey = "cohorts/" + cohortId + "/scores/Scenario 1/Results.xlsx";
        byte[] directBytes = "direct-bytes".getBytes(StandardCharsets.UTF_8);
        byte[] scenarioBytes = "scenario-bytes".getBytes(StandardCharsets.UTF_8);

        when(workbookFetchService.fetchIfChanged(eq(DRIVE_ID), eq("file-1"), any(), eq(directKey)))
            .thenReturn(changed("Direct.xlsx", directKey, directBytes));
        when(workbookFetchService.fetchIfChanged(eq(DRIVE_ID), eq("file-2"), any(), eq(scenarioKey)))
            .thenReturn(changed("Results.xlsx", scenarioKey, scenarioBytes));

        when(s3StorageService.putObject(anyString(), any(byte[].class), anyString())).thenReturn("v1");

        runner.run(cohortId, jobId, actorId);

        verify(s3StorageService).putObject(eq(directKey), eq(directBytes), anyString());
        verify(s3StorageService).putObject(eq(scenarioKey), eq(scenarioBytes), anyString());

        ArgumentCaptor<CohortSyncFile> captor = ArgumentCaptor.forClass(CohortSyncFile.class);
        verify(syncFileRepository, times(2)).save(captor.capture());

        assertThat(captor.getAllValues()).anySatisfy(f -> {
            assertThat(f.getFileName()).isEqualTo("Direct.xlsx");
            assertThat(f.getScenarioFolder()).isNull();
            assertThat(f.getS3Key()).isEqualTo(directKey);
            assertThat(f.getS3VersionId()).isEqualTo("v1");
            assertThat(f.getChangeState()).isEqualTo(SyncFileChangeState.CHANGED);
            assertThat(f.getSharepointItemId()).isEqualTo("file-1");
            assertThat(f.getQuickXorHash()).isEqualTo("quickxor-Direct.xlsx");
            assertThat(f.getSharepointVersionId()).isEqualTo("cTag-Direct.xlsx");
            assertThat(f.getFileSha256()).isEqualTo("sha-Direct.xlsx");
        });
        assertThat(captor.getAllValues()).anySatisfy(f -> {
            assertThat(f.getFileName()).isEqualTo("Results.xlsx");
            assertThat(f.getScenarioFolder()).isEqualTo("Scenario 1");
            assertThat(f.getS3Key()).isEqualTo(scenarioKey);
        });

        assertThat(jobEntity.getStatus()).isEqualTo(CohortSyncJobStatus.COMPLETED);
        verifyAuditCounts(2, 0, 0);
    }

    @Test
    void recordsAnUnchangedFileWithoutUploadingOrParsing() throws Exception {
        stubFolderWithOneFile("file-1", "Steady.xlsx");
        when(syncJobRepository.getReferenceById(jobId)).thenReturn(jobEntity);

        when(workbookFetchService.fetchIfChanged(eq(DRIVE_ID), eq("file-1"), any(), anyString()))
            .thenReturn(FetchOutcome.unchanged("sha-steady"));

        runner.run(cohortId, jobId, actorId);

        // B3 AC2 — nothing uploaded, but the observation is still on the record.
        verify(s3StorageService, never()).putObject(anyString(), any(byte[].class), anyString());
        verify(syncEventService).emit(eq(jobId), eq("file.unchanged"), any());

        ArgumentCaptor<CohortSyncFile> captor = ArgumentCaptor.forClass(CohortSyncFile.class);
        verify(syncFileRepository).save(captor.capture());
        assertThat(captor.getValue().getChangeState()).isEqualTo(SyncFileChangeState.UNCHANGED);
        assertThat(captor.getValue().getS3VersionId()).isNull();
        assertThat(captor.getValue().getFileSha256()).isEqualTo("sha-steady");

        // D1 AC2 / D4 AC2 — the dedup also gets its own ingestion_runs row (status=skipped), so
        // it's auditable via the same API as changed/new files, not just cohort_sync_files.
        verify(gradingIngestionService).recordSkipped(eq(cohort), eq(jobId), eq("Steady.xlsx"),
            any(DriveItemDetails.class), eq("sha-steady"), eq(actorId), eq("MANUAL"));

        verifyAuditCounts(0, 1, 0);
    }

    @Test
    void recordsASkippedFileWithTheSystemActorWhenTheRunIsScheduled() throws Exception {
        stubFolderWithOneFile("file-1", "Steady.xlsx");
        when(syncJobRepository.getReferenceById(jobId)).thenReturn(jobEntity);

        when(workbookFetchService.fetchIfChanged(eq(DRIVE_ID), eq("file-1"), any(), anyString()))
            .thenReturn(FetchOutcome.unchanged("sha-steady"));

        runner.run(cohortId, jobId, null);

        verify(gradingIngestionService).recordSkipped(eq(cohort), eq(jobId), eq("Steady.xlsx"),
            any(DriveItemDetails.class), eq("sha-steady"), eq(SystemUser.ID), eq("SCHEDULED"));
    }

    @Test
    void recordsAFailedRowWhenPoiCannotOpenTheWorkbook() throws Exception {
        stubFolderWithOneFile("file-1", "Corrupt.xlsx");
        when(syncJobRepository.getReferenceById(jobId)).thenReturn(jobEntity);

        when(workbookFetchService.fetchIfChanged(eq(DRIVE_ID), eq("file-1"), any(), anyString()))
            .thenThrow(new WorkbookParseException("not a valid .xlsx", null));

        runner.run(cohortId, jobId, actorId);

        ArgumentCaptor<CohortSyncFile> captor = ArgumentCaptor.forClass(CohortSyncFile.class);
        verify(syncFileRepository).save(captor.capture());
        assertThat(captor.getValue().getChangeState()).isEqualTo(SyncFileChangeState.FAILED);

        // The baseline must not move, so nothing is uploaded and the next run retries.
        verify(s3StorageService, never()).putObject(anyString(), any(byte[].class), anyString());
        verify(syncEventService).emit(eq(jobId), eq("file.failed"), any());
        assertThat(jobEntity.getStatus()).isEqualTo(CohortSyncJobStatus.COMPLETED);
        verifyAuditCounts(0, 0, 1);
    }

    @Test
    void oneUnreadableWorkbookDoesNotStopItsSiblings() throws Exception {
        when(sharePointProperties.scoresFolder()).thenReturn(SCORES_FOLDER_NAME);
        when(syncJobRepository.getReferenceById(jobId)).thenReturn(jobEntity);

        when(graphDriveService.listChildren(DRIVE_ID, ROOT_ITEM_ID))
            .thenReturn(List.of(item("scores-1", SCORES_FOLDER_NAME, true)));
        when(graphDriveService.listChildren(DRIVE_ID, "scores-1"))
            .thenReturn(List.of(item("bad", "Broken.xlsx", false), item("good", "Fine.xlsx", false)));
        when(graphDriveService.getItem(DRIVE_ID, "bad")).thenReturn(details("Broken.xlsx", SCORES_FOLDER_NAME));
        when(graphDriveService.getItem(DRIVE_ID, "good")).thenReturn(details("Fine.xlsx", SCORES_FOLDER_NAME));

        String goodKey = "cohorts/" + cohortId + "/scores/Fine.xlsx";
        when(workbookFetchService.fetchIfChanged(eq(DRIVE_ID), eq("bad"), any(), anyString()))
            .thenThrow(new GraphAccessException("throttled after retries"));
        when(workbookFetchService.fetchIfChanged(eq(DRIVE_ID), eq("good"), any(), anyString()))
            .thenReturn(changed("Fine.xlsx", goodKey, "ok".getBytes(StandardCharsets.UTF_8)));
        when(s3StorageService.putObject(anyString(), any(byte[].class), anyString())).thenReturn("v1");

        runner.run(cohortId, jobId, actorId);

        // §4.5 — the healthy sibling still commits.
        verify(s3StorageService).putObject(eq(goodKey), any(byte[].class), anyString());
        verify(syncFileRepository, times(2)).save(any());
        verifyAuditCounts(1, 0, 1);
    }

    @Test
    void anUnreadableScenarioFolderIsSkippedRatherThanSinkingTheSweep() throws Exception {
        when(sharePointProperties.scoresFolder()).thenReturn(SCORES_FOLDER_NAME);
        when(syncJobRepository.getReferenceById(jobId)).thenReturn(jobEntity);

        when(graphDriveService.listChildren(DRIVE_ID, ROOT_ITEM_ID))
            .thenReturn(List.of(item("scores-1", SCORES_FOLDER_NAME, true)));
        when(graphDriveService.listChildren(DRIVE_ID, "scores-1"))
            .thenReturn(List.of(item("locked", "Locked Scenario", true), item("file-1", "Fine.xlsx", false)));
        when(graphDriveService.listChildren(DRIVE_ID, "locked"))
            .thenThrow(new GraphAccessException("permissions revoked"));
        when(graphDriveService.getItem(DRIVE_ID, "file-1")).thenReturn(details("Fine.xlsx", SCORES_FOLDER_NAME));

        String key = "cohorts/" + cohortId + "/scores/Fine.xlsx";
        when(workbookFetchService.fetchIfChanged(eq(DRIVE_ID), eq("file-1"), any(), anyString()))
            .thenReturn(changed("Fine.xlsx", key, "ok".getBytes(StandardCharsets.UTF_8)));
        when(s3StorageService.putObject(anyString(), any(byte[].class), anyString())).thenReturn("v1");

        runner.run(cohortId, jobId, actorId);

        verify(syncEventService).emit(eq(jobId), eq("folder.failed"), any());
        verify(s3StorageService).putObject(eq(key), any(byte[].class), anyString());
        assertThat(jobEntity.getStatus()).isEqualTo(CohortSyncJobStatus.COMPLETED);
    }

    @Test
    void anArchiveFailureLeavesTheBaselineUnmovedAndMarksTheFileFailed() throws Exception {
        stubFolderWithOneFile("file-1", "Direct.xlsx");
        when(syncJobRepository.getReferenceById(jobId)).thenReturn(jobEntity);

        String key = "cohorts/" + cohortId + "/scores/Direct.xlsx";
        when(workbookFetchService.fetchIfChanged(eq(DRIVE_ID), eq("file-1"), any(), anyString()))
            .thenReturn(changed("Direct.xlsx", key, "bytes".getBytes(StandardCharsets.UTF_8)));
        when(s3StorageService.putObject(anyString(), any(byte[].class), anyString()))
            .thenThrow(new S3StorageException("bucket unreachable"));

        runner.run(cohortId, jobId, actorId);

        ArgumentCaptor<CohortSyncFile> captor = ArgumentCaptor.forClass(CohortSyncFile.class);
        verify(syncFileRepository).save(captor.capture());
        assertThat(captor.getValue().getChangeState()).isEqualTo(SyncFileChangeState.FAILED);
        assertThat(captor.getValue().getS3VersionId()).isNull();

        verify(syncEventService).emit(eq(jobId), eq("file.archive_failed"), any());
        verify(syncEventService, never()).emit(eq(jobId), eq("file.archived"), any());
        assertThat(jobEntity.getStatus()).isEqualTo(CohortSyncJobStatus.COMPLETED);
    }

    @Test
    void callsGradingIngestionWithManualTriggerTypeWhenAnActorIsPresent() throws Exception {
        stubFolderWithOneFile("file-1", "Direct.xlsx");
        when(syncJobRepository.getReferenceById(jobId)).thenReturn(jobEntity);

        String key = "cohorts/" + cohortId + "/scores/Direct.xlsx";
        when(workbookFetchService.fetchIfChanged(eq(DRIVE_ID), eq("file-1"), any(), anyString()))
            .thenReturn(changed("Direct.xlsx", key, "bytes".getBytes(StandardCharsets.UTF_8)));
        when(s3StorageService.putObject(anyString(), any(byte[].class), anyString())).thenReturn("v1");

        runner.run(cohortId, jobId, actorId);

        verify(gradingIngestionService).process(eq(cohort), eq(jobId), eq("Direct.xlsx"), any(), any(),
            eq("sha-Direct.xlsx"), eq(actorId), eq("MANUAL"));
        // The seam must run before the archive write (a processing failure must leave the baseline).
        verify(s3StorageService).putObject(eq(key), any(byte[].class), anyString());
    }

    @Test
    void callsGradingIngestionWithScheduledTriggerTypeWhenNoActorIsPresent() throws Exception {
        stubFolderWithOneFile("file-1", "Direct.xlsx");
        when(syncJobRepository.getReferenceById(jobId)).thenReturn(jobEntity);

        String key = "cohorts/" + cohortId + "/scores/Direct.xlsx";
        when(workbookFetchService.fetchIfChanged(eq(DRIVE_ID), eq("file-1"), any(), anyString()))
            .thenReturn(changed("Direct.xlsx", key, "bytes".getBytes(StandardCharsets.UTF_8)));
        when(s3StorageService.putObject(anyString(), any(byte[].class), anyString())).thenReturn("v1");

        runner.run(cohortId, jobId, null);

        // D1 AC3 — a scheduled run has no human actor, so triggered_by falls back to the SYSTEM
        // pseudo-user rather than being left null.
        verify(gradingIngestionService).process(eq(cohort), eq(jobId), eq("Direct.xlsx"), any(), any(),
            eq("sha-Direct.xlsx"), eq(SystemUser.ID), eq("SCHEDULED"));
    }

    @Test
    void gradingIngestionFailureLeavesTheBaselineUnmovedAndMarksTheFileFailed() throws Exception {
        stubFolderWithOneFile("file-1", "Direct.xlsx");
        when(syncJobRepository.getReferenceById(jobId)).thenReturn(jobEntity);

        String key = "cohorts/" + cohortId + "/scores/Direct.xlsx";
        when(workbookFetchService.fetchIfChanged(eq(DRIVE_ID), eq("file-1"), any(), anyString()))
            .thenReturn(changed("Direct.xlsx", key, "bytes".getBytes(StandardCharsets.UTF_8)));
        when(gradingIngestionService.process(any(), any(), anyString(), any(), any(), anyString(), any(), anyString()))
            .thenThrow(new IllegalStateException("DB unreachable"));

        runner.run(cohortId, jobId, actorId);

        // The processing failure must leave the S3 baseline untouched, so the file retries next run.
        verify(s3StorageService, never()).putObject(anyString(), any(byte[].class), anyString());

        ArgumentCaptor<CohortSyncFile> captor = ArgumentCaptor.forClass(CohortSyncFile.class);
        verify(syncFileRepository).save(captor.capture());
        assertThat(captor.getValue().getChangeState()).isEqualTo(SyncFileChangeState.FAILED);
        assertThat(captor.getValue().getS3VersionId()).isNull();

        verify(syncEventService).emit(eq(jobId), eq("file.ingestion_failed"), any());
        assertThat(jobEntity.getStatus()).isEqualTo(CohortSyncJobStatus.COMPLETED);
        verifyAuditCounts(0, 0, 1);
    }

    @Test
    void raisesAHighFailureRateAlertWhenOverHalfOfAFilesReadyRowsAreRejected() throws Exception {
        stubFolderWithOneFile("file-1", "Messy.xlsx");
        when(syncJobRepository.getReferenceById(jobId)).thenReturn(jobEntity);

        String key = "cohorts/" + cohortId + "/scores/Messy.xlsx";
        when(workbookFetchService.fetchIfChanged(eq(DRIVE_ID), eq("file-1"), any(), eq(key)))
            .thenReturn(changed("Messy.xlsx", key, "bytes".getBytes(StandardCharsets.UTF_8)));
        when(s3StorageService.putObject(anyString(), any(byte[].class), anyString())).thenReturn("v1");

        IngestionRun highFailureRun = IngestionRun.builder().build();
        highFailureRun.setRowsRead(10);
        highFailureRun.setSkippedInvalid(7);
        highFailureRun.setCommittedNew(3);
        highFailureRun.setHighFailureRate(true);
        highFailureRun.setFailureRatePercent(70.0);
        when(gradingIngestionService.process(any(), any(), eq("Messy.xlsx"), any(), any(), anyString(), any(),
            anyString())).thenReturn(highFailureRun);

        runner.run(cohortId, jobId, actorId);

        // §4.5 / B7 AC3 — a loud alert, but the valid rows still commit (the archive still happens).
        verify(s3StorageService).putObject(eq(key), any(byte[].class), anyString());
        verify(syncEventService).emit(eq(jobId), eq("file.high_failure_rate"), any());

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(auditEventService).record(eq("HIGH_FAILURE_RATE"), eq(cohortId), eq(actorId), captor.capture());
        assertThat(captor.getValue()).containsEntry("file", "Messy.xlsx");
        assertThat(captor.getValue()).containsEntry("failureRatePercent", 70.0);
        assertThat(captor.getValue()).containsEntry("rowsRead", 10);
        assertThat(captor.getValue()).containsEntry("skippedInvalid", 7);
    }

    @Test
    void doesNotRaiseAHighFailureRateAlertWhenTheRunIsBelowThreshold() throws Exception {
        stubFolderWithOneFile("file-1", "Fine.xlsx");
        when(syncJobRepository.getReferenceById(jobId)).thenReturn(jobEntity);

        String key = "cohorts/" + cohortId + "/scores/Fine.xlsx";
        when(workbookFetchService.fetchIfChanged(eq(DRIVE_ID), eq("file-1"), any(), eq(key)))
            .thenReturn(changed("Fine.xlsx", key, "bytes".getBytes(StandardCharsets.UTF_8)));
        when(s3StorageService.putObject(anyString(), any(byte[].class), anyString())).thenReturn("v1");

        IngestionRun healthyRun = IngestionRun.builder().build();
        healthyRun.setRowsRead(10);
        healthyRun.setSkippedInvalid(1);
        healthyRun.setHighFailureRate(false);
        when(gradingIngestionService.process(any(), any(), eq("Fine.xlsx"), any(), any(), anyString(), any(),
            anyString())).thenReturn(healthyRun);

        runner.run(cohortId, jobId, actorId);

        verify(syncEventService, never()).emit(eq(jobId), eq("file.high_failure_rate"), any());
        verify(auditEventService, never()).record(eq("HIGH_FAILURE_RATE"), any(), any(), any());
    }

    @Test
    void aGradingIngestionFailureOnOneFileDoesNotStopItsSiblings() throws Exception {
        when(sharePointProperties.scoresFolder()).thenReturn(SCORES_FOLDER_NAME);
        when(syncJobRepository.getReferenceById(jobId)).thenReturn(jobEntity);

        when(graphDriveService.listChildren(DRIVE_ID, ROOT_ITEM_ID))
            .thenReturn(List.of(item("scores-1", SCORES_FOLDER_NAME, true)));
        when(graphDriveService.listChildren(DRIVE_ID, "scores-1"))
            .thenReturn(List.of(item("bad", "Broken.xlsx", false), item("good", "Fine.xlsx", false)));
        when(graphDriveService.getItem(DRIVE_ID, "bad")).thenReturn(details("Broken.xlsx", SCORES_FOLDER_NAME));
        when(graphDriveService.getItem(DRIVE_ID, "good")).thenReturn(details("Fine.xlsx", SCORES_FOLDER_NAME));

        String badKey = "cohorts/" + cohortId + "/scores/Broken.xlsx";
        String goodKey = "cohorts/" + cohortId + "/scores/Fine.xlsx";
        when(workbookFetchService.fetchIfChanged(eq(DRIVE_ID), eq("bad"), any(), eq(badKey)))
            .thenReturn(changed("Broken.xlsx", badKey, "bad".getBytes(StandardCharsets.UTF_8)));
        when(workbookFetchService.fetchIfChanged(eq(DRIVE_ID), eq("good"), any(), eq(goodKey)))
            .thenReturn(changed("Fine.xlsx", goodKey, "ok".getBytes(StandardCharsets.UTF_8)));
        when(gradingIngestionService.process(any(), any(), eq("Broken.xlsx"), any(), any(), anyString(), any(), anyString()))
            .thenThrow(new IllegalStateException("bad workbook"));
        when(s3StorageService.putObject(anyString(), any(byte[].class), anyString())).thenReturn("v1");

        runner.run(cohortId, jobId, actorId);

        // The healthy sibling still archives despite the other file's ingestion failure.
        verify(s3StorageService).putObject(eq(goodKey), any(byte[].class), anyString());
        verify(s3StorageService, never()).putObject(eq(badKey), any(byte[].class), anyString());
        verifyAuditCounts(1, 0, 1);
    }

    @Test
    void metadataFailureCountsTheFileAsFailedWithoutAnAuditRow() throws Exception {
        when(sharePointProperties.scoresFolder()).thenReturn(SCORES_FOLDER_NAME);
        when(graphDriveService.listChildren(DRIVE_ID, ROOT_ITEM_ID))
            .thenReturn(List.of(item("scores-1", SCORES_FOLDER_NAME, true)));
        when(graphDriveService.listChildren(DRIVE_ID, "scores-1"))
            .thenReturn(List.of(item("file-1", "Ghost.xlsx", false)));
        when(graphDriveService.getItem(DRIVE_ID, "file-1"))
            .thenThrow(new GraphAccessException("item vanished"));

        runner.run(cohortId, jobId, actorId);

        // Without metadata there is no key to record a row against, so only the count and the
        // event carry the failure.
        verify(syncFileRepository, never()).save(any());
        verify(workbookFetchService, never()).fetchIfChanged(anyString(), anyString(), any(), anyString());
        verifyAuditCounts(0, 0, 1);
    }

    @Test
    void failsTheJobWhenTheScoresFolderIsMissing() {
        when(sharePointProperties.scoresFolder()).thenReturn(SCORES_FOLDER_NAME);
        when(graphDriveService.listChildren(DRIVE_ID, ROOT_ITEM_ID))
            .thenReturn(List.of(item("other", "Reference Data", true)));

        runner.run(cohortId, jobId, actorId);

        assertThat(jobEntity.getStatus()).isEqualTo(CohortSyncJobStatus.FAILED);
        verify(auditEventService, never()).record(anyString(), any(), any(), any());
    }

    @SuppressWarnings("unchecked")
    private void verifyAuditCounts(int changedAndNew, int unchanged, int failed) {
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(auditEventService).record(eq("SYNC_COMPLETED"), eq(cohortId), eq(actorId), captor.capture());

        Map<String, Object> payload = captor.getValue();
        int recordedChanged = (int) payload.get("changed") + (int) payload.get("new");
        assertThat(recordedChanged).isEqualTo(changedAndNew);
        assertThat(payload).containsEntry("unchanged", unchanged);
        assertThat(payload).containsEntry("failed", failed);
        assertThat(payload).containsEntry("cohortName", "Test Cohort");
    }
}
