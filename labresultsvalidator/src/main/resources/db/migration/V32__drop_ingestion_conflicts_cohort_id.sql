-- ============================================================
-- Final fix for docs/db-schema-review.md finding 1.2 (V31 only added a
-- consistency trigger). Unlike learners.cohort_id (1.1, kept — Postgres
-- UNIQUE constraints can't span a join, so it's load-bearing for
-- uq_learners_cohort_learner_id/uq_learners_cohort_email from V30),
-- ingestion_conflicts.cohort_id has no such constraint depending on it: it
-- was a pure query-convenience denormalization of
-- ingestion_runs.cohort_id via ingestion_run_id (NOT NULL). Dropping it
-- outright now that IngestionConflictRepository resolves cohort scoping via
-- a join/subquery on ingestion_run_id instead (see
-- IngestionConflictRepository.java).
-- ============================================================

DROP TRIGGER trg_conflicts_cohort_consistency ON ingestion_conflicts;
DROP FUNCTION check_conflict_cohort_matches_run();

-- idx_conflicts_cohort_status (cohort_id, status) depended on this column and
-- is dropped automatically by Postgres along with it.
ALTER TABLE ingestion_conflicts DROP COLUMN cohort_id;
