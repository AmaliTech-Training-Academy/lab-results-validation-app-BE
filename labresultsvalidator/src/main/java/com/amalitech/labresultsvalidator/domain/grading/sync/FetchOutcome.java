package com.amalitech.labresultsvalidator.domain.grading.sync;

import com.amalitech.labresultsvalidator.domain.sync.entity.SyncFileChangeState;

/**
 * What fetching one workbook concluded (B3 / B4).
 *
 * <p>{@code sha256Hex} is present for every outcome, including {@code UNCHANGED}, so the audit
 * row records the fingerprint of the bytes actually seen this run whether or not they were
 * parsed (B4 AC3).
 *
 * @param state      {@code NEW}, {@code CHANGED} or {@code UNCHANGED}
 * @param sha256Hex  SHA-256 (hex) over the exact bytes downloaded
 * @param workbook   the open workbook for {@code NEW}/{@code CHANGED}; {@code null} when
 *                   {@code UNCHANGED}, since POI is never invoked in that case (B3 AC2)
 */
public record FetchOutcome(
    SyncFileChangeState state,
    String sha256Hex,
    FetchedWorkbook workbook
) {
    public static FetchOutcome unchanged(String sha256Hex) {
        return new FetchOutcome(SyncFileChangeState.UNCHANGED, sha256Hex, null);
    }

    /** True when a workbook was parsed and is waiting to be processed. */
    public boolean hasWorkbook() {
        return workbook != null;
    }
}
