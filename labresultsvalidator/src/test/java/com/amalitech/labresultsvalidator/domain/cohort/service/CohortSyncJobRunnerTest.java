package com.amalitech.labresultsvalidator.domain.cohort.service;

import com.amalitech.labresultsvalidator.domain.cohort.entity.Cohort;
import com.amalitech.labresultsvalidator.domain.cohort.entity.CohortSyncFile;
import com.amalitech.labresultsvalidator.domain.cohort.entity.CohortSyncJob;
import com.amalitech.labresultsvalidator.domain.cohort.entity.CohortSyncJobStatus;
import com.amalitech.labresultsvalidator.domain.cohort.repository.CohortRepository;
import com.amalitech.labresultsvalidator.domain.cohort.repository.CohortSyncFileRepository;
import com.amalitech.labresultsvalidator.domain.cohort.repository.CohortSyncJobRepository;
import com.amalitech.labresultsvalidator.infrastructure.graph.DriveItemDetails;
import com.amalitech.labresultsvalidator.infrastructure.graph.DriveItemInfo;
import com.amalitech.labresultsvalidator.infrastructure.graph.GraphDriveService;
import com.amalitech.labresultsvalidator.infrastructure.graph.SharePointProperties;
import com.amalitech.labresultsvalidator.infrastructure.graph.exception.GraphAccessException;
import com.amalitech.labresultsvalidator.infrastructure.storage.S3StorageService;
import com.amalitech.labresultsvalidator.infrastructure.storage.exception.S3StorageException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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
            sseRegistry, auditEventService, graphDriveService, sharePointProperties, s3StorageService
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

    @Test
    void archivesEachFetchedFileToS3AndRecordsAnAuditRowPerFile() {
        when(sharePointProperties.scoresFolder()).thenReturn(SCORES_FOLDER_NAME);
        when(syncJobRepository.getReferenceById(jobId)).thenReturn(jobEntity);

        DriveItemInfo scoresFolder = item("scores-1", SCORES_FOLDER_NAME, true);
        when(graphDriveService.listChildren(DRIVE_ID, ROOT_ITEM_ID)).thenReturn(List.of(scoresFolder));

        DriveItemInfo directFile = item("file-1", "Direct.xlsx", false);
        DriveItemInfo scenarioFolder = item("scenario-1", "Scenario 1", true);
        when(graphDriveService.listChildren(DRIVE_ID, "scores-1"))
            .thenReturn(List.of(directFile, scenarioFolder));

        DriveItemInfo scenarioFile = item("file-2", "Results.xlsx", false);
        when(graphDriveService.listChildren(DRIVE_ID, "scenario-1")).thenReturn(List.of(scenarioFile));

        when(graphDriveService.downloadFile(DRIVE_ID, "file-1")).thenReturn("direct-bytes".getBytes());
        when(graphDriveService.downloadFile(DRIVE_ID, "file-2")).thenReturn("scenario-bytes".getBytes());
        when(s3StorageService.putObject(anyString(), any(byte[].class), anyString())).thenReturn("v1");

        runner.run(cohortId, jobId, actorId, null);

        String expectedDirectKey = "cohorts/" + cohortId + "/scores/Direct.xlsx";
        String expectedScenarioKey = "cohorts/" + cohortId + "/scores/Scenario 1/Results.xlsx";

        verify(s3StorageService).putObject(eq(expectedDirectKey), eq("direct-bytes".getBytes()), anyString());
        verify(s3StorageService).putObject(eq(expectedScenarioKey), eq("scenario-bytes".getBytes()), anyString());

        ArgumentCaptor<CohortSyncFile> captor = ArgumentCaptor.forClass(CohortSyncFile.class);
        verify(syncFileRepository, org.mockito.Mockito.times(2)).save(captor.capture());
        List<CohortSyncFile> saved = captor.getAllValues();

        assertThat(saved).anySatisfy(f -> {
            assertThat(f.getFileName()).isEqualTo("Direct.xlsx");
            assertThat(f.getScenarioFolder()).isNull();
            assertThat(f.getS3Key()).isEqualTo(expectedDirectKey);
            assertThat(f.getS3VersionId()).isEqualTo("v1");
            assertThat(f.getCohort()).isEqualTo(cohort);
            assertThat(f.getSyncJob()).isEqualTo(jobEntity);
        });
        assertThat(saved).anySatisfy(f -> {
            assertThat(f.getFileName()).isEqualTo("Results.xlsx");
            assertThat(f.getScenarioFolder()).isEqualTo("Scenario 1");
            assertThat(f.getS3Key()).isEqualTo(expectedScenarioKey);
        });

        verify(syncEventService, org.mockito.Mockito.times(2)).emit(eq(jobId), eq("file.archived"), any());
        assertThat(jobEntity.getStatus()).isEqualTo(CohortSyncJobStatus.COMPLETED);
        verify(auditEventService).record(eq("SYNC_COMPLETED"), eq(cohortId), eq(actorId),
            eq(Map.of("cohortName", "Test Cohort", "filesFetched", 2)));
    }

    @Test
    void toleratesAnS3UploadFailureWithoutFailingTheJobOrRecordingAFile() {
        when(sharePointProperties.scoresFolder()).thenReturn(SCORES_FOLDER_NAME);

        DriveItemInfo scoresFolder = item("scores-1", SCORES_FOLDER_NAME, true);
        when(graphDriveService.listChildren(DRIVE_ID, ROOT_ITEM_ID)).thenReturn(List.of(scoresFolder));

        DriveItemInfo directFile = item("file-1", "Direct.xlsx", false);
        when(graphDriveService.listChildren(DRIVE_ID, "scores-1")).thenReturn(List.of(directFile));
        when(graphDriveService.downloadFile(DRIVE_ID, "file-1")).thenReturn("bytes".getBytes());

        when(s3StorageService.putObject(anyString(), any(byte[].class), anyString()))
            .thenThrow(new S3StorageException("bucket unreachable"));

        runner.run(cohortId, jobId, actorId, null);

        verify(syncFileRepository, never()).save(any());
        verify(syncEventService).emit(eq(jobId), eq("file.archive_failed"), any());
        verify(syncEventService, never()).emit(eq(jobId), eq("file.archived"), any());

        // The upload failure is per-file tolerated: the download still counted as fetched,
        // so the job still completes successfully.
        assertThat(jobEntity.getStatus()).isEqualTo(CohortSyncJobStatus.COMPLETED);
        verify(auditEventService).record(eq("SYNC_COMPLETED"), eq(cohortId), eq(actorId),
            eq(Map.of("cohortName", "Test Cohort", "filesFetched", 1)));
    }

    @Test
    void singleFileSyncResolvesScenarioFolderAndBuildsTheSameKeyAFolderWalkWould() {
        when(syncJobRepository.getReferenceById(jobId)).thenReturn(jobEntity);
        when(sharePointProperties.scoresFolder()).thenReturn(SCORES_FOLDER_NAME);

        when(graphDriveService.downloadFile(DRIVE_ID, "file-2")).thenReturn("scenario-bytes".getBytes());
        when(graphDriveService.getItem(DRIVE_ID, "file-2"))
            .thenReturn(new DriveItemDetails("Results.xlsx", "Scenario 1"));
        when(s3StorageService.putObject(anyString(), any(byte[].class), anyString())).thenReturn("v1");

        runner.run(cohortId, jobId, actorId, "file-2");

        String expectedKey = "cohorts/" + cohortId + "/scores/Scenario 1/Results.xlsx";
        verify(s3StorageService).putObject(eq(expectedKey), eq("scenario-bytes".getBytes()), anyString());
    }

    @Test
    void singleFileSyncInScoresFolderItselfHasNoScenarioSegmentInTheKey() {
        when(syncJobRepository.getReferenceById(jobId)).thenReturn(jobEntity);
        when(sharePointProperties.scoresFolder()).thenReturn(SCORES_FOLDER_NAME);

        when(graphDriveService.downloadFile(DRIVE_ID, "file-1")).thenReturn("direct-bytes".getBytes());
        when(graphDriveService.getItem(DRIVE_ID, "file-1"))
            .thenReturn(new DriveItemDetails("Direct.xlsx", SCORES_FOLDER_NAME));
        when(s3StorageService.putObject(anyString(), any(byte[].class), anyString())).thenReturn("v1");

        runner.run(cohortId, jobId, actorId, "file-1");

        String expectedKey = "cohorts/" + cohortId + "/scores/Direct.xlsx";
        verify(s3StorageService).putObject(eq(expectedKey), eq("direct-bytes".getBytes()), anyString());
    }

    @Test
    void aPerFileDownloadFailureInsideTheFolderWalkIsToleratedAndSkipsArchiving() {
        when(sharePointProperties.scoresFolder()).thenReturn(SCORES_FOLDER_NAME);

        DriveItemInfo scoresFolder = item("scores-1", SCORES_FOLDER_NAME, true);
        when(graphDriveService.listChildren(DRIVE_ID, ROOT_ITEM_ID)).thenReturn(List.of(scoresFolder));

        DriveItemInfo brokenFile = item("file-1", "Broken.xlsx", false);
        when(graphDriveService.listChildren(DRIVE_ID, "scores-1")).thenReturn(List.of(brokenFile));
        when(graphDriveService.downloadFile(DRIVE_ID, "file-1"))
            .thenThrow(new GraphAccessException("network error"));

        runner.run(cohortId, jobId, actorId, null);

        verify(s3StorageService, never()).putObject(anyString(), any(byte[].class), anyString());
        verify(syncFileRepository, never()).save(any());
        verify(syncEventService).emit(eq(jobId), eq("file.failed"), any());
        assertThat(jobEntity.getStatus()).isEqualTo(CohortSyncJobStatus.COMPLETED);
        verify(auditEventService).record(eq("SYNC_COMPLETED"), eq(cohortId), eq(actorId),
            eq(Map.of("cohortName", "Test Cohort", "filesFetched", 0)));
    }
}