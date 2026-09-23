-- Output identity is independent of the reusable legacy undo cursor. No historical backfill.
ALTER TABLE schedule_run ADD CONSTRAINT uq_v36_run_org_task UNIQUE (org_id, exam_task_id, id);
CREATE TABLE demo_schedule_export_snapshot (
  workspace_id uuid NOT NULL,
  generation bigint NOT NULL,
  org_id uuid NOT NULL,
  task_id uuid NOT NULL,
  run_id uuid NOT NULL,
  export_revision bigint NOT NULL CHECK (export_revision >= 0),
  schema_version integer NOT NULL CHECK (schema_version = 1),
  snapshot_at timestamptz NOT NULL,
  payload jsonb NOT NULL CHECK (jsonb_typeof(payload) = 'object'),
  PRIMARY KEY (run_id, export_revision),
  FOREIGN KEY (workspace_id, generation, org_id) REFERENCES demo_generation(workspace_id, generation, org_id),
  FOREIGN KEY (org_id, task_id, run_id) REFERENCES schedule_run(org_id, exam_task_id, id) ON DELETE CASCADE
);
CREATE INDEX ix_demo_export_generation ON demo_schedule_export_snapshot(workspace_id,generation,org_id);
CREATE FUNCTION reject_demo_export_snapshot_update() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION 'Immutable demo export snapshot cannot be updated';
END;
$$;
CREATE TRIGGER demo_export_snapshot_immutable BEFORE UPDATE ON demo_schedule_export_snapshot
  FOR EACH ROW EXECUTE FUNCTION reject_demo_export_snapshot_update();
