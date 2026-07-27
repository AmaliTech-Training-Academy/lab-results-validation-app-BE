-- Holds the validated-but-not-yet-accepted reference bundle between Gate 3 and the Accept action.
CREATE TABLE cohort_standup_pending (
    cohort_id    UUID         PRIMARY KEY REFERENCES cohorts(id) ON DELETE CASCADE,
    bundle_json  JSONB        NOT NULL,
    passed_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    expires_at   TIMESTAMPTZ  NOT NULL
);
