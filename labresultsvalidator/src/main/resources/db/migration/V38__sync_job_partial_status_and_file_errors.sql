-- ============================================================
-- Weekly sync: distinguish "completed clean" from "completed with
-- some files failed", and record why a file failed.
-- ============================================================

-- 1. cohort_sync_jobs.status had no way to say "the run finished but not
--    every file made it" — a job where one workbook is unreadable and one
--    where every workbook synced cleanly both landed on COMPLETED, so a
--    dashboard/list view couldn't tell them apart without opening the run.
--    Postgres has no ALTER ... CHECK, so the constraint is dropped and
--    re-added with PARTIAL admitted alongside the existing values.
ALTER TABLE cohort_sync_jobs
    DROP CONSTRAINT chk_sync_job_status;
ALTER TABLE cohort_sync_jobs
    ADD CONSTRAINT chk_sync_job_status
    CHECK (status IN ('RUNNING','COMPLETED','PARTIAL','FAILED'));

-- 2. cohort_sync_files recorded that a file failed (change_state = FAILED)
--    but never why — the actual error text only ever reached the sync
--    SSE stream's events JSON and the server log, both ephemeral/hard to
--    query. Nullable: every other state (NEW/CHANGED/UNCHANGED) leaves it
--    unset.
ALTER TABLE cohort_sync_files
    ADD COLUMN error_message VARCHAR(1000);
