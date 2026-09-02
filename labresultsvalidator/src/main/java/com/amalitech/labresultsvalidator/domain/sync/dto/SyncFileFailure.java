package com.amalitech.labresultsvalidator.domain.sync.dto;

/**
 * One file a sync run could not read or process — carried from {@code CohortSyncJobRunner} into
 * the admin notifications (digest section and immediate alert), so a read failure is visible
 * somewhere a human will actually see it, not just the server log and the sync SSE stream.
 *
 * @param fileName     the workbook's name, or {@code "item " + itemId} when metadata itself
 *                     couldn't be read and no name is known yet
 * @param errorMessage why the file failed
 */
public record SyncFileFailure(String fileName, String errorMessage) {
}
