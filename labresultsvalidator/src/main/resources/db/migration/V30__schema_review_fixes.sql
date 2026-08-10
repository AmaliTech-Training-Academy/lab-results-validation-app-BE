-- ============================================================
-- Fixes from the DB schema review (docs/db-schema-review.md):
--   1. Missing FKs on triggered_by/created_by/updated_by (4 tables) — these
--      columns were added across V7/V8/V9/V10/V17 without the
--      REFERENCES users(id) ON DELETE SET NULL that every other actor/audit
--      column in this schema has (see V1, V5).
--   2. Drop redundant/duplicate indexes — each is either byte-for-byte
--      identical to an index a UNIQUE constraint already creates, or a
--      single-column index whose leading column is already covered by a
--      composite index on the same table.
--   3. Missing index on notifications.cohort_id — the only cohort-scoped
--      column on that table with no supporting index.
--   4. RUNNING-job race guard on cohort_gate4_jobs, for parity with the two
--      sibling job tables (cohort_standup_jobs, cohort_sync_jobs) which both
--      already enforce "one RUNNING job per cohort" at the DB level.
--   5. is_locked can only be true for a STOOD_UP cohort — codifies the rule
--      CohortService.lockCohort() already enforces in application code.
--   6. learners uniqueness moves from global to per-cohort. LearnerRepository
--      already queries existsByLearnerIdAndCohortId/findByLearnerIdAndCohortId
--      on that assumption; no code path looks up a learner by learner_id or
--      email without a cohort_id, so nothing relies on global uniqueness.
-- ============================================================

-- 1. Missing FKs -------------------------------------------------------------
ALTER TABLE cohort_gate4_jobs
    ADD CONSTRAINT fk_gate4_jobs_triggered_by FOREIGN KEY (triggered_by) REFERENCES users(id) ON DELETE SET NULL,
    ADD CONSTRAINT fk_gate4_jobs_created_by   FOREIGN KEY (created_by)   REFERENCES users(id) ON DELETE SET NULL,
    ADD CONSTRAINT fk_gate4_jobs_updated_by   FOREIGN KEY (updated_by)   REFERENCES users(id) ON DELETE SET NULL;

ALTER TABLE cohort_sync_jobs
    ADD CONSTRAINT fk_sync_jobs_triggered_by FOREIGN KEY (triggered_by) REFERENCES users(id) ON DELETE SET NULL,
    ADD CONSTRAINT fk_sync_jobs_created_by   FOREIGN KEY (created_by)   REFERENCES users(id) ON DELETE SET NULL,
    ADD CONSTRAINT fk_sync_jobs_updated_by   FOREIGN KEY (updated_by)   REFERENCES users(id) ON DELETE SET NULL;

ALTER TABLE cohort_sync_files
    ADD CONSTRAINT fk_sync_files_created_by FOREIGN KEY (created_by) REFERENCES users(id) ON DELETE SET NULL,
    ADD CONSTRAINT fk_sync_files_updated_by FOREIGN KEY (updated_by) REFERENCES users(id) ON DELETE SET NULL;

ALTER TABLE sync_schedules
    ADD CONSTRAINT fk_sync_schedules_created_by FOREIGN KEY (created_by) REFERENCES users(id) ON DELETE SET NULL,
    ADD CONSTRAINT fk_sync_schedules_updated_by FOREIGN KEY (updated_by) REFERENCES users(id) ON DELETE SET NULL;

-- 2. Redundant indexes --------------------------------------------------------
DROP INDEX IF EXISTS idx_modules_spec_code;      -- duplicate of uq_module_code (specialization_id, code)
DROP INDEX IF EXISTS idx_learners_learnerid;     -- duplicate of learners_learner_id_key (learner_id UNIQUE)
DROP INDEX IF EXISTS idx_gate4_jobs_cohort_id;   -- covered by idx_gate4_jobs_cohort_status leading column
DROP INDEX IF EXISTS idx_sync_jobs_cohort_id;    -- covered by idx_sync_jobs_cohort_status leading column
DROP INDEX IF EXISTS idx_results_learner;        -- covered by uq_lab_result (learner_id, lab_id) leading column

-- 3. Missing index -------------------------------------------------------------
CREATE INDEX idx_notifications_cohort ON notifications (cohort_id);

-- 4. RUNNING-job race guard on cohort_gate4_jobs -------------------------------
CREATE UNIQUE INDEX uq_gate4_jobs_cohort_running ON cohort_gate4_jobs (cohort_id) WHERE status = 'RUNNING';

-- 5. is_locked / lifecycle_state invariant -------------------------------------
ALTER TABLE cohorts
    ADD CONSTRAINT chk_cohort_lock_state CHECK (NOT is_locked OR lifecycle_state = 'STOOD_UP');

-- 6. learners: global uniqueness -> per-cohort uniqueness ----------------------
ALTER TABLE learners DROP CONSTRAINT learners_learner_id_key;
ALTER TABLE learners DROP CONSTRAINT learners_email_key;
ALTER TABLE learners ADD CONSTRAINT uq_learners_cohort_learner_id UNIQUE (cohort_id, learner_id);
ALTER TABLE learners ADD CONSTRAINT uq_learners_cohort_email      UNIQUE (cohort_id, email);
