-- P13 demo login accounts for local/private UAT.
-- Super admin: admin / Super001!!!
-- Tenant test admin: tenant.test / Tenant001!!!

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
  description = EXCLUDED.description,
  permissions = EXCLUDED.permissions,
  system_role = EXCLUDED.system_role,
  updated_at = now();

INSERT INTO app_user (
  id, org_id, username, display_name, email, phone, password_hash, role, status,
  must_change_password, onboarded, failed_attempts, locked_until
) VALUES (
  '00000000-0000-0000-0000-000000000201',
  '00000000-0000-0000-0000-000000000001',
  'admin',
  'Platform Super Admin',
  'admin@jkxt.local',
  '13800000000',
  '$2b$12$cotX6lNbAiladOyyfmhokesk7z/WoDqGNSlNCXEDA1LiC3qqkyZXG',
  'SUPER_ADMIN',
  'active',
  false,
  true,
  0,
  NULL
) ON CONFLICT (org_id, username) DO UPDATE SET
  display_name = EXCLUDED.display_name,
  email = EXCLUDED.email,
  phone = EXCLUDED.phone,
  password_hash = EXCLUDED.password_hash,
  role = EXCLUDED.role,
  status = EXCLUDED.status,
  must_change_password = EXCLUDED.must_change_password,
  onboarded = EXCLUDED.onboarded,
  failed_attempts = 0,
  locked_until = NULL,
  updated_at = now();

DELETE FROM user_role
WHERE user_id = '00000000-0000-0000-0000-000000000201'
  AND org_id = '00000000-0000-0000-0000-000000000001';

INSERT INTO user_role (user_id, role_id, org_id)
VALUES (
  '00000000-0000-0000-0000-000000000201',
  '00000000-0000-0000-0000-000000000102',
  '00000000-0000-0000-0000-000000000001'
) ON CONFLICT (user_id, role_id) DO NOTHING;

INSERT INTO app_user (
  id, org_id, username, display_name, email, phone, password_hash, role, status,
  must_change_password, onboarded, failed_attempts, locked_until
) VALUES (
  '00000000-0000-0000-0000-000000000204',
  '00000000-0000-0000-0000-000000000001',
  'tenant.test',
  'Tenant Test Admin',
  'tenant.test@demo.local',
  '13800000003',
  '$2b$12$Xg0JZbLncsCxx9FpDIl.o.gST8QjLQGOJ59G9HedlKNiQ3zIKJIq6',
  'ADMIN',
  'active',
  false,
  true,
  0,
  NULL
) ON CONFLICT (org_id, username) DO UPDATE SET
  display_name = EXCLUDED.display_name,
  email = EXCLUDED.email,
  phone = EXCLUDED.phone,
  password_hash = EXCLUDED.password_hash,
  role = EXCLUDED.role,
  status = EXCLUDED.status,
  must_change_password = EXCLUDED.must_change_password,
  onboarded = EXCLUDED.onboarded,
  failed_attempts = 0,
  locked_until = NULL,
  updated_at = now();

INSERT INTO user_role (user_id, role_id, org_id)
VALUES (
  '00000000-0000-0000-0000-000000000204',
  '00000000-0000-0000-0000-000000000101',
  '00000000-0000-0000-0000-000000000001'
) ON CONFLICT (user_id, role_id) DO NOTHING;
