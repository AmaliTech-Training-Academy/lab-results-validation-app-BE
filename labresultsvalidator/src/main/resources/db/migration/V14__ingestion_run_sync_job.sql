-- ============================================================
-- Links each per-file ingestion_runs row (row-processing layer, B5-B9) back to the
-- cohort_sync_jobs run that produced it (file-byte-level layer, B2-B4), so a sync
-- run's grading overview can be queried by job id.
--
-- Nullable: earlier test runs of the ingestion pipeline already left rows in this
-- table with no sync_job_id to backfill from (the app always sets it going forward,
-- via GradingIngestionService), so a hard NOT NULL would reject those existing rows.
-- ============================================================

ALTER TABLE ingestion_runs
    ADD COLUMN sync_job_id UUID REFERENCES cohort_sync_jobs(id) ON DELETE RESTRICT;

CREATE INDEX idx_ingestion_runs_sync_job ON ingestion_runs (sync_job_id);
