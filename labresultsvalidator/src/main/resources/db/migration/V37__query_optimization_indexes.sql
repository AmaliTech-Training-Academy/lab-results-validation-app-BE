-- ============================================================
-- Query-optimization pass: index gaps found by cross-referencing every
-- repository query method against the current index set (no dead weight
-- from the V30/V34 hygiene passes was left behind — these are genuinely
-- new gaps).
-- ============================================================

-- 1. cohort_standup_jobs / cohort_gate4_jobs / cohort_sync_jobs all serve the
--    same two access patterns from CohortStandUpService/CohortGate4Service/
--    CohortSyncService:
--      - existsByCohortIdAndStatus(cohortId, status)              -> (cohort_id, status)
--      - findTopByCohortIdOrderByStartedAtDesc /
--        findByCohortIdOrderByStartedAtDesc(Pageable)             -> (cohort_id, started_at DESC)
--    but indexing landed inconsistently: gate4/sync got (cohort_id, status) from
--    V7/V9, standup only got a plain (cohort_id) index from V18 (its own comment
--    admits this doesn't fully support existsByCohortIdAndStatus). None of the
--    three got the (cohort_id, started_at DESC) index that the identical
--    "latest thing for this cohort" query shape already has on ingestion_runs
--    (idx_runs_cohort_time) and audit_event (idx_audit_event_cohort) — without
--    it, findTopByCohortIdOrderByStartedAtDesc sorts instead of doing an
--    index-ordered scan + limit 1.
DROP INDEX idx_standup_jobs_cohort_id; -- superseded by the two composites below
CREATE INDEX idx_standup_jobs_cohort_status  ON cohort_standup_jobs (cohort_id, status);
CREATE INDEX idx_standup_jobs_cohort_started ON cohort_standup_jobs (cohort_id, started_at DESC);

CREATE INDEX idx_gate4_jobs_cohort_started ON cohort_gate4_jobs (cohort_id, started_at DESC);

CREATE INDEX idx_sync_jobs_cohort_started ON cohort_sync_jobs (cohort_id, started_at DESC);

-- 2. ingestion_conflicts: idx_conflicts_run (ingestion_run_id) and
--    idx_conflicts_status (status) are separate single-column indexes, but
--    every query in IngestionConflictRepository filters by ingestion_run_id
--    (directly, or via the cohort/sync-job subquery since cohort_id was
--    dropped in V32) and two of the five also filter by status — none filter
--    by status alone. A single composite covers all five query shapes
--    directly instead of relying on the planner to bitmap-AND two indexes;
--    idx_conflicts_status has no remaining standalone use.
DROP INDEX idx_conflicts_run;
DROP INDEX idx_conflicts_status;
CREATE INDEX idx_conflicts_run_status ON ingestion_conflicts (ingestion_run_id, status);

-- 3. notifications: findBySyncJobIdAndStatusAndDispatchPolicy /
--    countBySyncJobIdAndStatusAndDispatchPolicy (NotificationDispatchService,
--    fired on every sync-job auto-dispatch) filter on 3 columns but only
--    sync_job_id was indexed. idx_notifications_sync_job is dropped in favor
--    of this composite (leading column still covers the plain
--    findBySyncJobId lookup), matching the leading-column-reuse convention
--    from V30.
DROP INDEX idx_notifications_sync_job;
CREATE INDEX idx_notifications_syncjob_status_policy
    ON notifications (sync_job_id, status, dispatch_policy);
