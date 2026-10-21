-- P10 reporting and platform admin foundations.

CREATE TABLE ip_rule (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  rule_type varchar(16) NOT NULL CHECK (rule_type IN ('ban', 'allow')),
  ip varchar(45) NOT NULL,
  reason varchar(300),
  status varchar(20) NOT NULL DEFAULT 'active' CHECK (status IN ('active', 'revoked')),
  created_by uuid REFERENCES app_user(id) ON DELETE SET NULL,
  payload jsonb NOT NULL DEFAULT '{}'::jsonb,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  version bigint NOT NULL DEFAULT 0,
  UNIQUE (rule_type, ip, status)
);
CREATE INDEX idx_ip_rule_type_status ON ip_rule(rule_type, status, created_at DESC);

CREATE TABLE security_event (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  ip varchar(45) NOT NULL,
  country varchar(80),
  attempts int NOT NULL DEFAULT 0 CHECK (attempts >= 0),
  severity varchar(12) NOT NULL DEFAULT 'low' CHECK (severity IN ('low', 'med', 'high')),
  last_at timestamptz NOT NULL DEFAULT now(),
  status varchar(20) NOT NULL DEFAULT 'open' CHECK (status IN ('open', 'resolved')),
  payload jsonb NOT NULL DEFAULT '{}'::jsonb,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  version bigint NOT NULL DEFAULT 0
);
CREATE INDEX idx_security_event_status ON security_event(status, severity, last_at DESC);

CREATE TABLE backup_record (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  backup_no varchar(40) NOT NULL UNIQUE,
  status varchar(20) NOT NULL DEFAULT 'succeeded' CHECK (status IN ('queued', 'running', 'succeeded', 'failed')),
  scope varchar(40) NOT NULL DEFAULT 'platform',
  storage varchar(80) NOT NULL DEFAULT 'local-snapshot',
  size_mb numeric(10,2) NOT NULL DEFAULT 0,
  checksum varchar(80),
  started_at timestamptz,
  finished_at timestamptz,
  created_by uuid REFERENCES app_user(id) ON DELETE SET NULL,
  payload jsonb NOT NULL DEFAULT '{}'::jsonb,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  version bigint NOT NULL DEFAULT 0
);
CREATE INDEX idx_backup_record_status ON backup_record(status, created_at DESC);

CREATE TABLE announcement (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  title varchar(240) NOT NULL,
  body text,
  severity varchar(20) NOT NULL DEFAULT 'info' CHECK (severity IN ('info', 'reminder', 'critical')),
  target varchar(80) NOT NULL DEFAULT 'all',
  status varchar(20) NOT NULL DEFAULT 'draft' CHECK (status IN ('draft', 'published', 'retracted')),
  publish_at timestamptz,
  expire_at date,
  read_by int NOT NULL DEFAULT 0 CHECK (read_by >= 0),
  created_by uuid REFERENCES app_user(id) ON DELETE SET NULL,
  payload jsonb NOT NULL DEFAULT '{}'::jsonb,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  version bigint NOT NULL DEFAULT 0
);
CREATE INDEX idx_announcement_status ON announcement(status, publish_at DESC);

UPDATE organization
SET name = 'Tianjin Experimental School',
    type = 'school',
    plan = 'premium',
    status = 'active',
    region = '{"country":"CN","province":"Tianjin","city":"Tianjin Heping"}'::jsonb,
    settings = settings || '{"abbr":"TSY","displayType":"Complete School","color":"#9333EA","students":2860,"adminCap":6}'::jsonb,
    updated_at = now()
WHERE id = '00000000-0000-0000-0000-000000000001';

INSERT INTO organization (id, name, slug, type, plan, status, region, contact_name, contact_phone, settings)
VALUES
  ('00000000-0000-0000-0000-000000010002','Tianjin Nankai School','nky','school','premium','active','{"country":"CN","province":"Tianjin","city":"Tianjin Nankai"}'::jsonb,'Nankai Admin','13800002002','{"abbr":"NK","displayType":"Complete School","color":"#4F46E5","students":2200,"adminCap":4}'::jsonb),
  ('00000000-0000-0000-0000-000000010003','Tianjin Yaohua Primary School','yh','school','standard','active','{"country":"CN","province":"Tianjin","city":"Tianjin Heping"}'::jsonb,'Yaohua Admin','13800002003','{"abbr":"YH","displayType":"Primary School","color":"#059669","students":1200,"adminCap":2}'::jsonb),
  ('00000000-0000-0000-0000-000000010004','Tianjin University Affiliated School','tg','school','private','active','{"country":"CN","province":"Tianjin","city":"Tianjin Hexi"}'::jsonb,'Affiliated Admin','13800002004','{"abbr":"TG","displayType":"Complete School","color":"#D97706","students":3100,"adminCap":5}'::jsonb),
  ('00000000-0000-0000-0000-000000010005','Tianjin Foreign Language Primary School','wjx','school','trial','active','{"country":"CN","province":"Tianjin","city":"Tianjin Heping"}'::jsonb,'WJX Admin','13800002005','{"abbr":"WJX","displayType":"Primary School","color":"#0EA5E9","students":960,"adminCap":2}'::jsonb),
  ('00000000-0000-0000-0000-000000010006','Tianjin Baodi No.1 School','bsd','school','standard','suspended','{"country":"CN","province":"Tianjin","city":"Tianjin Baodi"}'::jsonb,'Baodi Admin','13800002006','{"abbr":"BSD","displayType":"Middle School","color":"#DC2626","students":1800,"adminCap":3}'::jsonb),
  ('00000000-0000-0000-0000-000000010007','Tianjin Wuqing Foreign Language School','wq','school','standard','active','{"country":"CN","province":"Tianjin","city":"Tianjin Wuqing"}'::jsonb,'Wuqing Admin','13800002007','{"abbr":"WQ","displayType":"International School","color":"#7C3AED","students":1560,"adminCap":3}'::jsonb)
ON CONFLICT (slug) DO UPDATE SET
  name = EXCLUDED.name,
  type = EXCLUDED.type,
  plan = EXCLUDED.plan,
  status = EXCLUDED.status,
  region = EXCLUDED.region,
  contact_name = EXCLUDED.contact_name,
  contact_phone = EXCLUDED.contact_phone,
  settings = EXCLUDED.settings,
  updated_at = now();

INSERT INTO role (id, org_id, code, name, description, permissions, system_role)
VALUES
  ('00000000-0000-0000-0000-000000011002','00000000-0000-0000-0000-000000010002','ADMIN','Organization Admin','Seed admin role','["tenant:read","user:manage","masterdata:manage","exam:manage","schedule:manage"]'::jsonb,true),
  ('00000000-0000-0000-0000-000000011003','00000000-0000-0000-0000-000000010003','ADMIN','Organization Admin','Seed admin role','["tenant:read","user:manage","masterdata:manage","exam:manage","schedule:manage"]'::jsonb,true),
  ('00000000-0000-0000-0000-000000011004','00000000-0000-0000-0000-000000010004','ADMIN','Organization Admin','Seed admin role','["tenant:read","user:manage","masterdata:manage","exam:manage","schedule:manage"]'::jsonb,true),
  ('00000000-0000-0000-0000-000000011005','00000000-0000-0000-0000-000000010005','ADMIN','Organization Admin','Seed admin role','["tenant:read","user:manage","masterdata:manage","exam:manage","schedule:manage"]'::jsonb,true),
  ('00000000-0000-0000-0000-000000011006','00000000-0000-0000-0000-000000010006','ADMIN','Organization Admin','Seed admin role','["tenant:read","user:manage","masterdata:manage","exam:manage","schedule:manage"]'::jsonb,true),
  ('00000000-0000-0000-0000-000000011007','00000000-0000-0000-0000-000000010007','ADMIN','Organization Admin','Seed admin role','["tenant:read","user:manage","masterdata:manage","exam:manage","schedule:manage"]'::jsonb,true)
ON CONFLICT (org_id, code) DO UPDATE SET
  name = EXCLUDED.name,
  permissions = EXCLUDED.permissions,
  updated_at = now();

INSERT INTO app_user (
  id, org_id, username, display_name, email, phone, password_hash, role, status,
  must_change_password, onboarded, last_login_at, login_count
)
VALUES
  ('00000000-0000-0000-0000-000000012001','00000000-0000-0000-0000-000000000001','platform.super','Platform Super Admin','super@jkxt.local','13800009001','$2a$12$3WbJqB7QYkYpZ6bDL9xqgOL9GEpZ4/9s1rLJfHIGjZ2sJ6t9I6Q8a','SUPER_ADMIN','active',false,true,now() - interval '10 minutes',42),
  ('00000000-0000-0000-0000-000000012002','00000000-0000-0000-0000-000000010002','nky.admin','Nankai Admin','admin@nky.local','13800009002','$2a$12$3WbJqB7QYkYpZ6bDL9xqgOL9GEpZ4/9s1rLJfHIGjZ2sJ6t9I6Q8a','ADMIN','active',false,true,now() - interval '2 hours',18),
  ('00000000-0000-0000-0000-000000012003','00000000-0000-0000-0000-000000010003','yh.admin','Yaohua Admin','admin@yh.local','13800009003','$2a$12$3WbJqB7QYkYpZ6bDL9xqgOL9GEpZ4/9s1rLJfHIGjZ2sJ6t9I6Q8a','ADMIN','locked',true,true,now() - interval '1 day',7),
  ('00000000-0000-0000-0000-000000012004','00000000-0000-0000-0000-000000010004','tg.viewer','Affiliated Viewer','viewer@tg.local','13800009004','$2a$12$3WbJqB7QYkYpZ6bDL9xqgOL9GEpZ4/9s1rLJfHIGjZ2sJ6t9I6Q8a','VIEWER','active',false,true,now() - interval '4 hours',11),
  ('00000000-0000-0000-0000-000000012005','00000000-0000-0000-0000-000000010005','wjx.admin','Foreign Language Admin','admin@wjx.local','13800009005','$2a$12$3WbJqB7QYkYpZ6bDL9xqgOL9GEpZ4/9s1rLJfHIGjZ2sJ6t9I6Q8a','ADMIN','active',false,true,now() - interval '6 hours',3)
ON CONFLICT (org_id, username) DO UPDATE SET
  display_name = EXCLUDED.display_name,
  email = EXCLUDED.email,
  phone = EXCLUDED.phone,
  role = EXCLUDED.role,
  status = EXCLUDED.status,
  last_login_at = EXCLUDED.last_login_at,
  login_count = EXCLUDED.login_count,
  updated_at = now();

INSERT INTO exam_task (
  id, org_id, task_no, name, term_id, status, source, starts_on, ends_on, settings
)
VALUES (
  '00000000-0000-0000-0000-000000090001',
  '00000000-0000-0000-0000-000000000001',
  'P10-DEMO-REPORT',
  'P10 Demo Reporting Schedule',
  '00000000-0000-0000-0000-000000000301',
  'published',
  'quick',
  current_date - interval '2 days',
  current_date + interval '4 days',
  '{"source":"p10-seed"}'::jsonb
)
ON CONFLICT (org_id, task_no) DO UPDATE SET
  name = EXCLUDED.name,
  status = EXCLUDED.status,
  starts_on = EXCLUDED.starts_on,
  ends_on = EXCLUDED.ends_on,
  updated_at = now();

INSERT INTO schedule_run (
  id, org_id, exam_task_id, status, stage, progress, solver, started_at, finished_at, stats
)
VALUES (
  '00000000-0000-0000-0000-000000090002',
  '00000000-0000-0000-0000-000000000001',
  '00000000-0000-0000-0000-000000090001',
  'succeeded',
  'P10 reporting seed',
  100,
  'ortools-cpsat',
  now() - interval '30 minutes',
  now() - interval '25 minutes',
  '{"fairness":[{"label":"teacher","score":92},{"label":"room","score":89},{"label":"slot","score":94}],"source":"p10-seed"}'::jsonb
)
ON CONFLICT (id) DO UPDATE SET
  status = EXCLUDED.status,
  stage = EXCLUDED.stage,
  progress = EXCLUDED.progress,
  stats = EXCLUDED.stats,
  updated_at = now();

INSERT INTO exam (id, org_id, exam_task_id, subject_id, subject_name, exam_date, start_time, end_time, grade, duration_minutes, room_count)
VALUES
  ('00000000-0000-0000-0000-000000090011','00000000-0000-0000-0000-000000000001','00000000-0000-0000-0000-000000090001','00000000-0000-0000-0000-000000001001','Chinese',current_date - interval '2 days',time '08:00',time '10:00','G10',120,1),
  ('00000000-0000-0000-0000-000000090012','00000000-0000-0000-0000-000000000001','00000000-0000-0000-0000-000000090001','00000000-0000-0000-0000-000000001002','Math',current_date - interval '1 day',time '09:00',time '11:00','G10',120,1),
  ('00000000-0000-0000-0000-000000090013','00000000-0000-0000-0000-000000000001','00000000-0000-0000-0000-000000090001','00000000-0000-0000-0000-000000001003','English',current_date,time '10:30',time '12:00','G11',90,1),
  ('00000000-0000-0000-0000-000000090014','00000000-0000-0000-0000-000000000001','00000000-0000-0000-0000-000000090001','00000000-0000-0000-0000-000000001004','Physics',current_date,time '14:30',time '16:30','G11',120,1),
  ('00000000-0000-0000-0000-000000090015','00000000-0000-0000-0000-000000000001','00000000-0000-0000-0000-000000090001','00000000-0000-0000-0000-000000001005','History',current_date + interval '2 days',time '08:30',time '10:00','G09',90,1),
  ('00000000-0000-0000-0000-000000090016','00000000-0000-0000-0000-000000000001','00000000-0000-0000-0000-000000090001','00000000-0000-0000-0000-000000001006','IT Practice',current_date + interval '4 days',time '15:00',time '16:30','G10',90,1)
ON CONFLICT (id) DO UPDATE SET
  exam_date = EXCLUDED.exam_date,
  start_time = EXCLUDED.start_time,
  end_time = EXCLUDED.end_time,
  subject_name = EXCLUDED.subject_name,
  grade = EXCLUDED.grade,
  updated_at = now();

INSERT INTO schedule_assignment (id, org_id, schedule_run_id, exam_id, classroom_id, teacher_id, role, exam_date, start_time, end_time, source)
VALUES
  ('00000000-0000-0000-0000-000000091001','00000000-0000-0000-0000-000000000001','00000000-0000-0000-0000-000000090002','00000000-0000-0000-0000-000000090011','00000000-0000-0000-0000-000000002001','00000000-0000-0000-0000-000000004001','main',current_date - interval '2 days',time '08:00',time '10:00','solver'),
  ('00000000-0000-0000-0000-000000091002','00000000-0000-0000-0000-000000000001','00000000-0000-0000-0000-000000090002','00000000-0000-0000-0000-000000090011','00000000-0000-0000-0000-000000002001','00000000-0000-0000-0000-000000004002','assistant',current_date - interval '2 days',time '08:00',time '10:00','solver'),
  ('00000000-0000-0000-0000-000000091003','00000000-0000-0000-0000-000000000001','00000000-0000-0000-0000-000000090002','00000000-0000-0000-0000-000000090012','00000000-0000-0000-0000-000000002002','00000000-0000-0000-0000-000000004002','main',current_date - interval '1 day',time '09:00',time '11:00','solver'),
  ('00000000-0000-0000-0000-000000091004','00000000-0000-0000-0000-000000000001','00000000-0000-0000-0000-000000090002','00000000-0000-0000-0000-000000090012','00000000-0000-0000-0000-000000002002','00000000-0000-0000-0000-000000004005','assistant',current_date - interval '1 day',time '09:00',time '11:00','solver'),
  ('00000000-0000-0000-0000-000000091005','00000000-0000-0000-0000-000000000001','00000000-0000-0000-0000-000000090002','00000000-0000-0000-0000-000000090013','00000000-0000-0000-0000-000000002003','00000000-0000-0000-0000-000000004003','main',current_date,time '10:30',time '12:00','solver'),
  ('00000000-0000-0000-0000-000000091006','00000000-0000-0000-0000-000000000001','00000000-0000-0000-0000-000000090002','00000000-0000-0000-0000-000000090013','00000000-0000-0000-0000-000000002003','00000000-0000-0000-0000-000000004001','assistant',current_date,time '10:30',time '12:00','solver'),
  ('00000000-0000-0000-0000-000000091007','00000000-0000-0000-0000-000000000001','00000000-0000-0000-0000-000000090002','00000000-0000-0000-0000-000000090014','00000000-0000-0000-0000-000000002004','00000000-0000-0000-0000-000000004004','main',current_date,time '14:30',time '16:30','solver'),
  ('00000000-0000-0000-0000-000000091008','00000000-0000-0000-0000-000000000001','00000000-0000-0000-0000-000000090002','00000000-0000-0000-0000-000000090014','00000000-0000-0000-0000-000000002004','00000000-0000-0000-0000-000000004005','assistant',current_date,time '14:30',time '16:30','solver'),
  ('00000000-0000-0000-0000-000000091009','00000000-0000-0000-0000-000000000001','00000000-0000-0000-0000-000000090002','00000000-0000-0000-0000-000000090015','00000000-0000-0000-0000-000000002001','00000000-0000-0000-0000-000000004004','main',current_date + interval '2 days',time '08:30',time '10:00','solver'),
  ('00000000-0000-0000-0000-000000091010','00000000-0000-0000-0000-000000000001','00000000-0000-0000-0000-000000090002','00000000-0000-0000-0000-000000090015','00000000-0000-0000-0000-000000002001','00000000-0000-0000-0000-000000004003','assistant',current_date + interval '2 days',time '08:30',time '10:00','solver'),
  ('00000000-0000-0000-0000-000000091011','00000000-0000-0000-0000-000000000001','00000000-0000-0000-0000-000000090002','00000000-0000-0000-0000-000000090016','00000000-0000-0000-0000-000000002003','00000000-0000-0000-0000-000000004005','main',current_date + interval '4 days',time '15:00',time '16:30','solver'),
  ('00000000-0000-0000-0000-000000091012','00000000-0000-0000-0000-000000000001','00000000-0000-0000-0000-000000090002','00000000-0000-0000-0000-000000090016','00000000-0000-0000-0000-000000002003','00000000-0000-0000-0000-000000004001','assistant',current_date + interval '4 days',time '15:00',time '16:30','solver')
ON CONFLICT (schedule_run_id, exam_id, classroom_id, teacher_id) DO UPDATE SET
  exam_date = EXCLUDED.exam_date,
  start_time = EXCLUDED.start_time,
  end_time = EXCLUDED.end_time,
  source = EXCLUDED.source,
  updated_at = now();

INSERT INTO security_event (id, ip, country, attempts, severity, last_at, payload)
VALUES
  ('00000000-0000-0000-0000-000000092001','45.32.118.207','United States',8,'high',now() - interval '18 minutes','{"source":"failed-login"}'::jsonb),
  ('00000000-0000-0000-0000-000000092002','203.93.211.4','China',5,'med',now() - interval '2 hours','{"source":"password-retry"}'::jsonb),
  ('00000000-0000-0000-0000-000000092003','188.40.86.122','Germany',3,'low',now() - interval '2 days','{"source":"rate-limit"}'::jsonb)
ON CONFLICT (id) DO UPDATE SET
  attempts = EXCLUDED.attempts,
  severity = EXCLUDED.severity,
  last_at = EXCLUDED.last_at,
  payload = EXCLUDED.payload,
  updated_at = now();

INSERT INTO backup_record (id, backup_no, status, scope, storage, size_mb, checksum, started_at, finished_at, payload)
VALUES (
  '00000000-0000-0000-0000-000000093001',
  'BAK-P10-SEED',
  'succeeded',
  'platform',
  'local-snapshot',
  4200.00,
  'p10-seed-checksum',
  now() - interval '6 hours',
  now() - interval '5 hours 55 minutes',
  '{"source":"p10-seed"}'::jsonb
)
ON CONFLICT (backup_no) DO UPDATE SET
  status = EXCLUDED.status,
  size_mb = EXCLUDED.size_mb,
  finished_at = EXCLUDED.finished_at,
  updated_at = now();

INSERT INTO announcement (id, title, body, severity, target, status, publish_at, expire_at, read_by, payload)
VALUES
  ('00000000-0000-0000-0000-000000094001','v2.0.4 scheduling template update','New K-12 scheduling templates are available.','info','all','published',now() - interval '1 day',current_date + interval '30 days',156,'{"channels":["in-app","email"]}'::jsonb),
  ('00000000-0000-0000-0000-000000094002','Semester archive reminder','Please archive this semester before the end of month.','reminder','all','published',now() - interval '12 hours',current_date + interval '25 days',142,'{"channels":["in-app"]}'::jsonb),
  ('00000000-0000-0000-0000-000000094003','Enable two-factor authentication','All admins should enable 2FA.','critical','admins','draft',null,current_date + interval '20 days',0,'{"channels":["in-app","email"]}'::jsonb)
ON CONFLICT (id) DO UPDATE SET
  title = EXCLUDED.title,
  body = EXCLUDED.body,
  severity = EXCLUDED.severity,
  target = EXCLUDED.target,
  status = EXCLUDED.status,
  publish_at = EXCLUDED.publish_at,
  expire_at = EXCLUDED.expire_at,
  read_by = EXCLUDED.read_by,
  payload = EXCLUDED.payload,
  updated_at = now();
