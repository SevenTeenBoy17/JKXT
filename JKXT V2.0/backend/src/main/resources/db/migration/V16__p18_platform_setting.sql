-- P18 修复 P0：超管「系统设置」保存是假的。
-- 缺陷：AdminPlatformService.platformSettings() 返回硬编码 Map，savePlatformSettings() 只 audit + 原样回显，
-- **不写任何库**——全库 31 张表中不存在 platform_setting 或任何 KV 配置表（对抗验证实证），
-- 即持久化在数据模型层就不存在。UI 却弹「配置已下发,审计已记录」，刷新即回退。
-- 本迁移补上持久化载体：平台级单例配置（不含 org_id——这是跨租户的平台运营配置，非租户主数据）。
CREATE TABLE IF NOT EXISTS platform_setting (
  key         varchar(64) PRIMARY KEY,
  value       jsonb       NOT NULL,
  updated_at  timestamptz NOT NULL DEFAULT now(),
  updated_by  uuid
);

-- 种子当前代码里的硬编码默认值，保证升级后首次 GET 的返回与旧行为一致（零感知迁移）。
INSERT INTO platform_setting (key, value) VALUES
  ('emailNotif',  'true'::jsonb),
  ('twoFactor',   'true'::jsonb),
  ('guestAccess', 'false'::jsonb),
  ('autoBackup',  'true'::jsonb),
  ('maintenance', 'false'::jsonb)
ON CONFLICT (key) DO NOTHING;
