-- ============================================================
-- notifications: add sync_job_id — digests are staged per whole sync job (which may span
-- multiple files/ingestion_runs), not per individual file, so the existing ingestion_run_id
-- column alone isn't enough to group/filter a job's notifications.
-- ============================================================
ALTER TABLE notifications
    ADD COLUMN sync_job_id UUID REFERENCES cohort_sync_jobs(id) ON DELETE SET NULL;

CREATE INDEX idx_notifications_sync_job ON notifications (sync_job_id);

-- ============================================================
-- notification_settings: singleton row holding the global "auto-send instructor emails" toggle.
-- The fixed-id CHECK constraint (paired with the PK) makes a second row physically impossible.
-- ============================================================
CREATE TABLE notification_settings (
    id                           UUID        PRIMARY KEY
                                     CHECK (id = '00000000-0000-0000-0000-000000000001'),
    auto_send_instructor_emails  BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at                   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at                   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_by                   UUID        REFERENCES users(id) ON DELETE SET NULL,
    updated_by                   UUID        REFERENCES users(id) ON DELETE SET NULL
);

INSERT INTO notification_settings (id) VALUES ('00000000-0000-0000-0000-000000000001');

CREATE TRIGGER trg_notification_settings_updated_at
    BEFORE UPDATE ON notification_settings
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();