-- Extend the legacy-compatible result graph with traceable JOINT output references.

ALTER TABLE exam_plan_session
  ADD COLUMN demand_id uuid,
  ADD COLUMN source_type varchar(20) NOT NULL DEFAULT 'LEGACY',
  ADD CONSTRAINT chk_v27_session_source CHECK (source_type IN ('LEGACY','IMPORT','MANUAL','JOINT')),
  ADD CONSTRAINT chk_v27_joint_session_complete CHECK (source_type <> 'JOINT' OR demand_id IS NOT NULL),
  ADD CONSTRAINT fk_v27_session_demand
    FOREIGN KEY (org_id, revision_id, demand_id)
    REFERENCES exam_plan_exam_demand(org_id, revision_id, id);

ALTER TABLE exam_plan_seat_assignment
  ADD COLUMN candidate_id uuid,
  ADD COLUMN seat_id uuid,
  ADD COLUMN starts_at timestamptz,
  ADD COLUMN ends_at timestamptz,
  ADD COLUMN source_type varchar(20) NOT NULL DEFAULT 'LEGACY',
  ADD CONSTRAINT chk_v27_seat_source CHECK (source_type IN ('LEGACY','IMPORT','MANUAL','JOINT')),
  ADD CONSTRAINT chk_v27_seat_time_pair CHECK ((starts_at IS NULL) = (ends_at IS NULL)),
  ADD CONSTRAINT chk_v27_seat_time_order CHECK (ends_at IS NULL OR ends_at > starts_at),
  ADD CONSTRAINT chk_v27_joint_seat_complete CHECK (
    source_type <> 'JOINT' OR
    (candidate_id IS NOT NULL AND seat_id IS NOT NULL AND starts_at IS NOT NULL AND ends_at IS NOT NULL)
  ),
  ADD CONSTRAINT fk_v27_seat_candidate
    FOREIGN KEY (org_id, revision_id, candidate_id)
    REFERENCES exam_plan_candidate(org_id, revision_id, id),
  ADD CONSTRAINT fk_v27_seat_topology
    FOREIGN KEY (org_id, revision_id, seat_id)
    REFERENCES exam_plan_room_seat(org_id, revision_id, id);

CREATE UNIQUE INDEX uq_v27_session_candidate
  ON exam_plan_seat_assignment(org_id, revision_id, session_id, candidate_id)
  WHERE candidate_id IS NOT NULL AND status <> 'CANCELLED';
CREATE UNIQUE INDEX uq_v27_session_topology_seat
  ON exam_plan_seat_assignment(org_id, revision_id, session_id, seat_id)
  WHERE seat_id IS NOT NULL AND status <> 'CANCELLED';
ALTER TABLE exam_plan_seat_assignment
  ADD CONSTRAINT ex_v27_candidate_time_per_revision EXCLUDE USING gist (
    org_id WITH =,
    revision_id WITH =,
    candidate_id WITH =,
    tstzrange(starts_at, ends_at, '[)') WITH &&
  ) WHERE (status <> 'CANCELLED' AND candidate_id IS NOT NULL AND starts_at IS NOT NULL AND ends_at IS NOT NULL);

CREATE TABLE exam_plan_class_session_assignment (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  org_id uuid NOT NULL REFERENCES organization(id) ON DELETE CASCADE,
  revision_id uuid NOT NULL,
  session_id uuid NOT NULL,
  demand_id uuid NOT NULL,
  demand_class_id uuid NOT NULL,
  class_id uuid NOT NULL,
  starts_at timestamptz NOT NULL,
  ends_at timestamptz NOT NULL,
  source_type varchar(20) NOT NULL DEFAULT 'JOINT'
    CHECK (source_type IN ('IMPORT','MANUAL','JOINT')),
  status varchar(20) NOT NULL DEFAULT 'ASSIGNED' CHECK (status IN ('ASSIGNED','CANCELLED')),
  created_by uuid,
  updated_by uuid,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
  CHECK (ends_at > starts_at),
  UNIQUE (org_id, revision_id, session_id, class_id),
  UNIQUE (org_id, revision_id, id),
  FOREIGN KEY (org_id, revision_id) REFERENCES exam_plan_revision(org_id, id) ON DELETE CASCADE,
  FOREIGN KEY (org_id, revision_id, session_id)
    REFERENCES exam_plan_session(org_id, revision_id, id) ON DELETE CASCADE,
  FOREIGN KEY (org_id, revision_id, demand_id)
    REFERENCES exam_plan_exam_demand(org_id, revision_id, id),
  FOREIGN KEY (org_id, revision_id, demand_class_id)
    REFERENCES exam_plan_demand_class(org_id, revision_id, id),
  FOREIGN KEY (org_id, class_id) REFERENCES school_class(org_id, id),
  FOREIGN KEY (org_id, created_by) REFERENCES app_user(org_id, id),
  FOREIGN KEY (org_id, updated_by) REFERENCES app_user(org_id, id),
  EXCLUDE USING gist (
    org_id WITH =,
    revision_id WITH =,
    class_id WITH =,
    tstzrange(starts_at, ends_at, '[)') WITH &&
  ) WHERE (status <> 'CANCELLED')
);
CREATE INDEX idx_v27_class_session_time
  ON exam_plan_class_session_assignment(org_id, revision_id, starts_at, ends_at);

CREATE OR REPLACE FUNCTION validate_v27_joint_seat_assignment()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
  session_demand uuid;
  session_start timestamptz;
  session_end timestamptz;
  session_source varchar(20);
  assigned_classroom uuid;
  topology_classroom uuid;
  topology_code varchar(48);
BEGIN
  IF NEW.source_type <> 'JOINT' THEN
    RETURN NEW;
  END IF;

  SELECT s.demand_id, s.starts_at, s.ends_at, s.source_type, r.classroom_id
    INTO session_demand, session_start, session_end, session_source, assigned_classroom
  FROM exam_plan_session s
  JOIN exam_plan_room_assignment r
    ON r.org_id=s.org_id AND r.revision_id=s.revision_id
   AND r.session_id=s.id AND r.id=NEW.room_assignment_id
  WHERE s.org_id=NEW.org_id AND s.revision_id=NEW.revision_id AND s.id=NEW.session_id;
  IF NOT FOUND THEN
    RAISE EXCEPTION 'JOINT seat assignment must reference its session room assignment'
      USING ERRCODE='23503';
  END IF;
  IF session_source <> 'JOINT' OR session_demand IS NULL THEN
    RAISE EXCEPTION 'JOINT seat assignment requires a JOINT demand-backed session'
      USING ERRCODE='23514';
  END IF;
  IF NEW.starts_at IS DISTINCT FROM session_start OR NEW.ends_at IS DISTINCT FROM session_end THEN
    RAISE EXCEPTION 'JOINT seat assignment time must equal its session time'
      USING ERRCODE='23514';
  END IF;

  SELECT p.classroom_id, seat.seat_code
    INTO topology_classroom, topology_code
  FROM exam_plan_room_seat seat
  JOIN exam_plan_room_pool p
    ON p.org_id=seat.org_id AND p.revision_id=seat.revision_id AND p.id=seat.room_pool_id
  WHERE seat.org_id=NEW.org_id AND seat.revision_id=NEW.revision_id AND seat.id=NEW.seat_id;
  IF NOT FOUND OR topology_classroom IS DISTINCT FROM assigned_classroom THEN
    RAISE EXCEPTION 'JOINT seat topology does not belong to the assigned classroom'
      USING ERRCODE='23514';
  END IF;
  IF NEW.seat_label IS DISTINCT FROM topology_code THEN
    RAISE EXCEPTION 'JOINT seat label must match the versioned seat topology'
      USING ERRCODE='23514';
  END IF;
  IF NOT EXISTS (
    SELECT 1 FROM exam_plan_enrollment e
    WHERE e.org_id=NEW.org_id AND e.revision_id=NEW.revision_id
      AND e.candidate_id=NEW.candidate_id AND e.demand_id=session_demand
      AND e.status='ENROLLED'
  ) THEN
    RAISE EXCEPTION 'JOINT candidate must be enrolled in the session demand'
      USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END;
$$;

CREATE TRIGGER trg_v27_joint_seat_integrity
BEFORE INSERT OR UPDATE ON exam_plan_seat_assignment
FOR EACH ROW EXECUTE FUNCTION validate_v27_joint_seat_assignment();

CREATE OR REPLACE FUNCTION validate_v27_class_session_assignment()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
  session_demand uuid;
  session_start timestamptz;
  session_end timestamptz;
  session_source varchar(20);
BEGIN
  SELECT demand_id, starts_at, ends_at, source_type
    INTO session_demand, session_start, session_end, session_source
  FROM exam_plan_session
  WHERE org_id=NEW.org_id AND revision_id=NEW.revision_id AND id=NEW.session_id;
  IF NOT FOUND THEN
    RAISE EXCEPTION 'Class assignment session is outside the version graph' USING ERRCODE='23503';
  END IF;
  IF NEW.source_type='JOINT' AND session_source <> 'JOINT' THEN
    RAISE EXCEPTION 'JOINT class assignment requires a JOINT session' USING ERRCODE='23514';
  END IF;
  IF session_demand IS DISTINCT FROM NEW.demand_id THEN
    RAISE EXCEPTION 'Class assignment demand must equal its session demand' USING ERRCODE='23514';
  END IF;
  IF NEW.starts_at IS DISTINCT FROM session_start OR NEW.ends_at IS DISTINCT FROM session_end THEN
    RAISE EXCEPTION 'Class assignment time must equal its session time' USING ERRCODE='23514';
  END IF;
  IF NOT EXISTS (
    SELECT 1 FROM exam_plan_demand_class dc
    WHERE dc.org_id=NEW.org_id AND dc.revision_id=NEW.revision_id
      AND dc.id=NEW.demand_class_id AND dc.demand_id=NEW.demand_id AND dc.class_id=NEW.class_id
  ) THEN
    RAISE EXCEPTION 'Class assignment must reference the matching demand/class member'
      USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END;
$$;

CREATE TRIGGER trg_v27_class_session_integrity
BEFORE INSERT OR UPDATE ON exam_plan_class_session_assignment
FOR EACH ROW EXECUTE FUNCTION validate_v27_class_session_assignment();
