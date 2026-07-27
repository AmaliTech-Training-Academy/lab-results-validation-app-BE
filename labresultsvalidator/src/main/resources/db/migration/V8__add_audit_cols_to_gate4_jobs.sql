ALTER TABLE cohort_gate4_jobs
    ADD COLUMN created_by UUID,
    ADD COLUMN updated_by UUID;
