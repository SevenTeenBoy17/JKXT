-- P3 auth seed: keep P2 admin while adding UI demo usernames.
-- Password for all demo accounts: Demo@2025
UPDATE app_user
SET password_hash = '$2b$12$SkbPAZGpanpjY6ewziWG8O7ujU/.0NPTslISDTyi4DTbnBPeRX5R6',
    failed_attempts = 0,
    locked_until = NULL,
    updated_at = now()
WHERE org_id = '00000000-0000-0000-0000-000000000001'
  AND username = 'admin';

INSERT INTO role (
  id, org_id, code, name, description, permissions, system_role
) VALUES (
  '00000000-0000-0000-0000-000000000102',
  '00000000-0000-0000-0000-000000000001',
  'SUPER_ADMIN',
  'Platform Operator',
  'Demo platform operator role',
  '["platform:read","platform:manage","tenant:read","tenant:manage","audit:read","security:manage"]'::jsonb,
  true
) ON CONFLICT (org_id, code) DO UPDATE SET
  name = EXCLUDED.name,
  permissions = EXCLUDED.permissions,
  updated_at = now();

INSERT INTO app_user (
  id, org_id, username, display_name, email, phone, password_hash, role, status,
  must_change_password, onboarded
) VALUES (
  '00000000-0000-0000-0000-000000000202',
  '00000000-0000-0000-0000-000000000001',
  'jwc.demo',
  'Demo Academic Admin',
  'jwc.demo@demo.local',
  '13800000001',
  '$2b$12$SkbPAZGpanpjY6ewziWG8O7ujU/.0NPTslISDTyi4DTbnBPeRX5R6',
  'ADMIN',
  'active',
  false,
  true
) ON CONFLICT (org_id, username) DO UPDATE SET
  display_name = EXCLUDED.display_name,
  email = EXCLUDED.email,
  password_hash = EXCLUDED.password_hash,
  role = EXCLUDED.role,
  status = EXCLUDED.status,
  failed_attempts = 0,
  locked_until = NULL,
  updated_at = now();

INSERT INTO user_role (user_id, role_id, org_id)
VALUES (
  '00000000-0000-0000-0000-000000000202',
  '00000000-0000-0000-0000-000000000101',
  '00000000-0000-0000-0000-000000000001'
) ON CONFLICT (user_id, role_id) DO NOTHING;

INSERT INTO app_user (
  id, org_id, username, display_name, email, phone, password_hash, role, status,
  must_change_password, onboarded
) VALUES (
  '00000000-0000-0000-0000-000000000203',
  '00000000-0000-0000-0000-000000000001',
  'ops.demo',
  'Demo Platform Operator',
  'ops.demo@demo.local',
  '13800000002',
  '$2b$12$SkbPAZGpanpjY6ewziWG8O7ujU/.0NPTslISDTyi4DTbnBPeRX5R6',
  'SUPER_ADMIN',
  'active',
  false,
  true
) ON CONFLICT (org_id, username) DO UPDATE SET
  display_name = EXCLUDED.display_name,
  email = EXCLUDED.email,
  password_hash = EXCLUDED.password_hash,
  role = EXCLUDED.role,
  status = EXCLUDED.status,
  failed_attempts = 0,
  locked_until = NULL,
  updated_at = now();

INSERT INTO user_role (user_id, role_id, org_id)
VALUES (
  '00000000-0000-0000-0000-000000000203',
  '00000000-0000-0000-0000-000000000102',
  '00000000-0000-0000-0000-000000000001'
) ON CONFLICT (user_id, role_id) DO NOTHING;
