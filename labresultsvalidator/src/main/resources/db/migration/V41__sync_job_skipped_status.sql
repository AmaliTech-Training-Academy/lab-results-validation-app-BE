-- ============================================================
-- Weekly sync: distinguish "nothing to do — no file changed since the
-- previous run" from "completed clean" (which itself covers both a
-- genuinely-empty cohort and a run that actually processed new/changed
-- files) and from "everything failed to read". All three previously
-- rendered identically: COMPLETED with every count at zero.
-- Postgres has no ALTER ... CHECK, so the constraint is dropped and
-- re-added with SKIPPED admitted alongside the existing values.
-- ============================================================
ALTER TABLE cohort_sync_jobs
    DROP CONSTRAINT chk_sync_job_status;
ALTER TABLE cohort_sync_jobs
    ADD CONSTRAINT chk_sync_job_status
    CHECK (status IN ('RUNNING','COMPLETED','PARTIAL','FAILED','SKIPPED'));
