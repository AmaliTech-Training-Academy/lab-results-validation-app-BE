CREATE TABLE cohort_gate4_jobs (
    id           UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    cohort_id    UUID         NOT NULL REFERENCES cohorts(id),
    status       VARCHAR(20)  NOT NULL DEFAULT 'RUNNING'
                     CONSTRAINT chk_gate4_job_status CHECK (status IN ('RUNNING','COMPLETED','FAILED')),
    started_at   TIMESTAMPTZ  NOT NULL,
    completed_at TIMESTAMPTZ,
    triggered_by UUID,
    gate_events_json JSONB    NOT NULL DEFAULT '[]'::jsonb,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_gate4_jobs_cohort_id ON cohort_gate4_jobs (cohort_id);
CREATE INDEX idx_gate4_jobs_cohort_status ON cohort_gate4_jobs (cohort_id, status);
