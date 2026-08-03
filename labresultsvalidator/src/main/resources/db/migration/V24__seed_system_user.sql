-- ============================================================
-- D1 AC3: scheduled runs need a real users(id) row to attribute
-- triggered_by/created_by/updated_by to, instead of leaving them NULL.
-- Inert placeholder: is_active=false means DaoAuthenticationProvider's
-- isEnabled() check rejects any login attempt outright.
-- ============================================================

INSERT INTO users (
    id, email, password_hash, role,
    is_active, must_change_password, created_at, updated_at
) VALUES (
    '00000000-0000-0000-0000-000000000001',
    'system@labgate.internal',
    'DISABLED-NO-LOGIN',
    'admin',
    false, true, NOW(), NOW()
)
ON CONFLICT (email) DO NOTHING;
