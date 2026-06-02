-- ============================================================
-- LabGate — Seed Data
-- Repeatable Flyway Migration  (R__seed_data.sql)
-- ============================================================

-- ============================================================
-- 1. USERS
-- ============================================================

INSERT INTO users (
    id, email, password_hash, role,
    is_active, must_change_password, created_at, updated_at
) VALUES
      (
          '8fe168a4-a3ae-4b08-b631-4d1c9beb77c8',
          'superadmin@amalitech.com',
          '$2b$12$5uaYVku4G9BGz9CeEpv7FO3B7c9T9AGoYytMuahKng5xTnVsyg7Um',
          'admin',
          true, true, NOW(), NOW()
      ),
      (
          'ab43299b-b114-4de9-bbfa-89489cc6ee3b',
          'instructor.da@labgate.com',
          '$2b$12$z4dJ6747FPM4fElMORnVWOSRwKGU2efbJj/49G9iWmJ8Ecdf0cz0.',
          'instructor',
          true, true, NOW(), NOW()
      ),
      (
          'eefd8bd3-3dad-4d78-b250-94dbecc418a2',
          'instructor.swe@amalitech.com',
          '$2b$12$z4dJ6747FPM4fElMORnVWOSRwKGU2efbJj/49G9iWmJ8Ecdf0cz0.',
          'instructor',
          true, true, NOW(), NOW()
      )
ON CONFLICT (email) DO NOTHING;

-- ============================================================
-- 2. COHORTS
-- ============================================================

INSERT INTO cohorts (
    id, name, start_date, end_date,
    is_active, created_at, updated_at,
    created_by, updated_by
) VALUES (
             'c731a6c3-b407-45c5-ae9d-6f5f155eea11',
             'Cohort 1 — Spring 2026',
             '2026-01-15',
             '2026-06-30',
             true, NOW(), NOW(),
             '8fe168a4-a3ae-4b08-b631-4d1c9beb77c8',
             '8fe168a4-a3ae-4b08-b631-4d1c9beb77c8'
         )
ON CONFLICT (name) DO NOTHING;

-- ============================================================
-- 3. SPECIALIZATIONS
-- ============================================================

INSERT INTO specializations (
    id, cohort_id, name, code,
    created_at, updated_at, created_by, updated_by
) VALUES
      (
          '28574f7b-81af-4976-8643-81f9d4337256',
          'c731a6c3-b407-45c5-ae9d-6f5f155eea11',
          'Data Analytics', 'DA',
          NOW(), NOW(),
          '8fe168a4-a3ae-4b08-b631-4d1c9beb77c8',
          '8fe168a4-a3ae-4b08-b631-4d1c9beb77c8'
      ),
      (
          '48bb026e-7d3e-471b-97b4-19128a7ea497',
          'c731a6c3-b407-45c5-ae9d-6f5f155eea11',
          'Software Engineering', 'SWE',
          NOW(), NOW(),
          '8fe168a4-a3ae-4b08-b631-4d1c9beb77c8',
          '8fe168a4-a3ae-4b08-b631-4d1c9beb77c8'
      )
ON CONFLICT (cohort_id, name) DO NOTHING;

-- ============================================================
-- 4. MODULES
-- ============================================================

INSERT INTO modules (
    id, specialization_id, name, sequence,
    created_at, updated_at, created_by, updated_by
) VALUES
      (
          'b5a3fba0-77a4-49d0-9ae3-3a359c857b54',
          '28574f7b-81af-4976-8643-81f9d4337256',
          'Data Fundamentals', 1,
          NOW(), NOW(),
          '8fe168a4-a3ae-4b08-b631-4d1c9beb77c8',
          '8fe168a4-a3ae-4b08-b631-4d1c9beb77c8'
      ),
      (
          '15696a93-b783-4071-8f15-f74d5423e19b',
          '28574f7b-81af-4976-8643-81f9d4337256',
          'SQL & Databases', 2,
          NOW(), NOW(),
          '8fe168a4-a3ae-4b08-b631-4d1c9beb77c8',
          '8fe168a4-a3ae-4b08-b631-4d1c9beb77c8'
      ),
      (
          'eb5a27b9-a3ef-458b-a495-293ce385d624',
          '48bb026e-7d3e-471b-97b4-19128a7ea497',
          'Programming Fundamentals', 1,
          NOW(), NOW(),
          '8fe168a4-a3ae-4b08-b631-4d1c9beb77c8',
          '8fe168a4-a3ae-4b08-b631-4d1c9beb77c8'
      ),
      (
          '6761a8c8-00b2-4862-b222-6929de749edb',
          '48bb026e-7d3e-471b-97b4-19128a7ea497',
          'Web Development', 2,
          NOW(), NOW(),
          '8fe168a4-a3ae-4b08-b631-4d1c9beb77c8',
          '8fe168a4-a3ae-4b08-b631-4d1c9beb77c8'
      )
ON CONFLICT (specialization_id, name) DO NOTHING;

-- ============================================================
-- 5. LABS
-- ============================================================

INSERT INTO labs (
    id, module_id, title, max_score,
    is_immutable, created_at, updated_at, created_by, updated_by
) VALUES
-- Data Fundamentals
(
    '711d371d-5ad7-4fc8-ab15-d9f0ec5489d3',
    'b5a3fba0-77a4-49d0-9ae3-3a359c857b54',
    'Lab 1 — Intro to Data', 100.00,
    false, NOW(), NOW(),
    '8fe168a4-a3ae-4b08-b631-4d1c9beb77c8',
    '8fe168a4-a3ae-4b08-b631-4d1c9beb77c8'
),
(
    'd8c7cdb6-3cfc-40f1-b90d-03d9b2fa8f5b',
    'b5a3fba0-77a4-49d0-9ae3-3a359c857b54',
    'Lab 2 — Data Types & Structures', 100.00,
    false, NOW(), NOW(),
    '8fe168a4-a3ae-4b08-b631-4d1c9beb77c8',
    '8fe168a4-a3ae-4b08-b631-4d1c9beb77c8'
),
-- SQL & Databases
(
    'b6d7813c-57ae-444b-ad8a-c28743e84054',
    '15696a93-b783-4071-8f15-f74d5423e19b',
    'Lab 1 — Basic Queries', 100.00,
    false, NOW(), NOW(),
    '8fe168a4-a3ae-4b08-b631-4d1c9beb77c8',
    '8fe168a4-a3ae-4b08-b631-4d1c9beb77c8'
),
(
    '7f58bce5-a7cf-4db9-9b5d-757ea5ed2f1f',
    '15696a93-b783-4071-8f15-f74d5423e19b',
    'Lab 2 — Joins & Aggregations', 100.00,
    false, NOW(), NOW(),
    '8fe168a4-a3ae-4b08-b631-4d1c9beb77c8',
    '8fe168a4-a3ae-4b08-b631-4d1c9beb77c8'
),
-- Programming Fundamentals
(
    '5103bd52-14ed-425d-bd4b-bf3a2fd3e377',
    'eb5a27b9-a3ef-458b-a495-293ce385d624',
    'Lab 1 — Variables & Control Flow', 100.00,
    false, NOW(), NOW(),
    '8fe168a4-a3ae-4b08-b631-4d1c9beb77c8',
    '8fe168a4-a3ae-4b08-b631-4d1c9beb77c8'
),
(
    'c6869631-4bfb-46e6-ab56-8f1749a33fff',
    'eb5a27b9-a3ef-458b-a495-293ce385d624',
    'Lab 2 — Functions & Recursion', 100.00,
    false, NOW(), NOW(),
    '8fe168a4-a3ae-4b08-b631-4d1c9beb77c8',
    '8fe168a4-a3ae-4b08-b631-4d1c9beb77c8'
),
-- Web Development
(
    '68d2467b-eae5-44d7-b452-8d855f3e2a4c',
    '6761a8c8-00b2-4862-b222-6929de749edb',
    'Lab 1 — HTML & CSS Basics', 100.00,
    false, NOW(), NOW(),
    '8fe168a4-a3ae-4b08-b631-4d1c9beb77c8',
    '8fe168a4-a3ae-4b08-b631-4d1c9beb77c8'
),
(
    'c9c641a8-892d-4673-be36-0f8215ac348a',
    '6761a8c8-00b2-4862-b222-6929de749edb',
    'Lab 2 — REST APIs', 100.00,
    false, NOW(), NOW(),
    '8fe168a4-a3ae-4b08-b631-4d1c9beb77c8',
    '8fe168a4-a3ae-4b08-b631-4d1c9beb77c8'
)
ON CONFLICT (module_id, title) DO NOTHING;

-- ============================================================
-- 6. LEARNERS
-- ============================================================

INSERT INTO learners (
    id, full_name, email,
    cohort_id, specialization_id, status,
    created_at, updated_at, created_by, updated_by
) VALUES
-- Data Analytics learners
(
    'e710e527-dbf7-4960-b6e7-6c95dc6420b5',
    'Ama Owusu', 'ama.owusu@learner.labgate.com',
    'c731a6c3-b407-45c5-ae9d-6f5f155eea11',
    '28574f7b-81af-4976-8643-81f9d4337256',
    'active', NOW(), NOW(),
    '8fe168a4-a3ae-4b08-b631-4d1c9beb77c8',
    '8fe168a4-a3ae-4b08-b631-4d1c9beb77c8'
),
(
    'b5aa07d5-78db-454c-a784-810efc4aae9b',
    'Kwame Mensah', 'kwame.mensah@learner.labgate.com',
    'c731a6c3-b407-45c5-ae9d-6f5f155eea11',
    '28574f7b-81af-4976-8643-81f9d4337256',
    'active', NOW(), NOW(),
    '8fe168a4-a3ae-4b08-b631-4d1c9beb77c8',
    '8fe168a4-a3ae-4b08-b631-4d1c9beb77c8'
),
(
    '2c6ae9b5-9320-458d-a4a7-b949c53b9b57',
    'Abena Asante', 'abena.asante@learner.labgate.com',
    'c731a6c3-b407-45c5-ae9d-6f5f155eea11',
    '28574f7b-81af-4976-8643-81f9d4337256',
    'active', NOW(), NOW(),
    '8fe168a4-a3ae-4b08-b631-4d1c9beb77c8',
    '8fe168a4-a3ae-4b08-b631-4d1c9beb77c8'
),
(
    '5202136f-a746-4418-a804-890a21a29fd9',
    'Kofi Boateng', 'kofi.boateng@learner.labgate.com',
    'c731a6c3-b407-45c5-ae9d-6f5f155eea11',
    '28574f7b-81af-4976-8643-81f9d4337256',
    'active', NOW(), NOW(),
    '8fe168a4-a3ae-4b08-b631-4d1c9beb77c8',
    '8fe168a4-a3ae-4b08-b631-4d1c9beb77c8'
),
-- Software Engineering learners
(
    '3ae1982e-6800-4261-84cb-a83eb14f01e9',
    'Efua Darko', 'efua.darko@learner.labgate.com',
    'c731a6c3-b407-45c5-ae9d-6f5f155eea11',
    '48bb026e-7d3e-471b-97b4-19128a7ea497',
    'active', NOW(), NOW(),
    '8fe168a4-a3ae-4b08-b631-4d1c9beb77c8',
    '8fe168a4-a3ae-4b08-b631-4d1c9beb77c8'
),
(
    '30f76ea7-632a-4c88-951a-df2659620867',
    'Yaw Amponsah', 'yaw.amponsah@learner.labgate.com',
    'c731a6c3-b407-45c5-ae9d-6f5f155eea11',
    '48bb026e-7d3e-471b-97b4-19128a7ea497',
    'active', NOW(), NOW(),
    '8fe168a4-a3ae-4b08-b631-4d1c9beb77c8',
    '8fe168a4-a3ae-4b08-b631-4d1c9beb77c8'
),
(
    '5a7e54d3-7740-44f6-8cf3-ba368637cecf',
    'Akosua Frimpong', 'akosua.frimpong@learner.labgate.com',
    'c731a6c3-b407-45c5-ae9d-6f5f155eea11',
    '48bb026e-7d3e-471b-97b4-19128a7ea497',
    'active', NOW(), NOW(),
    '8fe168a4-a3ae-4b08-b631-4d1c9beb77c8',
    '8fe168a4-a3ae-4b08-b631-4d1c9beb77c8'
),
(
    'e2b6c354-34d0-4699-9a09-8f628dae937a',
    'Nana Adjei', 'nana.adjei@learner.labgate.com',
    'c731a6c3-b407-45c5-ae9d-6f5f155eea11',
    '48bb026e-7d3e-471b-97b4-19128a7ea497',
    'active', NOW(), NOW(),
    '8fe168a4-a3ae-4b08-b631-4d1c9beb77c8',
    '8fe168a4-a3ae-4b08-b631-4d1c9beb77c8'
)
ON CONFLICT (email) DO NOTHING;

-- ============================================================
-- 7. USER MODULE ASSIGNMENTS
-- instructor.da  → Data Fundamentals, SQL & Databases
-- instructor.swe → Programming Fundamentals, Web Development
-- ============================================================

INSERT INTO user_module_assignments (
    id, user_id, module_id, created_at, created_by
) VALUES
      (
          'f08569de-2283-4a35-9565-0043bd2fa5c2',
          'ab43299b-b114-4de9-bbfa-89489cc6ee3b',
          'b5a3fba0-77a4-49d0-9ae3-3a359c857b54',
          NOW(),
          '8fe168a4-a3ae-4b08-b631-4d1c9beb77c8'
      ),
      (
          'fec9acc6-92ec-42b0-bd25-9754c7516ae1',
          'ab43299b-b114-4de9-bbfa-89489cc6ee3b',
          '15696a93-b783-4071-8f15-f74d5423e19b',
          NOW(),
          '8fe168a4-a3ae-4b08-b631-4d1c9beb77c8'
      ),
      (
          '36335228-20c9-4c3e-b06d-5e91d12ba3a1',
          'eefd8bd3-3dad-4d78-b250-94dbecc418a2',
          'eb5a27b9-a3ef-458b-a495-293ce385d624',
          NOW(),
          '8fe168a4-a3ae-4b08-b631-4d1c9beb77c8'
      ),
      (
          '3644583a-2c47-43af-a5d4-b9d1211149c5',
          'eefd8bd3-3dad-4d78-b250-94dbecc418a2',
          '6761a8c8-00b2-4862-b222-6929de749edb',
          NOW(),
          '8fe168a4-a3ae-4b08-b631-4d1c9beb77c8'
      )
ON CONFLICT (user_id, module_id) DO NOTHING;

-- ============================================================
-- NOTE: csv_uploads, lab_results, and lab_reference_audit_log
-- are NOT seeded — they are populated through the application
-- workflow (CSV uploads by instructors). Seeding them directly
-- would bypass the validation pipeline the app is built around.
-- ============================================================

-- ============================================================
-- END OF SEED DATA
-- ============================================================