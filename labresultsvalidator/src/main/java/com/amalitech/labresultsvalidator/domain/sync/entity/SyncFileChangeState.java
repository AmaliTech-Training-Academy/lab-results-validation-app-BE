package com.amalitech.labresultsvalidator.domain.sync.entity;

/**
 * Outcome of change detection for one workbook in one sync run (B3).
 *
 * <p>A row is written for every file a run looked at, so absence of a row means the run never
 * reached that file — never "nothing changed" (PRD D1 AC2).
 */
public enum SyncFileChangeState {

    /** No object existed at the file's S3 key: first time Validata has seen it. */
    NEW,

    /** SharePoint's bytes differ from the archived copy — the instructor edited the sheet. */
    CHANGED,

    /** SharePoint's bytes match the archived copy: no parse, no upload (B3 AC2). */
    UNCHANGED,

    /**
     * The file could not be handled — Graph download failed, or POI could not open it
     * (B4 AC2). The S3 baseline is deliberately left untouched so the next run retries.
     */
    FAILED
}
