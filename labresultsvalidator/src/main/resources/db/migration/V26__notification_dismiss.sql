-- ============================================================
-- notifications: track who/when dismissed a PENDING notification (transitions it to the
-- already-defined SKIPPED status) — mirrors ingestion_conflicts.resolved_by/resolved_at, the
-- same kind of manual-action audit trail. No chk_notif_status change needed: SKIPPED was already
-- an allowed value, just never set by any code path until now.
-- ============================================================
ALTER TABLE notifications
    ADD COLUMN dismissed_by UUID REFERENCES users(id) ON DELETE SET NULL,
    ADD COLUMN dismissed_at TIMESTAMPTZ;
