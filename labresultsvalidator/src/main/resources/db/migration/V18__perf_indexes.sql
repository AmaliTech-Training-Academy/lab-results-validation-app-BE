-- cohort_standup_jobs only had a partial unique index on (cohort_id) WHERE status = 'RUNNING'
-- (V3__create_cohort_standup_jobs.sql), which doesn't support existsByCohortIdAndStatus for
-- non-RUNNING statuses or the findTopBy.../findBy...OrderByStartedAtDesc repository methods.
CREATE INDEX idx_standup_jobs_cohort_id ON cohort_standup_jobs (cohort_id);

-- Supports IngestionConflictRepository.findByCohortIdAndStatus, which previously only benefited
-- from the narrower idx_conflicts_status (V1__create_tables.sql).
CREATE INDEX idx_conflicts_cohort_status ON ingestion_conflicts (cohort_id, status);
