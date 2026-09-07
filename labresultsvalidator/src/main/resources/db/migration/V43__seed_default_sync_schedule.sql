-- Deprecates the hardcoded "0 0 8 ? * MON" @Scheduled cron (CohortSyncScheduler, removed) in favor
-- of a DB-backed default row here (V43), applying to every eligible cohort (cohort_id IS NULL)
-- exactly as the old fixed job did. SyncScheduleService picks this up on startup (rehydrate()) and
-- via the normal /api/v1/sync-schedules CRUD endpoints, so the frontend can read and change it
-- instead of it being baked into app config.
INSERT INTO sync_schedules (id, name, cohort_id, frequency, time_of_day, day_of_week, timezone, enabled)
SELECT '00000000-0000-0000-0000-000000000001', 'Default weekly sync', NULL, 'WEEKLY', '08:00:00', 'MONDAY', 'GMT', true
WHERE NOT EXISTS (SELECT 1 FROM sync_schedules WHERE id = '00000000-0000-0000-0000-000000000001');
