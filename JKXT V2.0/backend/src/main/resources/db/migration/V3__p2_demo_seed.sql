INSERT INTO organization (
  id, name, slug, type, plan, status, region, contact_name, contact_phone, settings
) VALUES (
  '00000000-0000-0000-0000-000000000001',
  'Demo School',
  'demo',
  'school',
  'trial',
  'active',
  '{"country":"CN","province":"Demo Province","city":"Demo City"}'::jsonb,
  'Demo Admin',
  '13800000000',
  '{"timezone":"Asia/Shanghai","allowanceUnit":120}'::jsonb
) ON CONFLICT (slug) DO UPDATE SET
  name = EXCLUDED.name,
  status = EXCLUDED.status,
  updated_at = now();

INSERT INTO role (
  id, org_id, code, name, description, permissions, system_role
) VALUES (
  '00000000-0000-0000-0000-000000000101',
  '00000000-0000-0000-0000-000000000001',
  'ADMIN',
  'Organization Admin',
  'Demo tenant administrator role',
  '["tenant:read","user:manage","masterdata:manage","exam:manage","schedule:manage"]'::jsonb,
  true
) ON CONFLICT (org_id, code) DO UPDATE SET
  name = EXCLUDED.name,
  permissions = EXCLUDED.permissions,
  updated_at = now();

INSERT INTO app_user (
  id, org_id, username, display_name, email, phone, password_hash, role, status,
  must_change_password, onboarded
) VALUES (
  '00000000-0000-0000-0000-000000000201',
  '00000000-0000-0000-0000-000000000001',
  'admin',
  'Demo Admin',
  'admin@demo.local',
  '13800000000',
  '$2a$12$3WbJqB7QYkYpZ6bDL9xqgOL9GEpZ4/9s1rLJfHIGjZ2sJ6t9I6Q8a',
  'ADMIN',
  'active',
  true,
  true
) ON CONFLICT (org_id, username) DO UPDATE SET
  display_name = EXCLUDED.display_name,
  email = EXCLUDED.email,
  role = EXCLUDED.role,
  status = EXCLUDED.status,
  updated_at = now();

INSERT INTO user_role (user_id, role_id, org_id)
VALUES (
  '00000000-0000-0000-0000-000000000201',
  '00000000-0000-0000-0000-000000000101',
  '00000000-0000-0000-0000-000000000001'
) ON CONFLICT (user_id, role_id) DO NOTHING;

INSERT INTO academic_term (
  id, org_id, year_label, term, name, start_date, end_date, is_current
) VALUES (
  '00000000-0000-0000-0000-000000000301',
  '00000000-0000-0000-0000-000000000001',
  '2025-2026',
  'S2',
  '2025-2026 Second Term',
  DATE '2026-02-17',
  DATE '2026-07-10',
  true
) ON CONFLICT (org_id, year_label, term) DO UPDATE SET
  name = EXCLUDED.name,
  start_date = EXCLUDED.start_date,
  end_date = EXCLUDED.end_date,
  is_current = EXCLUDED.is_current,
  updated_at = now();
