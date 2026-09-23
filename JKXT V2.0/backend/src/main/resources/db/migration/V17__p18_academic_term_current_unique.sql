-- P18 批次2：学期功能落地的数据前置。
-- 缺陷：「当前学期」全库无唯一性保证——V2 只建了普通索引 idx_academic_term_org_current(org_id, is_current)，
-- 表级唯一键仅 (org_id, year_label, term)；AcademicTermRepository.findByOrgIdAndCurrentTrue 返回 List，
-- AuthService 静默取第一条。一旦出现两条 is_current=true，"当前学期"就变成不确定值。
-- 即将开放的 POST /terms/{id}/activate 会频繁改写该标志，必须先有数据库级互斥兜底。

-- ① 先清脏：每个 org 只保留最近更新的那条 current，其余置 false（有则清，无则空转）。
UPDATE academic_term t
SET is_current = false, updated_at = now()
WHERE t.is_current
  AND t.id <> (
    SELECT x.id FROM academic_term x
    WHERE x.org_id = t.org_id AND x.is_current
    ORDER BY x.updated_at DESC, x.id
    LIMIT 1
  );

-- ② 部分唯一索引：每个 org 至多一条 is_current=true（允许零条——新租户尚未设定学期时不应被阻塞）。
CREATE UNIQUE INDEX IF NOT EXISTS uq_academic_term_org_current
  ON academic_term (org_id) WHERE is_current;
