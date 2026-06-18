CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE organization (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  name varchar(120) NOT NULL,
  slug varchar(60) NOT NULL UNIQUE,
  type varchar(20) NOT NULL DEFAULT 'school' CHECK (type IN ('school', 'group', 'platform')),
  plan varchar(20) NOT NULL DEFAULT 'trial' CHECK (plan IN ('trial', 'standard', 'premium', 'private')),
  status varchar(20) NOT NULL DEFAULT 'active' CHECK (status IN ('active', 'suspended', 'archived')),
  region jsonb NOT NULL DEFAULT '{}'::jsonb,
  contact_name varchar(60),
  contact_phone varchar(30),
  settings jsonb NOT NULL DEFAULT '{}'::jsonb,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  version bigint NOT NULL DEFAULT 0
);

CREATE TABLE role (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  org_id uuid REFERENCES organization(id) ON DELETE CASCADE,
  code varchar(40) NOT NULL,
  name varchar(80) NOT NULL,
  description varchar(240),
  permissions jsonb NOT NULL DEFAULT '[]'::jsonb,
  system_role boolean NOT NULL DEFAULT false,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  version bigint NOT NULL DEFAULT 0,
  UNIQUE (org_id, code)
);
CREATE INDEX idx_role_org ON role(org_id);

CREATE TABLE app_user (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  org_id uuid REFERENCES organization(id) ON DELETE CASCADE,
  username varchar(60) NOT NULL,
  display_name varchar(80) NOT NULL,
  email varchar(120),
  phone varchar(30),
  password_hash varchar(120) NOT NULL,
  role varchar(24) NOT NULL CHECK (role IN ('SUPER_ADMIN', 'ADMIN', 'VIEWER', 'TEACHER_QUERY')),
  status varchar(20) NOT NULL DEFAULT 'active' CHECK (status IN ('active', 'disabled', 'locked')),
  must_change_password boolean NOT NULL DEFAULT true,
  onboarded boolean NOT NULL DEFAULT false,
  failed_attempts int NOT NULL DEFAULT 0,
  locked_until timestamptz,
  last_login_at timestamptz,
  last_login_ip varchar(45),
  login_count int NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  created_by uuid,
  version bigint NOT NULL DEFAULT 0,
  UNIQUE (org_id, username),
  UNIQUE (org_id, email)
);
CREATE INDEX idx_app_user_org ON app_user(org_id);
CREATE INDEX idx_app_user_status ON app_user(org_id, status);

CREATE TABLE user_role (
  user_id uuid NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
  role_id uuid NOT NULL REFERENCES role(id) ON DELETE CASCADE,
  org_id uuid NOT NULL REFERENCES organization(id) ON DELETE CASCADE,
  created_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (user_id, role_id)
);
CREATE INDEX idx_user_role_org ON user_role(org_id);

CREATE TABLE academic_term (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  org_id uuid NOT NULL REFERENCES organization(id) ON DELETE CASCADE,
  year_label varchar(20) NOT NULL,
  term varchar(4) NOT NULL CHECK (term IN ('S1', 'S2')),
  name varchar(60) NOT NULL,
  start_date date NOT NULL,
  end_date date NOT NULL,
  is_current boolean NOT NULL DEFAULT false,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  version bigint NOT NULL DEFAULT 0,
  UNIQUE (org_id, year_label, term)
);
CREATE INDEX idx_academic_term_org_current ON academic_term(org_id, is_current);

CREATE TABLE subject (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  org_id uuid NOT NULL REFERENCES organization(id) ON DELETE CASCADE,
  code varchar(30),
  name varchar(40) NOT NULL,
  category varchar(24) NOT NULL DEFAULT 'main' CHECK (category IN ('main', 'secondary', 'activity')),
  exam_type varchar(24) NOT NULL DEFAULT 'written' CHECK (exam_type IN ('written', 'oral', 'practical', 'other')),
  hours numeric(5,2),
  color varchar(20),
  status varchar(20) NOT NULL DEFAULT 'active' CHECK (status IN ('active', 'disabled')),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  version bigint NOT NULL DEFAULT 0,
  UNIQUE (org_id, name),
  UNIQUE (org_id, code)
);
CREATE INDEX idx_subject_org ON subject(org_id);

CREATE TABLE classroom (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  org_id uuid NOT NULL REFERENCES organization(id) ON DELETE CASCADE,
  code varchar(30) NOT NULL,
  name varchar(80) NOT NULL,
  building varchar(60),
  floor varchar(20),
  capacity int NOT NULL DEFAULT 0 CHECK (capacity >= 0),
  exam_capacity int NOT NULL DEFAULT 0 CHECK (exam_capacity >= 0),
  room_type varchar(24) NOT NULL DEFAULT 'standard' CHECK (room_type IN ('standard', 'corridor', 'lab', 'meeting', 'other')),
  features jsonb NOT NULL DEFAULT '[]'::jsonb,
  status varchar(20) NOT NULL DEFAULT 'active' CHECK (status IN ('active', 'disabled', 'maintenance')),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  version bigint NOT NULL DEFAULT 0,
  UNIQUE (org_id, code)
);
CREATE INDEX idx_classroom_org_status ON classroom(org_id, status);

CREATE TABLE school_class (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  org_id uuid NOT NULL REFERENCES organization(id) ON DELETE CASCADE,
  code varchar(30) NOT NULL,
  name varchar(80) NOT NULL,
  grade varchar(30) NOT NULL,
  stage varchar(20),
  student_count int NOT NULL DEFAULT 0 CHECK (student_count >= 0),
  homeroom_teacher_id uuid,
  default_classroom_id uuid REFERENCES classroom(id),
  status varchar(20) NOT NULL DEFAULT 'active' CHECK (status IN ('active', 'disabled', 'graduated')),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  version bigint NOT NULL DEFAULT 0,
  UNIQUE (org_id, code)
);
CREATE INDEX idx_school_class_org_grade ON school_class(org_id, grade);

CREATE TABLE teacher (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  org_id uuid NOT NULL REFERENCES organization(id) ON DELETE CASCADE,
  teacher_no varchar(30) NOT NULL,
  name varchar(80) NOT NULL,
  gender varchar(12) NOT NULL CHECK (gender IN ('male', 'female', 'unknown')),
  grade varchar(30),
  phone varchar(30),
  email varchar(120),
  status varchar(20) NOT NULL DEFAULT 'active' CHECK (status IN ('active', 'disabled', 'left')),
  is_homeroom boolean NOT NULL DEFAULT false,
  homeroom_class_id uuid REFERENCES school_class(id),
  availability jsonb NOT NULL DEFAULT '{}'::jsonb,
  tags jsonb NOT NULL DEFAULT '[]'::jsonb,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  version bigint NOT NULL DEFAULT 0,
  UNIQUE (org_id, teacher_no),
  UNIQUE (org_id, email)
);
CREATE INDEX idx_teacher_org_grade ON teacher(org_id, grade);
CREATE INDEX idx_teacher_org_status ON teacher(org_id, status);

ALTER TABLE school_class
  ADD CONSTRAINT fk_school_class_homeroom_teacher
  FOREIGN KEY (homeroom_teacher_id) REFERENCES teacher(id);

CREATE TABLE teacher_subject (
  teacher_id uuid NOT NULL REFERENCES teacher(id) ON DELETE CASCADE,
  subject_id uuid NOT NULL REFERENCES subject(id) ON DELETE CASCADE,
  org_id uuid NOT NULL REFERENCES organization(id) ON DELETE CASCADE,
  created_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (teacher_id, subject_id)
);
CREATE INDEX idx_teacher_subject_org ON teacher_subject(org_id);

CREATE TABLE school_class_subject (
  class_id uuid NOT NULL REFERENCES school_class(id) ON DELETE CASCADE,
  subject_id uuid NOT NULL REFERENCES subject(id) ON DELETE CASCADE,
  org_id uuid NOT NULL REFERENCES organization(id) ON DELETE CASCADE,
  created_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (class_id, subject_id)
);
CREATE INDEX idx_school_class_subject_org ON school_class_subject(org_id);

CREATE TABLE exam_task (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  org_id uuid NOT NULL REFERENCES organization(id) ON DELETE CASCADE,
  task_no varchar(40) NOT NULL,
  name varchar(120) NOT NULL,
  term_id uuid REFERENCES academic_term(id),
  status varchar(24) NOT NULL DEFAULT 'draft' CHECK (status IN ('draft', 'importing', 'ready', 'scheduling', 'scheduled', 'published', 'archived', 'failed')),
  owner_id uuid REFERENCES app_user(id),
  starts_on date,
  ends_on date,
  source varchar(24) NOT NULL DEFAULT 'manual' CHECK (source IN ('manual', 'quick', 'ai_policy', 'import')),
  settings jsonb NOT NULL DEFAULT '{}'::jsonb,
  published_at timestamptz,
  archived_at timestamptz,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  version bigint NOT NULL DEFAULT 0,
  UNIQUE (org_id, task_no)
);
CREATE INDEX idx_exam_task_org_status ON exam_task(org_id, status);
CREATE INDEX idx_exam_task_term ON exam_task(org_id, term_id);

CREATE TABLE exam (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  org_id uuid NOT NULL REFERENCES organization(id) ON DELETE CASCADE,
  exam_task_id uuid NOT NULL REFERENCES exam_task(id) ON DELETE CASCADE,
  subject_id uuid REFERENCES subject(id),
  subject_name varchar(40) NOT NULL,
  exam_date date NOT NULL,
  start_time time NOT NULL,
  end_time time NOT NULL,
  grade varchar(30),
  duration_minutes int NOT NULL DEFAULT 0 CHECK (duration_minutes >= 0),
  room_count int NOT NULL DEFAULT 0 CHECK (room_count >= 0),
  settings jsonb NOT NULL DEFAULT '{}'::jsonb,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  version bigint NOT NULL DEFAULT 0
);
CREATE INDEX idx_exam_task ON exam(exam_task_id);
CREATE INDEX idx_exam_org_date ON exam(org_id, exam_date);

CREATE TABLE exam_class (
  exam_id uuid NOT NULL REFERENCES exam(id) ON DELETE CASCADE,
  class_id uuid NOT NULL REFERENCES school_class(id),
  org_id uuid NOT NULL REFERENCES organization(id) ON DELETE CASCADE,
  PRIMARY KEY (exam_id, class_id)
);
CREATE INDEX idx_exam_class_org ON exam_class(org_id);

CREATE TABLE exam_room (
  exam_id uuid NOT NULL REFERENCES exam(id) ON DELETE CASCADE,
  classroom_id uuid NOT NULL REFERENCES classroom(id),
  org_id uuid NOT NULL REFERENCES organization(id) ON DELETE CASCADE,
  PRIMARY KEY (exam_id, classroom_id)
);
CREATE INDEX idx_exam_room_org ON exam_room(org_id);

CREATE TABLE uploaded_file (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  org_id uuid NOT NULL REFERENCES organization(id) ON DELETE CASCADE,
  exam_task_id uuid REFERENCES exam_task(id) ON DELETE CASCADE,
  kind varchar(24) NOT NULL CHECK (kind IN ('teacher', 'classroom', 'exam', 'other')),
  original_name varchar(240) NOT NULL,
  storage_key varchar(300) NOT NULL,
  content_type varchar(120),
  size_bytes bigint NOT NULL DEFAULT 0 CHECK (size_bytes >= 0),
  sha256 varchar(80),
  uploaded_by uuid REFERENCES app_user(id),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  version bigint NOT NULL DEFAULT 0
);
CREATE INDEX idx_uploaded_file_org_task ON uploaded_file(org_id, exam_task_id);
CREATE INDEX idx_uploaded_file_sha ON uploaded_file(org_id, sha256);

CREATE TABLE import_batch (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  org_id uuid NOT NULL REFERENCES organization(id) ON DELETE CASCADE,
  exam_task_id uuid REFERENCES exam_task(id) ON DELETE CASCADE,
  uploaded_file_id uuid REFERENCES uploaded_file(id) ON DELETE SET NULL,
  kind varchar(24) NOT NULL CHECK (kind IN ('teacher', 'classroom', 'exam', 'mixed')),
  status varchar(24) NOT NULL DEFAULT 'uploaded' CHECK (status IN ('uploaded', 'validating', 'validated', 'committed', 'failed')),
  row_count int NOT NULL DEFAULT 0 CHECK (row_count >= 0),
  valid_count int NOT NULL DEFAULT 0 CHECK (valid_count >= 0),
  error_count int NOT NULL DEFAULT 0 CHECK (error_count >= 0),
  summary jsonb NOT NULL DEFAULT '{}'::jsonb,
  created_by uuid REFERENCES app_user(id),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  version bigint NOT NULL DEFAULT 0
);
CREATE INDEX idx_import_batch_org_task ON import_batch(org_id, exam_task_id);
CREATE INDEX idx_import_batch_status ON import_batch(org_id, status);

CREATE TABLE validation_issue (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  import_batch_id uuid NOT NULL REFERENCES import_batch(id) ON DELETE CASCADE,
  org_id uuid NOT NULL REFERENCES organization(id) ON DELETE CASCADE,
  row_no int,
  column_name varchar(80),
  severity varchar(12) NOT NULL DEFAULT 'error' CHECK (severity IN ('info', 'warning', 'error')),
  code varchar(60) NOT NULL,
  message varchar(500) NOT NULL,
  raw_value varchar(500),
  suggestion jsonb NOT NULL DEFAULT '{}'::jsonb,
  created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_validation_issue_batch ON validation_issue(import_batch_id);
CREATE INDEX idx_validation_issue_org ON validation_issue(org_id);

CREATE TABLE schedule_run (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  org_id uuid NOT NULL REFERENCES organization(id) ON DELETE CASCADE,
  exam_task_id uuid NOT NULL REFERENCES exam_task(id) ON DELETE CASCADE,
  status varchar(24) NOT NULL DEFAULT 'queued' CHECK (status IN ('queued', 'running', 'succeeded', 'failed', 'fallback', 'cancelled')),
  stage varchar(80),
  progress int NOT NULL DEFAULT 0 CHECK (progress >= 0 AND progress <= 100),
  solver varchar(40) NOT NULL DEFAULT 'ortools-cpsat',
  seed bigint,
  started_at timestamptz,
  finished_at timestamptz,
  error_code varchar(80),
  error_message varchar(500),
  stats jsonb NOT NULL DEFAULT '{}'::jsonb,
  logs jsonb NOT NULL DEFAULT '[]'::jsonb,
  created_by uuid REFERENCES app_user(id),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  version bigint NOT NULL DEFAULT 0
);
CREATE INDEX idx_schedule_run_org_task ON schedule_run(org_id, exam_task_id);
CREATE INDEX idx_schedule_run_status ON schedule_run(org_id, status);

CREATE TABLE schedule_assignment (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  org_id uuid NOT NULL REFERENCES organization(id) ON DELETE CASCADE,
  schedule_run_id uuid NOT NULL REFERENCES schedule_run(id) ON DELETE CASCADE,
  exam_id uuid NOT NULL REFERENCES exam(id) ON DELETE CASCADE,
  classroom_id uuid NOT NULL REFERENCES classroom(id),
  teacher_id uuid NOT NULL REFERENCES teacher(id),
  role varchar(12) NOT NULL CHECK (role IN ('main', 'assistant')),
  exam_date date NOT NULL,
  start_time time NOT NULL,
  end_time time NOT NULL,
  conflict jsonb,
  source varchar(24) NOT NULL DEFAULT 'solver' CHECK (source IN ('solver', 'manual', 'ai_suggestion')),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  version bigint NOT NULL DEFAULT 0,
  UNIQUE (schedule_run_id, exam_id, classroom_id, teacher_id)
);
CREATE INDEX idx_assignment_run ON schedule_assignment(schedule_run_id);
CREATE INDEX idx_assignment_teacher ON schedule_assignment(org_id, teacher_id, exam_date);
CREATE INDEX idx_assignment_room ON schedule_assignment(org_id, classroom_id, exam_date);

CREATE TABLE audit_log (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  org_id uuid REFERENCES organization(id) ON DELETE SET NULL,
  actor_id uuid REFERENCES app_user(id) ON DELETE SET NULL,
  action varchar(80) NOT NULL,
  target_type varchar(80),
  target_id uuid,
  severity varchar(12) NOT NULL DEFAULT 'info' CHECK (severity IN ('info', 'warn', 'high')),
  ip varchar(45),
  user_agent varchar(300),
  payload jsonb NOT NULL DEFAULT '{}'::jsonb,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  version bigint NOT NULL DEFAULT 0
);
CREATE INDEX idx_audit_org_time ON audit_log(org_id, created_at DESC);
CREATE INDEX idx_audit_actor_time ON audit_log(actor_id, created_at DESC);
CREATE INDEX idx_audit_severity ON audit_log(severity, created_at DESC);

CREATE TABLE ai_call_log (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  org_id uuid NOT NULL REFERENCES organization(id) ON DELETE CASCADE,
  exam_task_id uuid REFERENCES exam_task(id) ON DELETE SET NULL,
  schedule_run_id uuid REFERENCES schedule_run(id) ON DELETE SET NULL,
  provider varchar(60) NOT NULL,
  model varchar(80),
  purpose varchar(40) NOT NULL CHECK (purpose IN ('nl2dsl', 'summary', 'repair', 'explain', 'other')),
  prompt_version varchar(40),
  input_hash varchar(80),
  status varchar(24) NOT NULL DEFAULT 'pending' CHECK (status IN ('pending', 'succeeded', 'failed', 'fallback')),
  latency_ms int,
  token_usage jsonb NOT NULL DEFAULT '{}'::jsonb,
  cost_cents numeric(12,4),
  request_meta jsonb NOT NULL DEFAULT '{}'::jsonb,
  response_meta jsonb NOT NULL DEFAULT '{}'::jsonb,
  error_message varchar(500),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  version bigint NOT NULL DEFAULT 0
);
CREATE INDEX idx_ai_call_org_time ON ai_call_log(org_id, created_at DESC);
CREATE INDEX idx_ai_call_task ON ai_call_log(org_id, exam_task_id);
