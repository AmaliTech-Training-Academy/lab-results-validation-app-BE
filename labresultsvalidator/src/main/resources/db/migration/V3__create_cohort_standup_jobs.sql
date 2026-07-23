-- ============================================================
-- Stand-up job tracking (PRD A2 AC2): one RUNNING job per cohort at a time.
-- Gate 1-4 execution against a job is out of scope here (future tickets);
-- this only creates and guards the job record.
-- ============================================================
CREATE TABLE cohort_standup_jobs (
    id            UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    cohort_id     UUID         NOT NULL REFERENCES cohorts(id) ON DELETE RESTRICT,
    status        VARCHAR(20)  NOT NULL DEFAULT 'RUNNING' CHECK (status IN ('RUNNING','COMPLETED','FAILED')),
    started_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    completed_at  TIMESTAMPTZ,
    triggered_by  UUID         REFERENCES users(id) ON DELETE SET NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

-- Race-safe enforcement of "one stand-up job per cohort at a time" at the DB level.
CREATE UNIQUE INDEX uq_cohort_standup_job_running
    ON cohort_standup_jobs (cohort_id) WHERE status = 'RUNNING';

CREATE TRIGGER trg_cohort_standup_jobs_updated_at
    BEFORE UPDATE ON cohort_standup_jobs
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
