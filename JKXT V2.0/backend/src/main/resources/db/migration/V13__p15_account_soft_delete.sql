-- 迭代11b P1: 账号"永久删除"此前只是 disable(行从不真删)，前端却谎称"永久删除、无法恢复"，且因
-- username/email 唯一约束，重建同名/同邮箱账号会撞唯一键冲突(500)。引入软删语义：
--   · 新增 deleted_at 标记(软删时间戳)；accounts() 过滤 deleted_at 非空的行；
--   · 软删时把该行 username/email 改写成带 id 后缀的墓碑值 —— 在**保留原有 (org_id,username)/(org_id,email)
--     唯一约束**的前提下释放原用户名/邮箱名额，可重建同名账号，而历史行(含审计 actor 引用)保留。
-- 注意：不改动/删除原唯一约束 —— 多处 seed/运行期 upsert 依赖 `ON CONFLICT (org_id, username)` 的约束推断，
-- 换成部分唯一索引会使这些 ON CONFLICT 失配报错(实测 P12 用例)。故仅加列，释放靠 delete 时改名实现。
ALTER TABLE app_user ADD COLUMN IF NOT EXISTS deleted_at timestamptz;
CREATE INDEX IF NOT EXISTS idx_app_user_deleted_at ON app_user(org_id, deleted_at);
