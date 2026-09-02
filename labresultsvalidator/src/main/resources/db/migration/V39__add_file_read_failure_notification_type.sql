-- A file the weekly sync could not even read (bad metadata, download/parse failure, or a failed
-- archive) previously had no admin-facing notification at all — it never produces an IngestionRun,
-- so it was invisible to both the admin digest and the immediate alert. Widening chk_notif_type to
-- admit 'file_read_failure' alongside the existing types (Postgres has no ALTER ... CHECK, so the
-- constraint is dropped and re-added).
ALTER TABLE notifications DROP CONSTRAINT chk_notif_type;
ALTER TABLE notifications
    ADD CONSTRAINT chk_notif_type CHECK (type IN (
        'instructor_digest','admin_run_digest','standup_failure',
        'high_failure','conflict_alert','stood_up','file_read_failure'));
