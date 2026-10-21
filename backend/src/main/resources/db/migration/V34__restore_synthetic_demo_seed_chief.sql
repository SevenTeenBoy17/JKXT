-- PB-2 (2026-10-18). V24 removed the V23 compatibility CHIEF grant from every teacher created
-- before V23 that never held a CHIEF duty. That correctly narrowed real legacy rosters, but it also
-- stripped CHIEF from the synthetic V5 demo roster (organization 00000000-...-0001, *@demo.local),
-- so the shipped sample exam (2 supervisors per room = 1 CHIEF + 1 INVIGILATOR) became
-- unschedulable (H2_CHIEF_QUALIFICATION).
--
-- V24 is applied history and is NOT edited (checksum). This forward migration re-grants CHIEF only
-- to active synthetic demo seed rows that are still exactly the V5 seed identity (id, organization,
-- teacher_no, seed e-mail and phone) and still carry exactly the post-V24 default ["INVIGILATOR"]
-- with no qualification expiry. Rows a school re-imported, edited or explicitly re-qualified, the
-- inactive seed row, and every non-seed teacher are left untouched. Idempotent.
UPDATE teacher t
SET qualifications = '["INVIGILATOR","CHIEF"]'::jsonb,
    updated_at = now()
FROM (VALUES
  ('00000000-0000-0000-0000-000000004001'::uuid, 'T2026001', 'wang.yn@demo.local', '13800001001'),
  ('00000000-0000-0000-0000-000000004002'::uuid, 'T2026002', 'li.my@demo.local',   '13800001002'),
  ('00000000-0000-0000-0000-000000004003'::uuid, 'T2026003', 'chen.sy@demo.local', '13800001003'),
  ('00000000-0000-0000-0000-000000004004'::uuid, 'T2026004', 'zhao.qh@demo.local', '13800001004'),
  ('00000000-0000-0000-0000-000000004005'::uuid, 'T2026005', 'zhou.xh@demo.local', '13800001005')
) AS seed(id, teacher_no, email, phone)
WHERE t.id = seed.id
  AND t.org_id = '00000000-0000-0000-0000-000000000001'::uuid
  AND t.teacher_no = seed.teacher_no
  AND t.email = seed.email
  AND t.phone = seed.phone
  AND t.status = 'active'
  AND t.qualifications = '["INVIGILATOR"]'::jsonb
  AND t.qualification_expiry IS NULL;