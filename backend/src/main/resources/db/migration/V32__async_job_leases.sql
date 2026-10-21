-- Durable ownership, not automatic replay. Existing unleased jobs are intentionally untouched.
ALTER TABLE exam_plan_planning_job ADD CONSTRAINT uq_v32_planning_job_tenant UNIQUE (org_id, id);
ALTER TABLE schedule_run ADD CONSTRAINT uq_v32_schedule_run_tenant UNIQUE (org_id, id);

CREATE TABLE async_job_lease (
  org_id uuid NOT NULL REFERENCES organization(id) ON DELETE CASCADE,
  job_kind varchar(16) NOT NULL CHECK (job_kind IN ('PLANNING','SCHEDULE')),
  job_id uuid NOT NULL,
  planning_job_id uuid,
  schedule_run_id uuid,
  owner_instance uuid NOT NULL,
  fence_token bigint NOT NULL DEFAULT 1 CHECK (fence_token > 0),
  phase varchar(12) NOT NULL CHECK (phase IN ('QUEUED','RUNNING','FINISHED','EXPIRED')),
  lease_until timestamptz NOT NULL,
  hard_deadline_at timestamptz NOT NULL,
  updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (org_id, job_kind, job_id),
  CHECK ((job_kind='PLANNING' AND planning_job_id=job_id AND planning_job_id IS NOT NULL AND schedule_run_id IS NULL)
      OR (job_kind='SCHEDULE' AND schedule_run_id=job_id AND schedule_run_id IS NOT NULL AND planning_job_id IS NULL)),
  FOREIGN KEY (org_id, planning_job_id) REFERENCES exam_plan_planning_job(org_id,id) ON DELETE CASCADE,
  FOREIGN KEY (org_id, schedule_run_id) REFERENCES schedule_run(org_id,id) ON DELETE CASCADE
);
CREATE INDEX idx_v32_expiring_jobs ON async_job_lease(job_kind, lease_until)
  WHERE phase IN ('QUEUED','RUNNING');

ALTER TABLE exam_plan_revision ADD COLUMN active_planning_job_id uuid;
ALTER TABLE exam_plan_revision ADD CONSTRAINT fk_v32_revision_active_job
  FOREIGN KEY (org_id, active_planning_job_id) REFERENCES exam_plan_planning_job(org_id,id);
