-- FND-53 / RTM C7-AC4 — sending or dismissing an instructor's correction email (Run Review)
-- wrote no audit_event row, unlike every other admin action of this kind (accepting reference
-- data, resolving a duplicate, locking a cohort). NotificationDispatchService now records
-- NOTIFICATION_SENT / NOTIFICATION_DISMISSED; both need to be added to the allow-list.
ALTER TABLE audit_event
    DROP CONSTRAINT chk_audit_event_type;

ALTER TABLE audit_event
    ADD CONSTRAINT chk_audit_event_type CHECK (event_type IN (
        'LINK_SUBMITTED','GATE_FAILED','GATE_PASSED','REFERENCE_ACCEPTED',
        'DISCARD_RESET','COHORT_LOCKED','COHORT_UNLOCKED','STOOD_UP','CONFLICT_RESOLVED',
        'SYNC_COMPLETED','HIGH_FAILURE_RATE','COHORT_CREATED','CONFLICT_DISMISSED',
        'NOTIFICATION_SENT','NOTIFICATION_DISMISSED'
    ));
