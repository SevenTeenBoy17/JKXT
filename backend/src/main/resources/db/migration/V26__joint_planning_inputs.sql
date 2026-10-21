-- Version-scoped, tenant-qualified input graph for full joint exam planning.

ALTER TABLE subject ADD CONSTRAINT uq_v26_subject_org_id UNIQUE (org_id, id);
ALTER TABLE school_class ADD CONSTRAINT uq_v26_school_class_org_id UNIQUE (org_id, id);

CREATE TABLE exam_plan_exam_demand (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  org_id uuid NOT NULL REFERENCES organization(id) ON DELETE CASCADE,
  revision_id uuid NOT NULL,
  demand_code varchar(64) NOT NULL CHECK (nullif(btrim(demand_code), '') IS NOT NULL),
  name varchar(160) NOT NULL CHECK (nullif(btrim(name), '') IS NOT NULL),
  subject_id uuid,
  duration_minutes int NOT NULL CHECK (duration_minutes > 0),
  candidate_count int NOT NULL DEFAULT 0 CHECK (candidate_count >= 0),
  invigilator_count int NOT NULL DEFAULT 1 CHECK (invigilator_count > 0),
  min_rooms int NOT NULL DEFAULT 1 CHECK (min_rooms > 0),
  max_rooms int NOT NULL DEFAULT 1 CHECK (max_rooms >= min_rooms),
  split_allowed boolean NOT NULL DEFAULT false,
  required_room_features jsonb NOT NULL DEFAULT '[]'::jsonb
    CHECK (jsonb_typeof(required_room_features) = 'array'),
  settings jsonb NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(settings) = 'object'),
  created_by uuid,
  updated_by uuid,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
  UNIQUE (org_id, revision_id, demand_code),
  UNIQUE (org_id, revision_id, id),
  FOREIGN KEY (org_id, revision_id) REFERENCES exam_plan_revision(org_id, id) ON DELETE CASCADE,
  FOREIGN KEY (org_id, subject_id) REFERENCES subject(org_id, id),
  FOREIGN KEY (org_id, created_by) REFERENCES app_user(org_id, id),
  FOREIGN KEY (org_id, updated_by) REFERENCES app_user(org_id, id)
);
CREATE INDEX idx_v26_demand_revision ON exam_plan_exam_demand(org_id, revision_id, demand_code);

CREATE TABLE exam_plan_demand_class (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  org_id uuid NOT NULL REFERENCES organization(id) ON DELETE CASCADE,
  revision_id uuid NOT NULL,
  demand_id uuid NOT NULL,
  class_id uuid NOT NULL,
  expected_candidates int NOT NULL DEFAULT 0 CHECK (expected_candidates >= 0),
  settings jsonb NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(settings) = 'object'),
  created_by uuid, updated_by uuid,
  created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
  version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
  UNIQUE (org_id, revision_id, demand_id, class_id),
  UNIQUE (org_id, revision_id, id),
  FOREIGN KEY (org_id, revision_id) REFERENCES exam_plan_revision(org_id, id) ON DELETE CASCADE,
  FOREIGN KEY (org_id, revision_id, demand_id)
    REFERENCES exam_plan_exam_demand(org_id, revision_id, id) ON DELETE CASCADE,
  FOREIGN KEY (org_id, class_id) REFERENCES school_class(org_id, id),
  FOREIGN KEY (org_id, created_by) REFERENCES app_user(org_id, id),
  FOREIGN KEY (org_id, updated_by) REFERENCES app_user(org_id, id)
);
CREATE INDEX idx_v26_demand_class_class ON exam_plan_demand_class(org_id, revision_id, class_id);

CREATE TABLE exam_plan_demand_window (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  org_id uuid NOT NULL REFERENCES organization(id) ON DELETE CASCADE,
  revision_id uuid NOT NULL,
  demand_id uuid NOT NULL,
  window_code varchar(64) NOT NULL CHECK (nullif(btrim(window_code), '') IS NOT NULL),
  starts_at timestamptz NOT NULL,
  ends_at timestamptz NOT NULL,
  preference_weight int NOT NULL DEFAULT 0 CHECK (preference_weight BETWEEN -1000 AND 1000),
  locked boolean NOT NULL DEFAULT false,
  settings jsonb NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(settings) = 'object'),
  created_by uuid, updated_by uuid,
  created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
  version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
  CHECK (ends_at > starts_at),
  UNIQUE (org_id, revision_id, demand_id, window_code),
  UNIQUE (org_id, revision_id, id),
  FOREIGN KEY (org_id, revision_id) REFERENCES exam_plan_revision(org_id, id) ON DELETE CASCADE,
  FOREIGN KEY (org_id, revision_id, demand_id)
    REFERENCES exam_plan_exam_demand(org_id, revision_id, id) ON DELETE CASCADE,
  FOREIGN KEY (org_id, created_by) REFERENCES app_user(org_id, id),
  FOREIGN KEY (org_id, updated_by) REFERENCES app_user(org_id, id)
);
CREATE INDEX idx_v26_demand_window_time ON exam_plan_demand_window(org_id, revision_id, starts_at, ends_at);

CREATE TABLE exam_plan_candidate (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  org_id uuid NOT NULL REFERENCES organization(id) ON DELETE CASCADE,
  revision_id uuid NOT NULL,
  candidate_code varchar(96) NOT NULL CHECK (nullif(btrim(candidate_code), '') IS NOT NULL),
  name varchar(120) NOT NULL CHECK (nullif(btrim(name), '') IS NOT NULL),
  class_id uuid,
  attributes jsonb NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(attributes) = 'object'),
  created_by uuid, updated_by uuid,
  created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
  version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
  UNIQUE (org_id, revision_id, candidate_code),
  UNIQUE (org_id, revision_id, id),
  FOREIGN KEY (org_id, revision_id) REFERENCES exam_plan_revision(org_id, id) ON DELETE CASCADE,
  FOREIGN KEY (org_id, class_id) REFERENCES school_class(org_id, id),
  FOREIGN KEY (org_id, created_by) REFERENCES app_user(org_id, id),
  FOREIGN KEY (org_id, updated_by) REFERENCES app_user(org_id, id)
);
CREATE INDEX idx_v26_candidate_class ON exam_plan_candidate(org_id, revision_id, class_id);

CREATE TABLE exam_plan_enrollment (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  org_id uuid NOT NULL REFERENCES organization(id) ON DELETE CASCADE,
  revision_id uuid NOT NULL,
  candidate_id uuid NOT NULL,
  demand_id uuid NOT NULL,
  status varchar(20) NOT NULL DEFAULT 'ENROLLED' CHECK (status IN ('ENROLLED','EXEMPT','CANCELLED')),
  settings jsonb NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(settings) = 'object'),
  created_by uuid, updated_by uuid,
  created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
  version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
  UNIQUE (org_id, revision_id, candidate_id, demand_id),
  UNIQUE (org_id, revision_id, id),
  FOREIGN KEY (org_id, revision_id) REFERENCES exam_plan_revision(org_id, id) ON DELETE CASCADE,
  FOREIGN KEY (org_id, revision_id, candidate_id)
    REFERENCES exam_plan_candidate(org_id, revision_id, id) ON DELETE CASCADE,
  FOREIGN KEY (org_id, revision_id, demand_id)
    REFERENCES exam_plan_exam_demand(org_id, revision_id, id) ON DELETE CASCADE,
  FOREIGN KEY (org_id, created_by) REFERENCES app_user(org_id, id),
  FOREIGN KEY (org_id, updated_by) REFERENCES app_user(org_id, id)
);
CREATE INDEX idx_v26_enrollment_demand ON exam_plan_enrollment(org_id, revision_id, demand_id, status);

CREATE TABLE exam_plan_candidate_window (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  org_id uuid NOT NULL REFERENCES organization(id) ON DELETE CASCADE,
  revision_id uuid NOT NULL,
  candidate_id uuid NOT NULL,
  window_type varchar(20) NOT NULL CHECK (window_type IN ('AVAILABLE','UNAVAILABLE')),
  starts_at timestamptz NOT NULL,
  ends_at timestamptz NOT NULL,
  reason varchar(500),
  settings jsonb NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(settings) = 'object'),
  created_by uuid, updated_by uuid,
  created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
  version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
  CHECK (ends_at > starts_at),
  UNIQUE (org_id, revision_id, candidate_id, window_type, starts_at, ends_at),
  UNIQUE (org_id, revision_id, id),
  FOREIGN KEY (org_id, revision_id) REFERENCES exam_plan_revision(org_id, id) ON DELETE CASCADE,
  FOREIGN KEY (org_id, revision_id, candidate_id)
    REFERENCES exam_plan_candidate(org_id, revision_id, id) ON DELETE CASCADE,
  FOREIGN KEY (org_id, created_by) REFERENCES app_user(org_id, id),
  FOREIGN KEY (org_id, updated_by) REFERENCES app_user(org_id, id)
);
CREATE INDEX idx_v26_candidate_window_time ON exam_plan_candidate_window(org_id, revision_id, starts_at, ends_at);

CREATE TABLE exam_plan_special_requirement (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  org_id uuid NOT NULL REFERENCES organization(id) ON DELETE CASCADE,
  revision_id uuid NOT NULL,
  candidate_id uuid NOT NULL,
  demand_id uuid,
  requirement_code varchar(64) NOT NULL CHECK (nullif(btrim(requirement_code), '') IS NOT NULL),
  requirement_type varchar(64) NOT NULL CHECK (nullif(btrim(requirement_type), '') IS NOT NULL),
  required_features jsonb NOT NULL DEFAULT '[]'::jsonb CHECK (jsonb_typeof(required_features) = 'array'),
  extra_minutes int NOT NULL DEFAULT 0 CHECK (extra_minutes >= 0),
  separate_room boolean NOT NULL DEFAULT false,
  details jsonb NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(details) = 'object'),
  created_by uuid, updated_by uuid,
  created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
  version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
  UNIQUE (org_id, revision_id, id),
  FOREIGN KEY (org_id, revision_id) REFERENCES exam_plan_revision(org_id, id) ON DELETE CASCADE,
  FOREIGN KEY (org_id, revision_id, candidate_id)
    REFERENCES exam_plan_candidate(org_id, revision_id, id) ON DELETE CASCADE,
  FOREIGN KEY (org_id, revision_id, demand_id)
    REFERENCES exam_plan_exam_demand(org_id, revision_id, id) ON DELETE CASCADE,
  FOREIGN KEY (org_id, created_by) REFERENCES app_user(org_id, id),
  FOREIGN KEY (org_id, updated_by) REFERENCES app_user(org_id, id)
);
CREATE UNIQUE INDEX uq_v26_special_requirement_natural
  ON exam_plan_special_requirement(org_id, revision_id, candidate_id, coalesce(demand_id, '00000000-0000-0000-0000-000000000000'::uuid), requirement_code);

CREATE TABLE exam_plan_room_pool (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  org_id uuid NOT NULL REFERENCES organization(id) ON DELETE CASCADE,
  revision_id uuid NOT NULL,
  classroom_id uuid NOT NULL,
  campus_code varchar(120) NOT NULL CHECK (nullif(btrim(campus_code), '') IS NOT NULL),
  exam_capacity int NOT NULL CHECK (exam_capacity > 0),
  features jsonb NOT NULL DEFAULT '[]'::jsonb CHECK (jsonb_typeof(features) = 'array'),
  status varchar(20) NOT NULL DEFAULT 'INCLUDED' CHECK (status IN ('INCLUDED','EXCLUDED','LOCKED')),
  settings jsonb NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(settings) = 'object'),
  created_by uuid, updated_by uuid,
  created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
  version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
  UNIQUE (org_id, revision_id, classroom_id),
  UNIQUE (org_id, revision_id, id),
  FOREIGN KEY (org_id, revision_id) REFERENCES exam_plan_revision(org_id, id) ON DELETE CASCADE,
  FOREIGN KEY (org_id, classroom_id) REFERENCES classroom(org_id, id),
  FOREIGN KEY (org_id, created_by) REFERENCES app_user(org_id, id),
  FOREIGN KEY (org_id, updated_by) REFERENCES app_user(org_id, id)
);
CREATE INDEX idx_v26_room_pool_campus ON exam_plan_room_pool(org_id, revision_id, campus_code, status);

CREATE TABLE exam_plan_room_window (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  org_id uuid NOT NULL REFERENCES organization(id) ON DELETE CASCADE,
  revision_id uuid NOT NULL,
  room_pool_id uuid NOT NULL,
  window_type varchar(20) NOT NULL CHECK (window_type IN ('AVAILABLE','UNAVAILABLE')),
  starts_at timestamptz NOT NULL,
  ends_at timestamptz NOT NULL,
  reason varchar(500),
  settings jsonb NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(settings) = 'object'),
  created_by uuid, updated_by uuid,
  created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
  version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
  CHECK (ends_at > starts_at),
  UNIQUE (org_id, revision_id, room_pool_id, window_type, starts_at, ends_at),
  UNIQUE (org_id, revision_id, id),
  FOREIGN KEY (org_id, revision_id) REFERENCES exam_plan_revision(org_id, id) ON DELETE CASCADE,
  FOREIGN KEY (org_id, revision_id, room_pool_id)
    REFERENCES exam_plan_room_pool(org_id, revision_id, id) ON DELETE CASCADE,
  FOREIGN KEY (org_id, created_by) REFERENCES app_user(org_id, id),
  FOREIGN KEY (org_id, updated_by) REFERENCES app_user(org_id, id)
);
CREATE INDEX idx_v26_room_window_time ON exam_plan_room_window(org_id, revision_id, starts_at, ends_at);

CREATE TABLE exam_plan_room_seat (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  org_id uuid NOT NULL REFERENCES organization(id) ON DELETE CASCADE,
  revision_id uuid NOT NULL,
  room_pool_id uuid NOT NULL,
  seat_code varchar(48) NOT NULL CHECK (nullif(btrim(seat_code), '') IS NOT NULL),
  row_no int CHECK (row_no IS NULL OR row_no > 0),
  column_no int CHECK (column_no IS NULL OR column_no > 0),
  features jsonb NOT NULL DEFAULT '[]'::jsonb CHECK (jsonb_typeof(features) = 'array'),
  status varchar(20) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','DISABLED')),
  created_by uuid, updated_by uuid,
  created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
  version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
  UNIQUE (org_id, revision_id, room_pool_id, seat_code),
  UNIQUE (org_id, revision_id, id),
  FOREIGN KEY (org_id, revision_id) REFERENCES exam_plan_revision(org_id, id) ON DELETE CASCADE,
  FOREIGN KEY (org_id, revision_id, room_pool_id)
    REFERENCES exam_plan_room_pool(org_id, revision_id, id) ON DELETE CASCADE,
  FOREIGN KEY (org_id, created_by) REFERENCES app_user(org_id, id),
  FOREIGN KEY (org_id, updated_by) REFERENCES app_user(org_id, id)
);
CREATE UNIQUE INDEX uq_v26_room_seat_grid
  ON exam_plan_room_seat(org_id, revision_id, room_pool_id, row_no, column_no)
  WHERE row_no IS NOT NULL AND column_no IS NOT NULL;

CREATE TABLE exam_plan_teacher_pool (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  org_id uuid NOT NULL REFERENCES organization(id) ON DELETE CASCADE,
  revision_id uuid NOT NULL,
  teacher_id uuid NOT NULL,
  eligible_roles jsonb NOT NULL DEFAULT '["INVIGILATOR"]'::jsonb
    CHECK (jsonb_typeof(eligible_roles) = 'array' AND jsonb_array_length(eligible_roles) > 0),
  max_daily_sessions int NOT NULL DEFAULT 4 CHECK (max_daily_sessions BETWEEN 1 AND 12),
  min_rest_minutes int NOT NULL DEFAULT 0 CHECK (min_rest_minutes >= 0),
  preferred_campus varchar(120),
  status varchar(20) NOT NULL DEFAULT 'INCLUDED' CHECK (status IN ('INCLUDED','EXCLUDED','RESERVE')),
  preferences jsonb NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(preferences) = 'object'),
  created_by uuid, updated_by uuid,
  created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
  version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
  CHECK (preferred_campus IS NULL OR nullif(btrim(preferred_campus), '') IS NOT NULL),
  UNIQUE (org_id, revision_id, teacher_id),
  UNIQUE (org_id, revision_id, id),
  FOREIGN KEY (org_id, revision_id) REFERENCES exam_plan_revision(org_id, id) ON DELETE CASCADE,
  FOREIGN KEY (org_id, teacher_id) REFERENCES teacher(org_id, id),
  FOREIGN KEY (org_id, created_by) REFERENCES app_user(org_id, id),
  FOREIGN KEY (org_id, updated_by) REFERENCES app_user(org_id, id)
);
CREATE INDEX idx_v26_teacher_pool_status ON exam_plan_teacher_pool(org_id, revision_id, status);

CREATE TABLE exam_plan_teacher_avoidance (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  org_id uuid NOT NULL REFERENCES organization(id) ON DELETE CASCADE,
  revision_id uuid NOT NULL,
  teacher_pool_id uuid NOT NULL,
  target_type varchar(20) NOT NULL CHECK (target_type IN ('DEMAND','CLASS','CANDIDATE')),
  demand_id uuid,
  class_id uuid,
  candidate_id uuid,
  severity varchar(12) NOT NULL DEFAULT 'HARD' CHECK (severity IN ('HARD','SOFT')),
  reason varchar(500) NOT NULL CHECK (nullif(btrim(reason), '') IS NOT NULL),
  details jsonb NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(details) = 'object'),
  created_by uuid, updated_by uuid,
  created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
  version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
  CHECK (num_nonnulls(demand_id, class_id, candidate_id) = 1),
  CHECK ((target_type = 'DEMAND' AND demand_id IS NOT NULL)
      OR (target_type = 'CLASS' AND class_id IS NOT NULL)
      OR (target_type = 'CANDIDATE' AND candidate_id IS NOT NULL)),
  UNIQUE (org_id, revision_id, id),
  FOREIGN KEY (org_id, revision_id) REFERENCES exam_plan_revision(org_id, id) ON DELETE CASCADE,
  FOREIGN KEY (org_id, revision_id, teacher_pool_id)
    REFERENCES exam_plan_teacher_pool(org_id, revision_id, id) ON DELETE CASCADE,
  FOREIGN KEY (org_id, revision_id, demand_id)
    REFERENCES exam_plan_exam_demand(org_id, revision_id, id) ON DELETE CASCADE,
  FOREIGN KEY (org_id, class_id) REFERENCES school_class(org_id, id),
  FOREIGN KEY (org_id, revision_id, candidate_id)
    REFERENCES exam_plan_candidate(org_id, revision_id, id) ON DELETE CASCADE,
  FOREIGN KEY (org_id, created_by) REFERENCES app_user(org_id, id),
  FOREIGN KEY (org_id, updated_by) REFERENCES app_user(org_id, id)
);
CREATE UNIQUE INDEX uq_v26_teacher_avoidance_natural
  ON exam_plan_teacher_avoidance(org_id, revision_id, teacher_pool_id, target_type,
    coalesce(demand_id, '00000000-0000-0000-0000-000000000000'::uuid),
    coalesce(class_id, '00000000-0000-0000-0000-000000000000'::uuid),
    coalesce(candidate_id, '00000000-0000-0000-0000-000000000000'::uuid), severity);

CREATE TABLE exam_plan_campus_travel (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  org_id uuid NOT NULL REFERENCES organization(id) ON DELETE CASCADE,
  revision_id uuid NOT NULL,
  from_campus varchar(120) NOT NULL CHECK (nullif(btrim(from_campus), '') IS NOT NULL),
  to_campus varchar(120) NOT NULL CHECK (nullif(btrim(to_campus), '') IS NOT NULL),
  travel_minutes int NOT NULL CHECK (travel_minutes > 0),
  buffer_minutes int NOT NULL DEFAULT 0 CHECK (buffer_minutes >= 0),
  settings jsonb NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(settings) = 'object'),
  created_by uuid, updated_by uuid,
  created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
  version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
  CHECK (btrim(from_campus) <> btrim(to_campus)),
  UNIQUE (org_id, revision_id, from_campus, to_campus),
  UNIQUE (org_id, revision_id, id),
  FOREIGN KEY (org_id, revision_id) REFERENCES exam_plan_revision(org_id, id) ON DELETE CASCADE,
  FOREIGN KEY (org_id, created_by) REFERENCES app_user(org_id, id),
  FOREIGN KEY (org_id, updated_by) REFERENCES app_user(org_id, id)
);
CREATE INDEX idx_v26_campus_travel_revision ON exam_plan_campus_travel(org_id, revision_id);
