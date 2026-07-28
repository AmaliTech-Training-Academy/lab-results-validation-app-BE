ALTER TABLE cohort_standup_jobs
    ADD COLUMN IF NOT EXISTS gate_events_json JSONB NOT NULL DEFAULT '[]'::jsonb;
