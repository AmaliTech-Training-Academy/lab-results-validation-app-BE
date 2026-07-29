CREATE TABLE cohort_sync_files (
    id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    cohort_id       UUID         NOT NULL REFERENCES cohorts(id),
    sync_job_id     UUID         NOT NULL REFERENCES cohort_sync_jobs(id),
    s3_key          VARCHAR(500) NOT NULL,
    s3_version_id   VARCHAR(200),
    file_name       VARCHAR(255) NOT NULL,
    scenario_folder VARCHAR(255),
    created_by      UUID,
    updated_by      UUID,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_sync_files_cohort_id ON cohort_sync_files (cohort_id);
CREATE INDEX idx_sync_files_sync_job_id ON cohort_sync_files (sync_job_id);

-- Supports "find the previous archived row for this filename" without listing S3.
CREATE INDEX idx_sync_files_cohort_filename ON cohort_sync_files (cohort_id, file_name);