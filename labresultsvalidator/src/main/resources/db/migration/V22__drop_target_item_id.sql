-- Per-file sync (POST /cohorts/{id}/sync/files/{itemId}) has been removed; every sync run now
-- always sweeps the whole cohort's score-sheet folder, so target_item_id is never populated.
ALTER TABLE cohort_sync_jobs DROP COLUMN target_item_id;
