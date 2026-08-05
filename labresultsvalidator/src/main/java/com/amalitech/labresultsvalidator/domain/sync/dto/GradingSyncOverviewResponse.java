package com.amalitech.labresultsvalidator.domain.sync.dto;

import com.amalitech.labresultsvalidator.domain.grading.dto.FileIngestionSummary;

import com.amalitech.labresultsvalidator.domain.sync.entity.CohortSyncJob;
import com.amalitech.labresultsvalidator.domain.sync.entity.CohortSyncJobStatus;
import com.amalitech.labresultsvalidator.domain.grading.entity.IngestionRun;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * A grading sync run's overview: what the row-processing pipeline (B5-B10) consumed across every
 * workbook it reached during one {@code CohortSyncJob}, aggregated plus per-file ({@code files}).
 */
public record GradingSyncOverviewResponse(
    UUID jobId,
    UUID cohortId,
    CohortSyncJobStatus jobStatus,
    OffsetDateTime startedAt,
    OffsetDateTime completedAt,
    int filesProcessed,
    int rowsRead,
    int committedNew,
    int updatedCount,
    int skippedInvalid,
    int skippedUnchanged,
    int conflictsCount,
    int highFailureFileCount,
    List<FileIngestionSummary> files
) {
    public static GradingSyncOverviewResponse from(CohortSyncJob job, List<IngestionRun> runs) {
        int rowsRead = 0;
        int committedNew = 0;
        int updatedCount = 0;
        int skippedInvalid = 0;
        int skippedUnchanged = 0;
        int conflictsCount = 0;
        int highFailureFileCount = 0;
        List<FileIngestionSummary> files = runs.stream().map(FileIngestionSummary::from).toList();

        for (IngestionRun run : runs) {
            rowsRead += run.getRowsRead();
            committedNew += run.getCommittedNew();
            updatedCount += run.getUpdatedCount();
            skippedInvalid += run.getSkippedInvalid();
            skippedUnchanged += run.getSkippedUnchanged();
            conflictsCount += run.getConflictsCount();
            if (run.isHighFailureRate()) {
                highFailureFileCount++;
            }
        }

        return new GradingSyncOverviewResponse(
            job.getId(),
            job.getCohort().getId(),
            job.getStatus(),
            job.getStartedAt(),
            job.getCompletedAt(),
            runs.size(),
            rowsRead,
            committedNew,
            updatedCount,
            skippedInvalid,
            skippedUnchanged,
            conflictsCount,
            highFailureFileCount,
            files
        );
    }
}
