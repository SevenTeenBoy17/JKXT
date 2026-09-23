-- P17 A1（细粒度 permissions 落地·数据准备）
-- 背景：登录时按 role.permissions 逐个授予 authority，但此前 role 表只有 SUPER_ADMIN/ADMIN 行——
-- VIEWER / TEACHER_QUERY 无行 → permissions 解析为空 → 一旦把读接口改成 hasAuthority(...) 会被全挡。
-- 本迁移把权限模型补齐，使 SecurityConfig 得以按能力(permission)而非仅角色(role)鉴权：
--   ① 为每个 org 建 VIEWER / TEACHER_QUERY 系统角色并赋 tenant:read（= 现状的只读访问，改为权限背书）。
--   ② 按产品决策给 SUPER_ADMIN 补业务写权限（masterdata/exam/schedule:manage），使超管可代管租户业务写
--      （此前 SUPER_ADMIN 靠 ROLE_SUPER_ADMIN 已能写，改为权限门后须显式持有，故这里补齐以保持零回归）。
-- 幂等：ON CONFLICT / 集合去重合并，可重复执行。system role 权限集固定，不随 org 变化。

-- ① 只读角色：为所有 org 建 VIEWER / TEACHER_QUERY（tenant:read）。
--   注：TEACHER_QUERY 暂与 VIEWER 同为全 org 只读（= 迁移前经 authenticated 的读范围，零回归）；
--   若日后产品要把它收窄为「仅查本人监考」，再单独调整其 permissions 即可，不影响本迁移结构。
INSERT INTO role (id, org_id, code, name, description, permissions, system_role)
SELECT gen_random_uuid(), o.id, 'VIEWER', '只读', 'System read-only role', '["tenant:read"]'::jsonb, true
FROM organization o
ON CONFLICT (org_id, code) DO UPDATE SET
  permissions = EXCLUDED.permissions,
  system_role = true,
  updated_at = now();

INSERT INTO role (id, org_id, code, name, description, permissions, system_role)
SELECT gen_random_uuid(), o.id, 'TEACHER_QUERY', '教师查询', 'System teacher-query role', '["tenant:read"]'::jsonb, true
FROM organization o
ON CONFLICT (org_id, code) DO UPDATE SET
  permissions = EXCLUDED.permissions,
  system_role = true,
  updated_at = now();

-- ② 给所有 SUPER_ADMIN 角色补业务写权限，保留其原有平台权限；去重合并，幂等。
UPDATE role
SET permissions = (
      SELECT jsonb_agg(DISTINCT p)
      FROM jsonb_array_elements_text(
        permissions || '["masterdata:manage","exam:manage","schedule:manage"]'::jsonb
      ) AS p
    ),
    updated_at = now()
WHERE code = 'SUPER_ADMIN';
