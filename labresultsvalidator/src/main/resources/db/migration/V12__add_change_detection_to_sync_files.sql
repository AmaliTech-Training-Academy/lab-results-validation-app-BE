-- ============================================================
-- B3 / B4 — file-level change detection against the S3 archive.
--
-- The skip decision needs no stored comparand: the bucket is versioned and the key
-- is stable, so the object currently at a file's key *is* the copy the last run
-- archived. Detection downloads SharePoint's bytes and compares them to that
-- object; uploading a difference creates a new version and advances the baseline.
--
-- These columns record the provenance of each decision so a run is reconstructable:
-- what SharePoint reported about the file (B3 AC1), the fingerprint of the exact
-- bytes parsed (B4 AC3), and the outcome (B3 AC2). All nullable, so rows written
-- before this migration stay valid.
-- ============================================================
ALTER TABLE cohort_sync_files
    ADD COLUMN change_state          VARCHAR(20),
    ADD COLUMN quick_xor_hash        VARCHAR(128),
    ADD COLUMN sharepoint_version_id VARCHAR(200),
    ADD COLUMN file_sha256           CHAR(64),
    ADD COLUMN sharepoint_item_id    VARCHAR(200);

COMMENT ON COLUMN cohort_sync_files.change_state IS
    'NEW | CHANGED | UNCHANGED | FAILED - outcome of change detection for this file in this run.';

COMMENT ON COLUMN cohort_sync_files.quick_xor_hash IS
    'SharePoint server-computed content hash, read via the single-item GET (B3 AC1).';

COMMENT ON COLUMN cohort_sync_files.sharepoint_version_id IS
    'Content tag (cTag) from the single-item GET - changes when the file content changes (B3 AC1).';

COMMENT ON COLUMN cohort_sync_files.file_sha256 IS
    'SHA-256 (hex) over the exact bytes downloaded and handed to POI (B4 AC3).';

-- A row is now written for every file a run looked at, not only the ones it archived,
-- so "we saw it, nothing changed" is auditable (B3 AC2, PRD D1 AC2). s3_version_id
-- stays NULL for UNCHANGED and FAILED files because no new object version is written.
CREATE INDEX idx_sync_files_cohort_item
    ON cohort_sync_files (cohort_id, sharepoint_item_id, created_at DESC);

CREATE INDEX idx_sync_files_change_state
    ON cohort_sync_files (change_state);
