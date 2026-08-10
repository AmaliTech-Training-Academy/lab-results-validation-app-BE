-- ============================================================
-- Mechanical schema-hygiene fixes from a fresh 3NF/best-practices recheck.
-- All additive/consistency changes, no app-code impact, verified against
-- the app's own invariants before applying (see per-item notes below).
-- ============================================================

-- 1. cohort_sync_files.change_state had a COMMENT documenting its allowed
--    values (NEW | CHANGED | UNCHANGED | FAILED) but no CHECK enforcing
--    them — every other state column in this schema does (V1's header:
--    "VARCHAR + CHECK for all state columns"). Nullable column, so NULL
--    rows (pre-V12, or any row change detection hasn't run for yet) still
--    pass — CHECK constraints in Postgres are satisfied on NULL.
ALTER TABLE cohort_sync_files
    ADD CONSTRAINT chk_sync_files_change_state
    CHECK (change_state IN ('NEW','CHANGED','UNCHANGED','FAILED'));

-- 2. cohort_id FK on 4 tables had no explicit ON DELETE action (defaulting
--    to NO ACTION), inconsistent with cohort_standup_jobs_cohort_id_fkey's
--    explicit ON DELETE RESTRICT. Aligning all of them on RESTRICT, matching
--    every other FK to cohorts(id) in this schema (specializations, labs,
--    modules, learners, ingestion_runs, cohort_standup_jobs all use it).
--    Postgres has no ALTER ... ON DELETE, so each is dropped and re-added.
ALTER TABLE cohort_gate4_jobs
    DROP CONSTRAINT cohort_gate4_jobs_cohort_id_fkey,
    ADD CONSTRAINT cohort_gate4_jobs_cohort_id_fkey
        FOREIGN KEY (cohort_id) REFERENCES cohorts(id) ON DELETE RESTRICT;

ALTER TABLE cohort_sync_jobs
    DROP CONSTRAINT cohort_sync_jobs_cohort_id_fkey,
    ADD CONSTRAINT cohort_sync_jobs_cohort_id_fkey
        FOREIGN KEY (cohort_id) REFERENCES cohorts(id) ON DELETE RESTRICT;

ALTER TABLE cohort_sync_files
    DROP CONSTRAINT cohort_sync_files_cohort_id_fkey,
    ADD CONSTRAINT cohort_sync_files_cohort_id_fkey
        FOREIGN KEY (cohort_id) REFERENCES cohorts(id) ON DELETE RESTRICT;

ALTER TABLE sync_schedules
    DROP CONSTRAINT sync_schedules_cohort_id_fkey,
    ADD CONSTRAINT sync_schedules_cohort_id_fkey
        FOREIGN KEY (cohort_id) REFERENCES cohorts(id) ON DELETE RESTRICT;

-- Same gap found on cohort_sync_files.sync_job_id while fixing the above —
-- ingestion_runs_sync_job_id_fkey already uses RESTRICT; align this one too.
ALTER TABLE cohort_sync_files
    DROP CONSTRAINT cohort_sync_files_sync_job_id_fkey,
    ADD CONSTRAINT cohort_sync_files_sync_job_id_fkey
        FOREIGN KEY (sync_job_id) REFERENCES cohort_sync_jobs(id) ON DELETE RESTRICT;

-- 3. cohort_standup_jobs' status CHECK was left with its Postgres-assigned
--    auto-generated name (from the original unnamed inline CHECK in V3),
--    while the identical constraint on cohort_gate4_jobs/cohort_sync_jobs
--    is explicitly named. Cosmetic, but renaming costs nothing.
ALTER TABLE cohort_standup_jobs
    RENAME CONSTRAINT cohort_standup_jobs_status_check TO chk_standup_job_status;

-- 4. started_at has DEFAULT now() on cohort_standup_jobs but not on
--    cohort_gate4_jobs/cohort_sync_jobs — the app always passes it
--    explicitly on those two today, so this isn't a live bug, just a
--    consistency gap. Adding the same safety-net default.
ALTER TABLE cohort_gate4_jobs ALTER COLUMN started_at SET DEFAULT now();
ALTER TABLE cohort_sync_jobs ALTER COLUMN started_at SET DEFAULT now();

-- 5. ingestion_runs.failure_rate_percent had no range check. Verified against
--    GradingIngestionService.commit(): failureRate = rejectedRows/readyRows
--    where rejectedRows is always <= readyRows by construction, so the
--    stored percent is always in [0,100] today — this just makes that
--    invariant durable at the DB level too.
ALTER TABLE ingestion_runs
    ADD CONSTRAINT chk_failure_rate_percent
    CHECK (failure_rate_percent >= 0 AND failure_rate_percent <= 100);

-- 6. lab_results.row_value_hash was VARCHAR(64) while the other fixed-length
--    hex digest columns (ingestion_runs.file_sha256, cohort_sync_files.
--    file_sha256) are CHAR(64). RowFingerprint.compute() -> Sha256Util
--    always produces exactly 64 lowercase hex chars, so this is a lossless,
--    purely cosmetic type alignment.
ALTER TABLE lab_results ALTER COLUMN row_value_hash TYPE CHAR(64);
