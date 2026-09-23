-- Additive bridge from the unchanged /exam-tasks contract to the immutable exam-plan graph.
-- The bridge stores only provenance/cursors; projected rows remain in the existing version tables.

ALTER TABLE schedule_run
  ADD CONSTRAINT uq_v30_schedule_run_org_id UNIQUE (org_id, id);

CREATE TABLE legacy_exam_task_plan_link (
  org_id uuid NOT NULL REFERENCES organization(id) ON DELETE CASCADE,
  exam_task_id uuid NOT NULL,
  plan_id uuid NOT NULL,
  active_revision_id uuid NOT NULL,
  source_fingerprint varchar(64),
  projected_run_id uuid,
  projected_run_revision_cursor bigint,
  published_history boolean NOT NULL DEFAULT false,
  projected_at timestamptz NOT NULL DEFAULT now(),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
  PRIMARY KEY (org_id, exam_task_id),
  UNIQUE (org_id, plan_id),
  CHECK (source_fingerprint IS NULL OR source_fingerprint ~ '^[0-9a-f]{64}$'),
  CHECK (projected_run_revision_cursor IS NULL OR projected_run_revision_cursor >= 0),
  CHECK ((projected_run_id IS NULL) = (projected_run_revision_cursor IS NULL)),
  FOREIGN KEY (org_id, exam_task_id) REFERENCES exam_task(org_id, id) ON DELETE CASCADE,
  FOREIGN KEY (org_id, plan_id) REFERENCES exam_plan(org_id, id) ON DELETE CASCADE,
  FOREIGN KEY (org_id, plan_id, active_revision_id)
    REFERENCES exam_plan_revision(org_id, plan_id, id),
  FOREIGN KEY (org_id, projected_run_id) REFERENCES schedule_run(org_id, id) ON DELETE SET NULL
);

CREATE INDEX idx_v30_legacy_link_revision
  ON legacy_exam_task_plan_link(org_id, active_revision_id);
CREATE INDEX idx_v30_legacy_link_run
  ON legacy_exam_task_plan_link(org_id, projected_run_id)
  WHERE projected_run_id IS NOT NULL;

-- V19 already created exam_plan.legacy_exam_task_id. Adopt those rows without fabricating a
-- fingerprint: the first lazy read recomputes the canonical source and safely refreshes it.
INSERT INTO legacy_exam_task_plan_link (
  org_id, exam_task_id, plan_id, active_revision_id, source_fingerprint,
  projected_run_id, projected_run_revision_cursor, published_history
)
SELECT p.org_id, p.legacy_exam_task_id, p.id, p.active_revision_id, NULL, NULL, NULL,
       (r.status IN ('PUBLISHED','EXECUTING','CLOSED','ARCHIVED'))
FROM exam_plan p
JOIN exam_plan_revision r
  ON r.org_id=p.org_id AND r.plan_id=p.id AND r.id=p.active_revision_id
WHERE p.legacy_exam_task_id IS NOT NULL
ON CONFLICT (org_id, exam_task_id) DO NOTHING;
