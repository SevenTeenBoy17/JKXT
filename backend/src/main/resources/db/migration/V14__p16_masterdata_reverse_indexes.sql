-- 上生产前加固 B1: teacher_subject / school_class_subject 此前仅有 (org_id) 索引（idx_teacher_subject_org /
-- idx_school_class_subject_org）。按 subject_id 反向查询——deleteSubject 清理学科关联、科目"被引用"计数、
-- 以及 toSubjectDto 的用量统计——在 org 内走顺序扫描。补 (org_id, subject_id) 复合索引，规模化（多科目 ×
-- 多教师/班级）下把这些反向查询降为索引扫描。纯增量：只加索引、绝不改变任何查询结果；IF NOT EXISTS 保证幂等。
CREATE INDEX IF NOT EXISTS idx_teacher_subject_subject
  ON teacher_subject (org_id, subject_id);
CREATE INDEX IF NOT EXISTS idx_school_class_subject_subject
  ON school_class_subject (org_id, subject_id);
