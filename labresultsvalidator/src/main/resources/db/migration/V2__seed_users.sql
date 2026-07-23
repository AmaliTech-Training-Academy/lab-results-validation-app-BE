-- ============================================================
-- LabGate v2 — Seed: Admin Users
-- Migrated from project B's super_admin records.
-- ============================================================

INSERT INTO users (
    id, email, password_hash, role,
    is_active, must_change_password, created_at, updated_at
) VALUES (
    '8fe168a4-a3ae-4b08-b631-4d1c9beb77c8',
    'superadmin@amalitech.com',
    '$2b$12$5uaYVku4G9BGz9CeEpv7FO3B7c9T9AGoYytMuahKng5xTnVsyg7Um',
    'admin',
    true, false, NOW(), NOW()
)
ON CONFLICT (email) DO NOTHING;
