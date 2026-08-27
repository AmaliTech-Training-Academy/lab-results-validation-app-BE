-- ============================================================
-- 3NF fix: cohort_sync_files.cohort_id was a transitive dependency on
-- sync_job_id -> cohort_sync_jobs.cohort_id (both NOT NULL, and set from the
-- same in-flight cohort/job pair at the one write site, CohortSyncJobRunner)
-- with no UNIQUE constraint depending on the direct column (unlike
-- learners.cohort_id, kept in V30/V31 because Postgres can't express a
-- UNIQUE constraint across a join). Same shape as
-- ingestion_conflicts.cohort_id, already dropped in V32 -- this one was
-- missed by that review pass.
--
-- CohortSyncFileRepository.findByCohortIdAndFileNameOrderByCreatedAtDesc now
-- joins through sync_job_id instead. Dropping the column cascades the drop
-- of its dependent indexes and FK:
--   - idx_sync_files_cohort_id
--   - idx_sync_files_cohort_filename (cohort_id, file_name)      -- replaced below
--   - idx_sync_files_cohort_item (cohort_id, sharepoint_item_id, created_at DESC)
--     -- unused by any query in the current codebase; not recreated
--   - cohort_sync_files_cohort_id_fkey
-- ============================================================

ALTER TABLE cohort_sync_files DROP COLUMN cohort_id;

-- Replaces idx_sync_files_sync_job_id: same leading column, now also covering
-- the file_name filter the rewritten repository query joins on.
DROP INDEX idx_sync_files_sync_job_id;
CREATE INDEX idx_sync_files_syncjob_filename ON cohort_sync_files (sync_job_id, file_name);
