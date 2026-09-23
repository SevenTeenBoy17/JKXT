-- V23 granted CHIEF to the full legacy roster to avoid invalidating already published duties.
-- Narrow that compatibility grant after historical evidence is available: teachers created before
-- V23 retain CHIEF only when they have actually held a CHIEF duty. Explicit grants made after V23
-- are not touched.
WITH v23 AS (
  SELECT installed_on
  FROM flyway_schema_history
  WHERE version = '23' AND success
  ORDER BY installed_rank DESC
  LIMIT 1
)
UPDATE teacher t
SET qualifications = t.qualifications - 'CHIEF',
    updated_at = now()
FROM v23
WHERE t.created_at <= v23.installed_on
  AND t.qualifications ? 'INVIGILATOR'
  AND t.qualifications ? 'CHIEF'
  AND NOT EXISTS (
    SELECT 1
    FROM exam_plan_duty_assignment a
    WHERE a.org_id = t.org_id
      AND a.teacher_id = t.id
      AND upper(a.duty_role) = 'CHIEF'
  );

-- Migration-time assertion: no unproven legacy CHIEF compatibility grants may remain.
DO $$
BEGIN
  IF EXISTS (
    SELECT 1
    FROM teacher t
    CROSS JOIN LATERAL (
      SELECT installed_on
      FROM flyway_schema_history
      WHERE version = '23' AND success
      ORDER BY installed_rank DESC
      LIMIT 1
    ) v23
    WHERE t.created_at <= v23.installed_on
      AND t.qualifications ? 'INVIGILATOR'
      AND t.qualifications ? 'CHIEF'
      AND NOT EXISTS (
        SELECT 1
        FROM exam_plan_duty_assignment a
        WHERE a.org_id = t.org_id
          AND a.teacher_id = t.id
          AND upper(a.duty_role) = 'CHIEF'
      )
  ) THEN
    RAISE EXCEPTION 'V24 failed to remove an unproven legacy CHIEF grant';
  END IF;
END $$;
