package com.amalitech.labresultsvalidator.domain.grading.sync;

import com.amalitech.labresultsvalidator.common.utils.Sha256Util;
import com.amalitech.labresultsvalidator.domain.sync.entity.SyncFileChangeState;
import com.amalitech.labresultsvalidator.infrastructure.graph.DriveItemDetails;
import com.amalitech.labresultsvalidator.infrastructure.graph.GraphDriveService;
import com.amalitech.labresultsvalidator.infrastructure.graph.SharePointProperties;
import com.amalitech.labresultsvalidator.infrastructure.graph.exception.GraphAccessException;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkbookFetchServiceTest {

    private static final String DRIVE_ID = "drive-1";
    private static final String ITEM_ID = "item-1";
    private static final String FILE_NAME = "BEM01 Scores.xlsx";
    private static final String S3_KEY = "cohorts/abc/scores/BEM01 Scores.xlsx";
    private static final long MAX_BYTES = 20L * 1024 * 1024;

    @Mock
    private GraphDriveService graphDriveService;
    @Mock
    private ScoreSheetChangeDetector changeDetector;
    @Mock
    private SharePointProperties sharePointProperties;

    private WorkbookFetchService service;

    @BeforeEach
    void setUp() {
        service = new WorkbookFetchService(graphDriveService, changeDetector, sharePointProperties);
    }

    private DriveItemDetails details(Long sizeBytes) {
        return new DriveItemDetails(FILE_NAME, "Scenario 1", "quickxor-abc", "cTag-1",
            sizeBytes, "https://sp/BEM01.xlsx");
    }

    /** A genuine two-sheet .xlsx, so POI is exercised rather than stubbed. */
    private static byte[] realWorkbookBytes() throws IOException {
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            wb.createSheet("BEM01").createRow(0).createCell(0).setCellValue("Total Score");
            wb.createSheet("How-To");
            wb.write(out);
            return out.toByteArray();
        }
    }

    @Test
    void returnsNoWorkbookAndNeverParsesWhenTheFileIsUnchanged() throws Exception {
        byte[] bytes = realWorkbookBytes();
        when(sharePointProperties.maxWorkbookBytes()).thenReturn(MAX_BYTES);
        when(graphDriveService.downloadFile(DRIVE_ID, ITEM_ID)).thenReturn(bytes);
        when(changeDetector.detect(S3_KEY, bytes)).thenReturn(SyncFileChangeState.UNCHANGED);

        FetchOutcome outcome = service.fetchIfChanged(DRIVE_ID, ITEM_ID, details((long) bytes.length), S3_KEY);

        assertThat(outcome.state()).isEqualTo(SyncFileChangeState.UNCHANGED);
        assertThat(outcome.hasWorkbook()).isFalse();
        // Fingerprint is still recorded for the audit row even though nothing was parsed.
        assertThat(outcome.sha256Hex()).isEqualTo(Sha256Util.sha256Hex(bytes));
    }

    @Test
    void parsesLocallyAndReturnsTheWorkbookWhenChanged() throws Exception {
        byte[] bytes = realWorkbookBytes();
        when(sharePointProperties.maxWorkbookBytes()).thenReturn(MAX_BYTES);
        when(graphDriveService.downloadFile(DRIVE_ID, ITEM_ID)).thenReturn(bytes);
        when(changeDetector.detect(S3_KEY, bytes)).thenReturn(SyncFileChangeState.CHANGED);

        FetchOutcome outcome = service.fetchIfChanged(DRIVE_ID, ITEM_ID, details((long) bytes.length), S3_KEY);

        assertThat(outcome.state()).isEqualTo(SyncFileChangeState.CHANGED);
        assertThat(outcome.hasWorkbook()).isTrue();

        try (FetchedWorkbook fetched = outcome.workbook()) {
            assertThat(fetched.fileName()).isEqualTo(FILE_NAME);
            assertThat(fetched.sharepointItemId()).isEqualTo(ITEM_ID);
            assertThat(fetched.s3Key()).isEqualTo(S3_KEY);
            assertThat(fetched.content()).isEqualTo(bytes);
            assertThat(fetched.workbook().getNumberOfSheets()).isEqualTo(2);
            assertThat(fetched.workbook().getSheetAt(0).getSheetName()).isEqualTo("BEM01");
        }
    }

    @Test
    void hashIsComputedOverTheExactBytesHandedToPoi() throws Exception {
        byte[] bytes = realWorkbookBytes();
        when(sharePointProperties.maxWorkbookBytes()).thenReturn(MAX_BYTES);
        when(graphDriveService.downloadFile(DRIVE_ID, ITEM_ID)).thenReturn(bytes);
        when(changeDetector.detect(S3_KEY, bytes)).thenReturn(SyncFileChangeState.NEW);

        FetchOutcome outcome = service.fetchIfChanged(DRIVE_ID, ITEM_ID, details((long) bytes.length), S3_KEY);

        try (FetchedWorkbook fetched = outcome.workbook()) {
            // B4 AC3 — version-to-data correspondence: the recorded hash describes the parsed bytes.
            assertThat(outcome.sha256Hex()).isEqualTo(Sha256Util.sha256Hex(fetched.content()));
        }
    }

    @Test
    void failsThatWorkbookWhenPoiCannotOpenIt() throws Exception {
        byte[] corrupt = "this is not a zip container at all".getBytes(StandardCharsets.UTF_8);
        when(sharePointProperties.maxWorkbookBytes()).thenReturn(MAX_BYTES);
        when(graphDriveService.downloadFile(DRIVE_ID, ITEM_ID)).thenReturn(corrupt);
        when(changeDetector.detect(S3_KEY, corrupt)).thenReturn(SyncFileChangeState.CHANGED);

        assertThatThrownBy(() ->
            service.fetchIfChanged(DRIVE_ID, ITEM_ID, details((long) corrupt.length), S3_KEY))
            .isInstanceOf(WorkbookParseException.class)
            .hasMessageContaining(FILE_NAME)
            .hasMessageContaining("corrupt");
    }

    @Test
    void rejectsAnOversizedWorkbookBeforeSpendingTheDownload() {
        when(sharePointProperties.maxWorkbookBytes()).thenReturn(1024L);

        assertThatThrownBy(() ->
            service.fetchIfChanged(DRIVE_ID, ITEM_ID, details(5_000_000L), S3_KEY))
            .isInstanceOf(GraphAccessException.class)
            .hasMessageContaining("above the");

        verify(graphDriveService, never()).downloadFile(anyString(), anyString());
        verify(changeDetector, never()).detect(anyString(), any());
    }

    @Test
    void stillProceedsWhenGraphOmitsTheFileSize() throws Exception {
        byte[] bytes = realWorkbookBytes();
        when(sharePointProperties.maxWorkbookBytes()).thenReturn(MAX_BYTES);
        when(graphDriveService.downloadFile(DRIVE_ID, ITEM_ID)).thenReturn(bytes);
        when(changeDetector.detect(S3_KEY, bytes)).thenReturn(SyncFileChangeState.NEW);

        FetchOutcome outcome = service.fetchIfChanged(DRIVE_ID, ITEM_ID, details(null), S3_KEY);

        assertThat(outcome.hasWorkbook()).isTrue();
        outcome.workbook().close();
    }

    @Test
    void propagatesDownloadFailuresSoTheCallerCanFailJustThisFile() throws Exception {
        when(sharePointProperties.maxWorkbookBytes()).thenReturn(MAX_BYTES);
        when(graphDriveService.downloadFile(DRIVE_ID, ITEM_ID))
            .thenThrow(new GraphAccessException("throttled after retries"));

        assertThatThrownBy(() ->
            service.fetchIfChanged(DRIVE_ID, ITEM_ID, details(1024L), S3_KEY))
            .isInstanceOf(GraphAccessException.class);

        verify(changeDetector, never()).detect(anyString(), any());
    }
}
