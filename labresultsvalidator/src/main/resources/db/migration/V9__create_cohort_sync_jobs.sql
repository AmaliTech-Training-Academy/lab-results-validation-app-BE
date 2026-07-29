CREATE TABLE cohort_sync_jobs (
    id           UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    cohort_id    UUID         NOT NULL REFERENCES cohorts(id),
    status       VARCHAR(20)  NOT NULL DEFAULT 'RUNNING'
                     CONSTRAINT chk_sync_job_status CHECK (status IN ('RUNNING','COMPLETED','FAILED')),
    started_at   TIMESTAMPTZ  NOT NULL,
    completed_at TIMESTAMPTZ,
    triggered_by UUID,
    target_item_id VARCHAR(200),
    sync_events_json JSONB    NOT NULL DEFAULT '[]'::jsonb,
    created_by   UUID,
    updated_by   UUID,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_sync_jobs_cohort_id ON cohort_sync_jobs (cohort_id);
CREATE INDEX idx_sync_jobs_cohort_status ON cohort_sync_jobs (cohort_id, status);

-- Enforces one RUNNING sync job per cohort at the DB level (B1 AC3).
CREATE UNIQUE INDEX uq_sync_jobs_cohort_running ON cohort_sync_jobs (cohort_id) WHERE status = 'RUNNING';