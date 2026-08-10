-- ============================================================
-- Drops learners.learner_id and instructor_contacts.instructor_id — both were
-- placeholder columns with zero real information, confirmed against every
-- write site in the codebase:
--   * learners.learner_id was always set to the exact same value as
--     learners.email (ReferenceCommitService.persistLearners) — the
--     reference bundle's LearnerRow has no separate learner-ID field at all,
--     so there was never a code path where the two could differ.
--     uq_learners_cohort_learner_id (V30) enforced nothing that
--     uq_learners_cohort_email didn't already enforce.
--   * instructor_contacts.instructor_id was set to a random UUID string
--     (ReferenceCommitService.persistInstructors) purely to satisfy its own
--     NOT NULL UNIQUE constraint — it never carried a real identifier from
--     any source data.
-- Both drop cleanly: dropping a column drops any constraint/index defined on
-- it, so uq_learners_cohort_learner_id and instructor_contacts' implicit
-- UNIQUE(instructor_id) go with them automatically. uq_learners_cohort_email
-- and instructor_contacts' UNIQUE(email) already cover the uniqueness these
-- columns provided.
-- ============================================================

ALTER TABLE learners DROP COLUMN learner_id;
ALTER TABLE instructor_contacts DROP COLUMN instructor_id;
