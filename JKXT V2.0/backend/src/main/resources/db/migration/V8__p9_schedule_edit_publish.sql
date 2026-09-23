ALTER TABLE schedule_run
  ADD COLUMN IF NOT EXISTS revision int NOT NULL DEFAULT 0,
  ADD COLUMN IF NOT EXISTS revision_cursor int NOT NULL DEFAULT 0;

CREATE TABLE schedule_revision (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  org_id uuid NOT NULL REFERENCES organization(id) ON DELETE CASCADE,
  exam_task_id uuid NOT NULL REFERENCES exam_task(id) ON DELETE CASCADE,
  schedule_run_id uuid NOT NULL REFERENCES schedule_run(id) ON DELETE CASCADE,
  revision_no int NOT NULL CHECK (revision_no > 0),
  action varchar(32) NOT NULL CHECK (action IN ('reassign', 'undo', 'redo')),
  assignment_id uuid NOT NULL REFERENCES schedule_assignment(id) ON DELETE CASCADE,
  before_state jsonb NOT NULL DEFAULT '{}'::jsonb,
  after_state jsonb NOT NULL DEFAULT '{}'::jsonb,
  status varchar(24) NOT NULL DEFAULT 'applied' CHECK (status IN ('applied', 'undone')),
  created_by uuid REFERENCES app_user(id) ON DELETE SET NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  version bigint NOT NULL DEFAULT 0,
  UNIQUE (org_id, schedule_run_id, revision_no)
);
CREATE INDEX idx_schedule_revision_run_cursor ON schedule_revision(org_id, schedule_run_id, revision_no DESC);
CREATE INDEX idx_schedule_revision_task ON schedule_revision(org_id, exam_task_id, created_at DESC);

CREATE TABLE notification (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  org_id uuid NOT NULL REFERENCES organization(id) ON DELETE CASCADE,
  exam_task_id uuid REFERENCES exam_task(id) ON DELETE CASCADE,
  type varchar(40) NOT NULL,
  title varchar(160) NOT NULL,
  body varchar(600),
  status varchar(24) NOT NULL DEFAULT 'queued' CHECK (status IN ('queued', 'sent', 'failed', 'read')),
  target_url varchar(240),
  payload jsonb NOT NULL DEFAULT '{}'::jsonb,
  published_at timestamptz,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  version bigint NOT NULL DEFAULT 0
);
CREATE INDEX idx_notification_org_status ON notification(org_id, status, created_at DESC);
CREATE INDEX idx_notification_task ON notification(org_id, exam_task_id);
