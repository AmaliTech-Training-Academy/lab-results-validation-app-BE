-- cohort_sync_files.s3_key has been NOT NULL since V10, which was correct
-- while every row described a file that had been fetched and archived.
-- The metadata-failure branch breaks that assumption: Graph is asked for the
-- item's details, that call fails, and the run never reaches a download — so
-- there is no archive key to record, and never will be for that row.
--
-- The CHECK keeps the original guarantee everywhere it still applies: only a
-- FAILED row may omit the key. NEW/CHANGED/UNCHANGED must still carry one.
ALTER TABLE cohort_sync_files
    ALTER COLUMN s3_key DROP NOT NULL;

ALTER TABLE cohort_sync_files
    ADD CONSTRAINT chk_sync_files_s3_key_present
    CHECK (s3_key IS NOT NULL OR change_state = 'FAILED');
