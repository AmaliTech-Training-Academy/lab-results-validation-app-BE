package com.amalitech.labresultsvalidator.domain.sync.dto;

import com.amalitech.labresultsvalidator.domain.sync.entity.CohortSyncFile;
import com.amalitech.labresultsvalidator.domain.sync.entity.SyncFileChangeState;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One file a sync run touched, for a run-detail screen. Previously the only way to see which
 * files failed (and why) was to hold open the run's SSE stream while it happened — this exposes
 * the same {@link CohortSyncFile} rows the stream is built from as a plain, pollable list.
 */
public record SyncFileResponse(
    UUID id,
    String fileName,
    String scenarioFolder,
    SyncFileChangeState changeState,
    String errorMessage,
    OffsetDateTime createdAt
) {
    public static SyncFileResponse from(CohortSyncFile file) {
        return new SyncFileResponse(
            file.getId(),
            file.getFileName(),
            file.getScenarioFolder(),
            file.getChangeState(),
            file.getErrorMessage(),
            file.getCreatedAt()
        );
    }
}
