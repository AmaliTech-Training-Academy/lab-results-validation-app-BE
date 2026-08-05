package com.amalitech.labresultsvalidator.domain.grading.sync;

import com.amalitech.labresultsvalidator.domain.sync.entity.SyncFileChangeState;
import com.amalitech.labresultsvalidator.infrastructure.storage.S3StorageService;
import com.amalitech.labresultsvalidator.infrastructure.storage.exception.S3StorageException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ScoreSheetChangeDetectorTest {

    private static final String KEY = "cohorts/abc/scores/BEM01 Scores.xlsx";

    @Mock
    private S3StorageService s3StorageService;

    @InjectMocks
    private ScoreSheetChangeDetector detector;

    private static byte[] bytes(String content) {
        return content.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void treatsFileAsNewWhenNoObjectIsArchivedYet() {
        when(s3StorageService.exists(KEY)).thenReturn(false);

        SyncFileChangeState state = detector.detect(KEY, bytes("workbook-v1"));

        assertThat(state).isEqualTo(SyncFileChangeState.NEW);
        // Nothing to compare against, so the archived copy is never fetched.
        verify(s3StorageService, never()).getObject(KEY);
    }

    @Test
    void reportsUnchangedWhenBytesMatchTheArchivedCopy() {
        when(s3StorageService.exists(KEY)).thenReturn(true);
        when(s3StorageService.getObject(KEY)).thenReturn(bytes("workbook-v1"));

        SyncFileChangeState state = detector.detect(KEY, bytes("workbook-v1"));

        assertThat(state).isEqualTo(SyncFileChangeState.UNCHANGED);
    }

    @Test
    void reportsChangedWhenBytesDifferFromTheArchivedCopy() {
        when(s3StorageService.exists(KEY)).thenReturn(true);
        when(s3StorageService.getObject(KEY)).thenReturn(bytes("workbook-v1"));

        SyncFileChangeState state = detector.detect(KEY, bytes("workbook-v2-with-new-scores"));

        assertThat(state).isEqualTo(SyncFileChangeState.CHANGED);
    }

    @Test
    void reportsChangedWhenOnlyOneByteDiffers() {
        when(s3StorageService.exists(KEY)).thenReturn(true);
        when(s3StorageService.getObject(KEY)).thenReturn(new byte[]{1, 2, 3, 4});

        SyncFileChangeState state = detector.detect(KEY, new byte[]{1, 2, 3, 5});

        assertThat(state).isEqualTo(SyncFileChangeState.CHANGED);
    }

    @Test
    void letsS3ReadFailuresPropagateRatherThanGuessing() {
        when(s3StorageService.exists(KEY)).thenReturn(true);
        when(s3StorageService.getObject(KEY)).thenThrow(new S3StorageException("throttled"));

        // Guessing "changed" here would re-upload and re-notify on every transient S3 blip;
        // failing this one file instead is what §4.5 asks for.
        assertThatThrownBy(() -> detector.detect(KEY, bytes("workbook-v1")))
            .isInstanceOf(S3StorageException.class);
    }
}
