package com.jkxt.examops.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jkxt.common.web.ApiException;
import com.jkxt.examops.joint.JointPlanningModel.CampusTravel;
import com.jkxt.examops.joint.JointPlanningModel.Candidate;
import com.jkxt.examops.joint.JointPlanningModel.DemandWindow;
import com.jkxt.examops.joint.JointPlanningModel.Enrollment;
import com.jkxt.examops.joint.JointPlanningModel.ExamDemand;
import com.jkxt.examops.joint.JointPlanningModel.LockedDuty;
import com.jkxt.examops.joint.JointPlanningModel.LockedRoom;
import com.jkxt.examops.joint.JointPlanningModel.LockedSession;
import com.jkxt.examops.joint.JointPlanningModel.Room;
import com.jkxt.examops.joint.JointPlanningModel.Seat;
import com.jkxt.examops.joint.JointPlanningModel.Snapshot;
import com.jkxt.examops.joint.JointPlanningModel.SpecialRequirement;
import com.jkxt.examops.joint.JointPlanningModel.Teacher;
import com.jkxt.examops.joint.JointPlanningModel.TeacherStatus;
import com.jkxt.examops.joint.JointPlanningModel.TimeInterval;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Builds the immutable, tenant-qualified input graph consumed by the joint planner.
 *
 * <p>The loader deliberately merges current master-data safety state with the versioned planning
 * pool: disabling a classroom or teacher after import takes effect immediately, and duties/rooms
 * already occupied by another published plan become unavailable intervals. Version inputs remain
 * the source of scheduling intent; master data can only remove eligibility, never grant it.</p>
 */
@Service
public class JointPlanningSnapshotLoader {

  private static final ZoneId SCHOOL_ZONE = ZoneId.of("Asia/Shanghai");
  private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
  };
  private final JdbcTemplate jdbcTemplate;
  private final ObjectMapper objectMapper;

  public JointPlanningSnapshotLoader(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
    this.jdbcTemplate = jdbcTemplate;
    this.objectMapper = objectMapper;
  }

  public LoadedSnapshot load(UUID orgId, UUID revisionId) {
    RevisionContext revision = jdbcTemplate.query("""
        select plan_id from exam_plan_revision where org_id=? and id=?
        """, rs -> rs.next()
        ? new RevisionContext(rs.getObject(1, UUID.class)) : null, orgId, revisionId);
    if (revision == null) {
      throw new ApiException(HttpStatus.NOT_FOUND, "EXAM_PLAN_NOT_FOUND", "Plan revision is unavailable");
    }

    Map<UUID, List<DemandWindow>> windowsByDemand = demandWindows(orgId, revisionId);
    Map<UUID, Set<UUID>> classesByDemand = new LinkedHashMap<>();
    Map<DemandClassKey, UUID> demandClassIds = new LinkedHashMap<>();
    jdbcTemplate.query("""
        select id, demand_id, class_id from exam_plan_demand_class
        where org_id=? and revision_id=? order by demand_id, class_id
        """, (org.springframework.jdbc.core.RowCallbackHandler) rs -> {
      UUID demandId = rs.getObject("demand_id", UUID.class);
      UUID classId = rs.getObject("class_id", UUID.class);
      classesByDemand.computeIfAbsent(demandId, ignored -> new LinkedHashSet<>()).add(classId);
      demandClassIds.put(new DemandClassKey(demandId, classId), rs.getObject("id", UUID.class));
    }, orgId, revisionId);

    Map<UUID, DemandMetadata> demandMetadata = new LinkedHashMap<>();
    List<ExamDemand> demands = jdbcTemplate.query("""
        select d.id, d.demand_code, d.name, d.subject_id, s.name subject_name,
               d.duration_minutes, d.invigilator_count, d.min_rooms, d.max_rooms,
               d.split_allowed, d.required_room_features::text
        from exam_plan_exam_demand d
        left join subject s on s.org_id=d.org_id and s.id=d.subject_id
        where d.org_id=? and d.revision_id=? order by d.demand_code, d.id
        """, (rs, row) -> {
      UUID id = rs.getObject("id", UUID.class);
      String code = rs.getString("demand_code");
      String name = rs.getString("name");
      demandMetadata.put(id, new DemandMetadata(code, name, rs.getString("subject_name")));
      return new ExamDemand(id, code, name, rs.getObject("subject_id", UUID.class),
          rs.getInt("duration_minutes"), rs.getInt("invigilator_count"), rs.getInt("min_rooms"),
          rs.getInt("max_rooms"), rs.getBoolean("split_allowed"),
          tokens(rs.getString("required_room_features")),
          classesByDemand.getOrDefault(id, Set.of()), windowsByDemand.getOrDefault(id, List.of()));
    }, orgId, revisionId);

    Map<UUID, CandidateWindows> candidateWindows = candidateWindows(orgId, revisionId);
    Map<UUID, CandidateMetadata> candidateMetadata = new LinkedHashMap<>();
    List<Candidate> candidates = jdbcTemplate.query("""
        select id, candidate_code, class_id from exam_plan_candidate
        where org_id=? and revision_id=? order by candidate_code, id
        """, (rs, row) -> {
      UUID id = rs.getObject("id", UUID.class);
      UUID classId = rs.getObject("class_id", UUID.class);
      String code = rs.getString("candidate_code");
      candidateMetadata.put(id, new CandidateMetadata(code, classId));
      CandidateWindows windows = candidateWindows.getOrDefault(id, CandidateWindows.EMPTY);
      return new Candidate(id, code, classId, windows.available(), windows.unavailable());
    }, orgId, revisionId);

    List<Enrollment> enrollments = jdbcTemplate.query("""
        select candidate_id, demand_id from exam_plan_enrollment
        where org_id=? and revision_id=? and status='ENROLLED'
        order by demand_id, candidate_id
        """, (rs, row) -> new Enrollment(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class)),
        orgId, revisionId);

    List<SpecialRequirement> specialRequirements = jdbcTemplate.query("""
        select candidate_id, demand_id, requirement_code, required_features::text,
               extra_minutes, separate_room
        from exam_plan_special_requirement where org_id=? and revision_id=?
        order by candidate_id, demand_id nulls first, requirement_code
        """, (rs, row) -> new SpecialRequirement(rs.getObject(1, UUID.class),
        rs.getObject(2, UUID.class), rs.getString(3), tokens(rs.getString(4)), rs.getInt(5),
        rs.getBoolean(6)), orgId, revisionId);

    Map<UUID, RoomWindows> roomWindows = roomWindows(orgId, revisionId);
    Map<UUID, List<Seat>> seatsByPool = new LinkedHashMap<>();
    Map<UUID, SeatMetadata> seatMetadata = new LinkedHashMap<>();
    jdbcTemplate.query("""
        select s.id, s.room_pool_id, s.seat_code, s.row_no, s.column_no, s.features::text, s.status
        from exam_plan_room_seat s where s.org_id=? and s.revision_id=?
        order by s.room_pool_id, s.seat_code, s.id
        """, (org.springframework.jdbc.core.RowCallbackHandler) rs -> {
      UUID id = rs.getObject("id", UUID.class);
      UUID poolId = rs.getObject("room_pool_id", UUID.class);
      String code = rs.getString("seat_code");
      seatsByPool.computeIfAbsent(poolId, ignored -> new ArrayList<>()).add(new Seat(
          id, code, integer(rs.getObject("row_no")), integer(rs.getObject("column_no")),
          tokens(rs.getString("features")), "ACTIVE".equals(rs.getString("status"))));
      seatMetadata.put(id, new SeatMetadata(code));
    }, orgId, revisionId);

    Map<UUID, List<TimeInterval>> publishedRoomOccupancy = publishedRoomOccupancy(
        orgId, revisionId, revision.planId());
    Map<UUID, RoomMetadata> roomMetadata = new LinkedHashMap<>();
    List<Room> rooms = jdbcTemplate.query("""
        select p.id pool_id, p.classroom_id, p.campus_code, p.exam_capacity,
               p.features::text, p.status pool_status, c.code classroom_code, c.status classroom_status
        from exam_plan_room_pool p
        join classroom c on c.org_id=p.org_id and c.id=p.classroom_id
        where p.org_id=? and p.revision_id=? order by p.campus_code, c.code, p.classroom_id
        """, (rs, row) -> {
      UUID poolId = rs.getObject("pool_id", UUID.class);
      UUID classroomId = rs.getObject("classroom_id", UUID.class);
      RoomWindows windows = roomWindows.getOrDefault(poolId, RoomWindows.EMPTY);
      List<TimeInterval> unavailable = new ArrayList<>(windows.unavailable());
      unavailable.addAll(publishedRoomOccupancy.getOrDefault(classroomId, List.of()));
      String status = rs.getString("pool_status");
      int examCapacity = rs.getInt("exam_capacity");
      String code = rs.getString("classroom_code");
      Set<String> features = tokens(rs.getString("features"));
      roomMetadata.put(classroomId, new RoomMetadata(code, examCapacity, features));
      return new Room(classroomId, code, rs.getString("campus_code"), examCapacity, features,
          windows.available(), List.copyOf(unavailable), seatsByPool.getOrDefault(poolId, List.of()),
          !"EXCLUDED".equals(status) && "active".equals(rs.getString("classroom_status")),
          "LOCKED".equals(status));
    }, orgId, revisionId);

    Map<UUID, Avoidance> avoidances = avoidances(orgId, revisionId, enrollments, candidates);
    Map<UUID, Set<UUID>> subjectsByTeacher = teacherSubjects(orgId);
    Map<UUID, Set<UUID>> homeroomsByTeacher = teacherHomerooms(orgId);
    Map<UUID, List<TimeInterval>> publishedTeacherOccupancy = publishedTeacherOccupancy(
        orgId, revisionId, revision.planId());
    Map<UUID, List<TimeInterval>> constraintLeave = constraintLeave(orgId, revisionId);
    List<Teacher> teachers = jdbcTemplate.query("""
        select p.teacher_id, p.eligible_roles::text pool_roles, p.max_daily_sessions pool_max,
               p.min_rest_minutes, p.preferred_campus pool_campus, p.status pool_status,
               p.preferences::text, t.teacher_no, t.name, t.status teacher_status,
               t.qualifications::text master_roles, t.qualification_expiry,
               t.max_daily_sessions master_max, t.preferred_campus master_campus,
               t.age, t.position, t.leave_windows::text, t.duty_windows::text,
               t.functional_accommodations::text
        from exam_plan_teacher_pool p
        join teacher t on t.org_id=p.org_id and t.id=p.teacher_id
        where p.org_id=? and p.revision_id=? order by t.teacher_no, p.teacher_id
        """, (rs, row) -> teacher(rs, subjectsByTeacher, homeroomsByTeacher, avoidances,
        publishedTeacherOccupancy, constraintLeave), orgId, revisionId);

    List<CampusTravel> travel = jdbcTemplate.query("""
        select from_campus, to_campus, travel_minutes, buffer_minutes
        from exam_plan_campus_travel where org_id=? and revision_id=?
        order by from_campus, to_campus
        """, (rs, row) -> new CampusTravel(rs.getString(1), rs.getString(2), rs.getInt(3),
        rs.getInt(4)), orgId, revisionId);

    LockState locks = locks(orgId, revisionId);
    Snapshot snapshot = new Snapshot(
        orgId + "/" + revisionId,
        List.copyOf(demands), List.copyOf(candidates), List.copyOf(enrollments),
        List.copyOf(specialRequirements), List.copyOf(rooms), List.copyOf(teachers),
        List.copyOf(travel), locks.sessions(), locks.rooms(), locks.duties());
    return new LoadedSnapshot(snapshot, Map.copyOf(demandMetadata), Map.copyOf(candidateMetadata),
        Map.copyOf(roomMetadata), Map.copyOf(seatMetadata), Map.copyOf(demandClassIds),
        locks.lockedSessionDemands(), locks.lockedRooms(), locks.lockedDuties());
  }

  private Map<UUID, List<DemandWindow>> demandWindows(UUID orgId, UUID revisionId) {
    Map<UUID, List<DemandWindow>> result = new LinkedHashMap<>();
    jdbcTemplate.query("""
        select id, demand_id, starts_at, ends_at, preference_weight, locked
        from exam_plan_demand_window where org_id=? and revision_id=?
        order by demand_id, starts_at, id
        """, (org.springframework.jdbc.core.RowCallbackHandler) rs -> result
        .computeIfAbsent(rs.getObject("demand_id", UUID.class), ignored -> new ArrayList<>())
        .add(new DemandWindow(rs.getObject("id", UUID.class), instant(rs, "starts_at"),
            instant(rs, "ends_at"), rs.getInt("preference_weight"), rs.getBoolean("locked"))),
        orgId, revisionId);
    result.replaceAll((ignored, values) -> List.copyOf(values));
    return result;
  }

  private Map<UUID, CandidateWindows> candidateWindows(UUID orgId, UUID revisionId) {
    Map<UUID, List<TimeInterval>> available = new LinkedHashMap<>();
    Map<UUID, List<TimeInterval>> unavailable = new LinkedHashMap<>();
    jdbcTemplate.query("""
        select candidate_id, window_type, starts_at, ends_at from exam_plan_candidate_window
        where org_id=? and revision_id=? order by candidate_id, starts_at
        """, (org.springframework.jdbc.core.RowCallbackHandler) rs -> {
      UUID id = rs.getObject("candidate_id", UUID.class);
      Map<UUID, List<TimeInterval>> target = "AVAILABLE".equals(rs.getString("window_type"))
          ? available : unavailable;
      target.computeIfAbsent(id, ignored -> new ArrayList<>()).add(
          new TimeInterval(instant(rs, "starts_at"), instant(rs, "ends_at")));
    }, orgId, revisionId);
    Set<UUID> ids = new LinkedHashSet<>(available.keySet());
    ids.addAll(unavailable.keySet());
    Map<UUID, CandidateWindows> result = new LinkedHashMap<>();
    ids.forEach(id -> result.put(id, new CandidateWindows(
        List.copyOf(available.getOrDefault(id, List.of())),
        List.copyOf(unavailable.getOrDefault(id, List.of())))));
    return result;
  }

  private Map<UUID, RoomWindows> roomWindows(UUID orgId, UUID revisionId) {
    Map<UUID, List<TimeInterval>> available = new LinkedHashMap<>();
    Map<UUID, List<TimeInterval>> unavailable = new LinkedHashMap<>();
    jdbcTemplate.query("""
        select room_pool_id, window_type, starts_at, ends_at from exam_plan_room_window
        where org_id=? and revision_id=? order by room_pool_id, starts_at
        """, (org.springframework.jdbc.core.RowCallbackHandler) rs -> {
      UUID id = rs.getObject("room_pool_id", UUID.class);
      Map<UUID, List<TimeInterval>> target = "AVAILABLE".equals(rs.getString("window_type"))
          ? available : unavailable;
      target.computeIfAbsent(id, ignored -> new ArrayList<>()).add(
          new TimeInterval(instant(rs, "starts_at"), instant(rs, "ends_at")));
    }, orgId, revisionId);
    Set<UUID> ids = new LinkedHashSet<>(available.keySet());
    ids.addAll(unavailable.keySet());
    Map<UUID, RoomWindows> result = new LinkedHashMap<>();
    ids.forEach(id -> result.put(id, new RoomWindows(
        List.copyOf(available.getOrDefault(id, List.of())),
        List.copyOf(unavailable.getOrDefault(id, List.of())))));
    return result;
  }

  private Map<UUID, Avoidance> avoidances(
      UUID orgId,
      UUID revisionId,
      List<Enrollment> enrollments,
      List<Candidate> candidates
  ) {
    Map<UUID, Set<UUID>> demandsByCandidate = new HashMap<>();
    enrollments.forEach(value -> demandsByCandidate
        .computeIfAbsent(value.candidateId(), ignored -> new LinkedHashSet<>()).add(value.demandId()));
    Map<UUID, Set<UUID>> demandsByClass = new HashMap<>();
    candidates.forEach(candidate -> {
      if (candidate.classId() != null) {
        demandsByClass.computeIfAbsent(candidate.classId(), ignored -> new LinkedHashSet<>())
            .addAll(demandsByCandidate.getOrDefault(candidate.id(), Set.of()));
      }
    });
    Map<UUID, AvoidanceBuilder> builders = new LinkedHashMap<>();
    jdbcTemplate.query("""
        select p.teacher_id, a.target_type, a.demand_id, a.class_id, a.candidate_id, a.severity
        from exam_plan_teacher_avoidance a
        join exam_plan_teacher_pool p on p.org_id=a.org_id and p.revision_id=a.revision_id
          and p.id=a.teacher_pool_id
        where a.org_id=? and a.revision_id=? order by p.teacher_id, a.id
        """, (org.springframework.jdbc.core.RowCallbackHandler) rs -> {
      AvoidanceBuilder builder = builders.computeIfAbsent(
          rs.getObject("teacher_id", UUID.class), ignored -> new AvoidanceBuilder());
      String target = rs.getString("target_type");
      boolean hard = "HARD".equals(rs.getString("severity"));
      if (hard) {
        switch (target) {
          case "DEMAND" -> builder.hardDemands.add(rs.getObject("demand_id", UUID.class));
          case "CLASS" -> builder.hardClasses.add(rs.getObject("class_id", UUID.class));
          case "CANDIDATE" -> builder.hardCandidates.add(rs.getObject("candidate_id", UUID.class));
          default -> throw new IllegalStateException("Unknown avoidance target " + target);
        }
      } else {
        switch (target) {
          case "DEMAND" -> builder.softDemands.add(rs.getObject("demand_id", UUID.class));
          case "CLASS" -> builder.softDemands.addAll(
              demandsByClass.getOrDefault(rs.getObject("class_id", UUID.class), Set.of()));
          case "CANDIDATE" -> builder.softDemands.addAll(
              demandsByCandidate.getOrDefault(rs.getObject("candidate_id", UUID.class), Set.of()));
          default -> throw new IllegalStateException("Unknown avoidance target " + target);
        }
      }
    }, orgId, revisionId);
    Map<UUID, Avoidance> result = new LinkedHashMap<>();
    builders.forEach((id, builder) -> result.put(id, builder.build()));
    return result;
  }

  private Map<UUID, Set<UUID>> teacherSubjects(UUID orgId) {
    Map<UUID, Set<UUID>> result = new LinkedHashMap<>();
    jdbcTemplate.query("""
        select teacher_id, subject_id from teacher_subject where org_id=? order by teacher_id, subject_id
        """, (org.springframework.jdbc.core.RowCallbackHandler) rs -> result
        .computeIfAbsent(rs.getObject(1, UUID.class), ignored -> new LinkedHashSet<>())
        .add(rs.getObject(2, UUID.class)), orgId);
    result.replaceAll((ignored, values) -> Set.copyOf(values));
    return result;
  }

  private Map<UUID, Set<UUID>> teacherHomerooms(UUID orgId) {
    Map<UUID, Set<UUID>> result = new LinkedHashMap<>();
    jdbcTemplate.query("""
        select id, homeroom_class_id from teacher
        where org_id=? and homeroom_class_id is not null
        """, (org.springframework.jdbc.core.RowCallbackHandler) rs -> result
        .computeIfAbsent(rs.getObject(1, UUID.class), ignored -> new LinkedHashSet<>())
        .add(rs.getObject(2, UUID.class)), orgId);
    jdbcTemplate.query("""
        select homeroom_teacher_id, id from school_class
        where org_id=? and homeroom_teacher_id is not null
        """, (org.springframework.jdbc.core.RowCallbackHandler) rs -> result
        .computeIfAbsent(rs.getObject(1, UUID.class), ignored -> new LinkedHashSet<>())
        .add(rs.getObject(2, UUID.class)), orgId);
    result.replaceAll((ignored, values) -> Set.copyOf(values));
    return result;
  }

  private Teacher teacher(
      ResultSet rs,
      Map<UUID, Set<UUID>> subjectsByTeacher,
      Map<UUID, Set<UUID>> homeroomsByTeacher,
      Map<UUID, Avoidance> avoidances,
      Map<UUID, List<TimeInterval>> publishedOccupancy,
      Map<UUID, List<TimeInterval>> constraintLeave
  ) throws SQLException {
    UUID id = rs.getObject("teacher_id", UUID.class);
    Set<String> masterRoles = tokens(rs.getString("master_roles"));
    Set<String> poolRoles = tokens(rs.getString("pool_roles"));
    Set<String> eligibleRoles = new LinkedHashSet<>();
    if (masterRoles.contains("INVIGILATOR") && poolRoles.contains("INVIGILATOR")) {
      eligibleRoles.add("INVIGILATOR");
    }
    if (masterRoles.containsAll(Set.of("INVIGILATOR", "CHIEF")) && poolRoles.contains("CHIEF")) {
      eligibleRoles.add("CHIEF");
    }
    JsonNode preferences = json(rs.getString("preferences"), "{}");
    List<TimeInterval> unavailable = new ArrayList<>(intervals(rs.getString("leave_windows")));
    unavailable.addAll(constraintLeave.getOrDefault(id, List.of()));
    unavailable.addAll(publishedOccupancy.getOrDefault(id, List.of()));
    Avoidance avoidance = avoidances.getOrDefault(id, Avoidance.EMPTY);
    Date expiryDate = rs.getDate("qualification_expiry");
    Instant expiry = expiryDate == null ? null
        : expiryDate.toLocalDate().plusDays(1).atStartOfDay(SCHOOL_ZONE).toInstant().minusNanos(1);
    int age = rs.getObject("age") == null ? 0 : rs.getInt("age");
    boolean healthRestricted = preferences.path("healthRestricted").asBoolean(false)
        || !json(rs.getString("functional_accommodations"), "[]").isEmpty();
    int positionWeight = Math.max(0, preferences.path("positionWeight").asInt(
        administrativeWeight(rs.getString("position"))));
    String preferredCampus = text(rs.getString("pool_campus"));
    if (preferredCampus == null) preferredCampus = text(rs.getString("master_campus"));
    TeacherStatus status = switch (rs.getString("pool_status")) {
      case "INCLUDED" -> TeacherStatus.INCLUDED;
      case "RESERVE" -> TeacherStatus.RESERVE;
      default -> TeacherStatus.EXCLUDED;
    };
    if (!"active".equals(rs.getString("teacher_status"))) status = TeacherStatus.EXCLUDED;
    return new Teacher(id, rs.getString("teacher_no"), rs.getString("name"), Set.copyOf(eligibleRoles),
        expiry, Math.min(rs.getInt("pool_max"), rs.getInt("master_max")),
        rs.getInt("min_rest_minutes"), preferredCampus, status,
        intervals(rs.getString("duty_windows")), List.copyOf(unavailable),
        avoidance.hardDemands(), avoidance.hardClasses(), avoidance.hardCandidates(),
        avoidance.softDemands(), homeroomsByTeacher.getOrDefault(id, Set.of()),
        subjectsByTeacher.getOrDefault(id, Set.of()), age, healthRestricted, positionWeight);
  }

  private int administrativeWeight(String position) {
    if (position == null) return 0;
    String value = position.toLowerCase(Locale.ROOT);
    return value.contains("校长") || value.contains("principal") ? 40
        : value.contains("主任") || value.contains("director") || value.contains("dean") ? 20 : 0;
  }

  private Map<UUID, List<TimeInterval>> publishedTeacherOccupancy(
      UUID orgId,
      UUID revisionId,
      UUID planId
  ) {
    Map<UUID, List<TimeInterval>> result = new LinkedHashMap<>();
    jdbcTemplate.query("""
        select a.teacher_id, a.starts_at, a.ends_at
        from exam_plan_duty_assignment a
        join exam_plan_revision r on r.org_id=a.org_id and r.id=a.revision_id
        where a.org_id=? and a.revision_id<>? and r.plan_id<>?
          and r.status in ('PUBLISHED','EXECUTING') and a.status not in ('CANCELLED','DECLINED')
          and not exists (
            select 1 from exam_plan_duty_response response
            where response.org_id=a.org_id and response.assignment_id=a.id
              and response.status='REJECTED'
          )
        order by a.teacher_id, a.starts_at
        """, (org.springframework.jdbc.core.RowCallbackHandler) rs -> result
        .computeIfAbsent(rs.getObject(1, UUID.class), ignored -> new ArrayList<>())
        .add(new TimeInterval(instant(rs, "starts_at"), instant(rs, "ends_at"))),
        orgId, revisionId, planId);
    result.replaceAll((ignored, values) -> List.copyOf(values));
    return result;
  }

  private Map<UUID, List<TimeInterval>> publishedRoomOccupancy(
      UUID orgId,
      UUID revisionId,
      UUID planId
  ) {
    Map<UUID, List<TimeInterval>> result = new LinkedHashMap<>();
    jdbcTemplate.query("""
        select a.classroom_id, a.starts_at, a.ends_at
        from exam_plan_room_assignment a
        join exam_plan_revision r on r.org_id=a.org_id and r.id=a.revision_id
        where a.org_id=? and a.revision_id<>? and r.plan_id<>?
          and r.status in ('PUBLISHED','EXECUTING') and a.status<>'CANCELLED'
        order by a.classroom_id, a.starts_at
        """, (org.springframework.jdbc.core.RowCallbackHandler) rs -> result
        .computeIfAbsent(rs.getObject(1, UUID.class), ignored -> new ArrayList<>())
        .add(new TimeInterval(instant(rs, "starts_at"), instant(rs, "ends_at"))),
        orgId, revisionId, planId);
    result.replaceAll((ignored, values) -> List.copyOf(values));
    return result;
  }

  private Map<UUID, List<TimeInterval>> constraintLeave(UUID orgId, UUID revisionId) {
    Map<UUID, List<TimeInterval>> result = new LinkedHashMap<>();
    jdbcTemplate.query("""
        select teacher_id, starts_at, ends_at from exam_plan_leave_request
        where org_id=? and status='APPROVED' order by teacher_id, starts_at
        """, (org.springframework.jdbc.core.RowCallbackHandler) rs -> result
        .computeIfAbsent(rs.getObject("teacher_id", UUID.class), ignored -> new ArrayList<>())
        .add(new TimeInterval(instant(rs, "starts_at"), instant(rs, "ends_at"))), orgId);
    jdbcTemplate.query("""
        select t.id teacher_id, c.expression::text
        from exam_plan_constraint c
        join teacher t on t.org_id=c.org_id
          and (c.resource_id=t.id::text or c.resource_id=t.teacher_no)
        where c.org_id=? and c.revision_id=? and c.enabled and c.severity='HARD'
          and c.constraint_type='TEACHER_LEAVE'
        """, (org.springframework.jdbc.core.RowCallbackHandler) rs -> {
      JsonNode expression = json(rs.getString("expression"), "{}");
      if (!expression.path("approved").asBoolean(true)) return;
      Instant start = parseInstant(expression.path("startsAt").asText(null));
      Instant end = parseInstant(expression.path("endsAt").asText(null));
      if (start != null && end != null && end.isAfter(start)) {
        result.computeIfAbsent(rs.getObject("teacher_id", UUID.class), ignored -> new ArrayList<>())
            .add(new TimeInterval(start, end));
      }
    }, orgId, revisionId);
    result.replaceAll((ignored, values) -> List.copyOf(values));
    return result;
  }

  private LockState locks(UUID orgId, UUID revisionId) {
    List<LockedSession> sessions = jdbcTemplate.query("""
        select s.demand_id, w.id window_id
        from exam_plan_session s
        join exam_plan_demand_window w on w.org_id=s.org_id and w.revision_id=s.revision_id
          and w.demand_id=s.demand_id and w.starts_at=s.starts_at
        where s.org_id=? and s.revision_id=? and s.status='LOCKED' and s.demand_id is not null
        order by s.demand_id, w.id
        """, (rs, row) -> new LockedSession(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class)),
        orgId, revisionId);
    // A window explicitly marked locked is a hard lock even before any output rows exist.
    List<LockedSession> windowLocks = jdbcTemplate.query("""
        select demand_id, id from exam_plan_demand_window
        where org_id=? and revision_id=? and locked order by demand_id, id
        """, (rs, row) -> new LockedSession(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class)),
        orgId, revisionId);
    Map<UUID, LockedSession> byDemand = new LinkedHashMap<>();
    sessions.forEach(value -> byDemand.putIfAbsent(value.demandId(), value));
    for (LockedSession value : windowLocks) {
      LockedSession previous = byDemand.putIfAbsent(value.demandId(), value);
      if (previous != null && !previous.windowId().equals(value.windowId())) {
        throw new IllegalArgumentException("Demand has more than one locked time window: " + value.demandId());
      }
    }

    List<LockedRoom> rooms = jdbcTemplate.query("""
        select s.demand_id, r.classroom_id
        from exam_plan_room_assignment r
        join exam_plan_session s on s.org_id=r.org_id and s.revision_id=r.revision_id and s.id=r.session_id
        where r.org_id=? and r.revision_id=? and r.status='LOCKED' and s.demand_id is not null
        order by s.demand_id, r.classroom_id
        """, (rs, row) -> new LockedRoom(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class)),
        orgId, revisionId);
    List<LockedDuty> duties = jdbcTemplate.query("""
        select demand_id, classroom_id, duty_role, position_no, teacher_id from (
          select s.demand_id, r.classroom_id, a.duty_role, a.teacher_id,
                 row_number() over (partition by s.demand_id, r.classroom_id, a.duty_role
                   order by a.teacher_id)-1 position_no
          from exam_plan_duty_assignment a
          join exam_plan_duty_requirement q on q.org_id=a.org_id and q.revision_id=a.revision_id
            and q.id=a.requirement_id
          join exam_plan_session s on s.org_id=q.org_id and s.revision_id=q.revision_id and s.id=q.session_id
          join exam_plan_room_assignment r on r.org_id=q.org_id and r.revision_id=q.revision_id
            and r.id=q.room_assignment_id
          where a.org_id=? and a.revision_id=? and a.status not in ('CANCELLED','DECLINED')
            and a.source in ('MANUAL','SUBSTITUTION') and s.demand_id is not null
        ) locked order by demand_id, classroom_id, duty_role, position_no
        """, (rs, row) -> new LockedDuty(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
        rs.getString(3), rs.getInt(4), rs.getObject(5, UUID.class)), orgId, revisionId);
    Set<UUID> lockedSessionDemands = Set.copyOf(byDemand.keySet());
    Set<DemandRoomKey> lockedRooms = rooms.stream()
        .map(value -> new DemandRoomKey(value.demandId(), value.roomId()))
        .collect(java.util.stream.Collectors.toUnmodifiableSet());
    Map<LockedDutyKey, UUID> lockedDuties = new LinkedHashMap<>();
    duties.forEach(value -> lockedDuties.put(new LockedDutyKey(value.demandId(), value.roomId(),
        value.role(), value.position()), value.teacherId()));
    return new LockState(List.copyOf(byDemand.values()), List.copyOf(rooms), List.copyOf(duties),
        lockedSessionDemands, lockedRooms, Map.copyOf(lockedDuties));
  }

  private List<TimeInterval> intervals(String raw) {
    JsonNode value = json(raw, "[]");
    if (!value.isArray()) return List.of();
    List<TimeInterval> result = new ArrayList<>();
    for (JsonNode item : value) {
      Instant start = parseInstant(item.path("start").asText(item.path("startsAt").asText(null)));
      Instant end = parseInstant(item.path("end").asText(item.path("endsAt").asText(null)));
      if (start != null && end != null && end.isAfter(start)) result.add(new TimeInterval(start, end));
    }
    result.sort(Comparator.comparing(TimeInterval::start));
    return List.copyOf(result);
  }

  private Instant parseInstant(String value) {
    if (value == null || value.isBlank()) return null;
    try {
      return OffsetDateTime.parse(value).toInstant();
    } catch (Exception ignored) {
      try {
        return Instant.parse(value);
      } catch (Exception ignoredAgain) {
        try {
          return LocalDateTime.parse(value).atZone(SCHOOL_ZONE).toInstant();
        } catch (Exception ignoredLocal) {
          return null;
        }
      }
    }
  }

  private Set<String> tokens(String raw) {
    try {
      List<String> values = objectMapper.readValue(raw == null ? "[]" : raw, STRING_LIST);
      Set<String> result = new LinkedHashSet<>();
      values.stream().filter(value -> value != null && !value.isBlank())
          .map(value -> value.trim().toUpperCase(Locale.ROOT)).forEach(result::add);
      return Set.copyOf(result);
    } catch (Exception exception) {
      throw new IllegalStateException("Stored joint input token list is unreadable", exception);
    }
  }

  private JsonNode json(String raw, String fallback) {
    try {
      return objectMapper.readTree(raw == null ? fallback : raw);
    } catch (Exception exception) {
      throw new IllegalStateException("Stored joint input JSON is unreadable", exception);
    }
  }

  private Instant instant(ResultSet rs, String column) throws SQLException {
    Timestamp value = rs.getTimestamp(column);
    return value == null ? null : value.toInstant();
  }

  private Integer integer(Object value) {
    return value == null ? null : ((Number) value).intValue();
  }

  private String text(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }

  public record LoadedSnapshot(
      Snapshot snapshot,
      Map<UUID, DemandMetadata> demands,
      Map<UUID, CandidateMetadata> candidates,
      Map<UUID, RoomMetadata> rooms,
      Map<UUID, SeatMetadata> seats,
      Map<DemandClassKey, UUID> demandClassIds,
      Set<UUID> lockedSessionDemands,
      Set<DemandRoomKey> lockedRooms,
      Map<LockedDutyKey, UUID> lockedDuties
  ) {
  }

  public record DemandMetadata(String code, String name, String subjectName) {
  }

  public record CandidateMetadata(String code, UUID classId) {
  }

  public record RoomMetadata(String code, int examCapacity, Set<String> features) {
  }

  public record SeatMetadata(String code) {
  }

  public record DemandClassKey(UUID demandId, UUID classId) {
  }

  public record DemandRoomKey(UUID demandId, UUID roomId) {
  }

  public record LockedDutyKey(UUID demandId, UUID roomId, String role, int position) {
  }

  private record RevisionContext(UUID planId) {
  }

  private record CandidateWindows(List<TimeInterval> available, List<TimeInterval> unavailable) {
    private static final CandidateWindows EMPTY = new CandidateWindows(List.of(), List.of());
  }

  private record RoomWindows(List<TimeInterval> available, List<TimeInterval> unavailable) {
    private static final RoomWindows EMPTY = new RoomWindows(List.of(), List.of());
  }

  private record Avoidance(
      Set<UUID> hardDemands,
      Set<UUID> hardClasses,
      Set<UUID> hardCandidates,
      Set<UUID> softDemands
  ) {
    private static final Avoidance EMPTY = new Avoidance(Set.of(), Set.of(), Set.of(), Set.of());
  }

  private static final class AvoidanceBuilder {
    private final Set<UUID> hardDemands = new LinkedHashSet<>();
    private final Set<UUID> hardClasses = new LinkedHashSet<>();
    private final Set<UUID> hardCandidates = new LinkedHashSet<>();
    private final Set<UUID> softDemands = new LinkedHashSet<>();

    private Avoidance build() {
      return new Avoidance(Set.copyOf(hardDemands), Set.copyOf(hardClasses),
          Set.copyOf(hardCandidates), Set.copyOf(softDemands));
    }
  }

  private record LockState(
      List<LockedSession> sessions,
      List<LockedRoom> rooms,
      List<LockedDuty> duties,
      Set<UUID> lockedSessionDemands,
      Set<DemandRoomKey> lockedRooms,
      Map<LockedDutyKey, UUID> lockedDuties
  ) {
  }
}
