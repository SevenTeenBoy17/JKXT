package com.jkxt.scheduling.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jkxt.common.tenant.TenantContext;
import com.jkxt.common.web.ApiException;
import com.jkxt.examops.api.ExamPlanDtos.PlanRevisionView;
import com.jkxt.examops.service.ExamPlanService;
import com.jkxt.examops.service.LegacyPublicationIntegrity;
import com.jkxt.scheduling.api.LegacyPublicationDtos.PublicationView;
import com.jkxt.scheduling.api.LegacyPublicationDtos.SubmitPublicationRequest;
import com.jkxt.scheduling.domain.ScheduleRun;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Immutable approval projections of the existing invigilation-only task contract. */
@Service
public class LegacyPublicationService {
  private static final ZoneId SCHOOL_ZONE = ZoneId.of("Asia/Shanghai");
  private final JdbcTemplate jdbc;
  private final ObjectMapper mapper;
  private final ExamPlanService plans;
  private final LegacyPublicationIntegrity integrity;

  public LegacyPublicationService(JdbcTemplate jdbc, ObjectMapper mapper, ExamPlanService plans,
      LegacyPublicationIntegrity integrity) {
    this.jdbc = jdbc; this.mapper = mapper; this.plans = plans; this.integrity = integrity;
  }

  @Transactional(readOnly = true)
  public PublicationView get(UUID task) {
    UUID org = TenantContext.getRequired();
    requireTask(org, task, false);
    return view(org, task);
  }

  /** Called under the same organization/task locks as the compatibility publication. */
  @Transactional
  public PublicationView publishApproved(UUID task, UUID actor) {
    UUID org = TenantContext.getRequired();
    jdbc.queryForObject("select id from organization where id=? for no key update", UUID.class, org);
    Map<String, Object> source = requireTask(org, task, true);
    Map<String, Object> binding = integrity.binding(org, task);
    if (binding.isEmpty()) {
      // Historical releases predate projections. Permit only the existing no-op response.
      if ("published".equals(source.get("status")) && source.get("published_at") != null) return null;
      throw approvalRequired();
    }
    UUID plan = (UUID) binding.get("planId"), revision = (UUID) binding.get("revisionId");
    integrity.requireTrustedProjection(org, revision);
    var version = plans.revision(plan, revision);
    if ("APPROVED".equals(version.status())) {
      plans.publish(plan, revision, actor, version.revisionCursor());
    } else if (Set.of("PUBLISHED", "EXECUTING").contains(version.status())) {
      UUID active = jdbc.queryForObject("select active_revision_id from exam_plan where org_id=? and id=?", UUID.class, org, plan);
      if (!revision.equals(active)) throw approvalRequired();
    } else {
      throw approvalRequired();
    }
    return view(org, task);
  }

  private ApiException approvalRequired() {
    return new ApiException(HttpStatus.CONFLICT, "LEGACY_APPROVAL_REQUIRED",
        "请先将当前排班提交审批，由另一位管理员批准后再发布；修改排班后必须重新送审");
  }

  @Transactional
  public PublicationView submit(UUID task, SubmitPublicationRequest request, UUID actor) {
    UUID org = TenantContext.getRequired();
    jdbc.queryForObject("select id from organization where id=? for no key update", UUID.class, org);
    Map<String, Object> sourceTask = requireTask(org, task, true);
    if (!Set.of("scheduled", "published").contains(sourceTask.get("status"))) {
      throw notReady("任务尚未完成排班或正在重新排班");
    }
    ScheduleRun run = integrity.activeRun(org, task);
    if (!"succeeded".equals(run.getStatus())) throw notReady("当前排班尚未成功");
    if (!run.getId().equals(request.runId()) || request.runRevisionCursor() != run.getRevisionCursor()) {
      throw new ApiException(HttpStatus.CONFLICT, "LEGACY_RUN_STALE", "排班版本已变化，请刷新后重新提交");
    }
    Map<String, Object> binding = integrity.binding(org, task);
    if (!binding.isEmpty() && integrity.current(org, task, binding)) {
      PlanRevisionView existing = plans.revision((UUID) binding.get("planId"), (UUID) binding.get("revisionId"));
      if (Set.of("PENDING_APPROVAL", "APPROVED", "PUBLISHED", "EXECUTING").contains(existing.status())) {
        return view(org, task);
      }
    }
    List<SourceRoom> rooms = sourceRooms(org, task);
    List<SourceDuty> duties = sourceDuties(org, run.getId());
    assertSourceReady(org, run, rooms, duties);
    UUID plan = binding.isEmpty() ? UUID.randomUUID() : (UUID) binding.get("planId");
    UUID previous = binding.isEmpty() ? null : (UUID) binding.get("revisionId");
    if (binding.isEmpty()) {
      jdbc.update("""
          insert into exam_plan(id,org_id,plan_no,name,legacy_exam_task_id,settings,created_by,updated_by)
          values (?,?,?,?,?,?::jsonb,?,?)
          """, plan, org, "LEGACY-" + task, sourceTask.get("name"), task,
          mapper.createObjectNode().put("publicationScope", LegacyPublicationIntegrity.NOTICE).toString(), actor, actor);
    } else {
      // An obsolete request must not remain in the review queue; its evidence is retained.
      jdbc.update("""
          update exam_plan_approval_request set status='CANCELLED',updated_by=?,updated_at=now(),version=version+1
          where org_id=? and revision_id=? and status='PENDING'
          """, actor, org, previous);
    }
    int revisionNo = jdbc.queryForObject("select coalesce(max(revision_no),0)+1 from exam_plan_revision where org_id=? and plan_id=?",
        Integer.class, org, plan);
    UUID revision = UUID.randomUUID();
    String configuration = mapper.createObjectNode().put("mode", LegacyPublicationIntegrity.MODE)
        .put("scopeNotice", LegacyPublicationIntegrity.NOTICE).put("legacyTaskId", task.toString())
        .put("runId", run.getId().toString()).put("runRevisionCursor", run.getRevisionCursor()).toString();
    jdbc.update("""
        insert into exam_plan_revision(id,org_id,plan_id,revision_no,predecessor_id,source_type,title,change_reason,
          configuration,created_by,updated_by) values (?,?,?,?,?,'LEGACY',?,'Legacy schedule approval projection',?::jsonb,?,?)
        """, revision, org, plan, revisionNo, previous, LegacyPublicationIntegrity.NOTICE, configuration, actor, actor);
    project(org, revision, rooms, duties, actor);
    String fingerprint = integrity.fingerprint(org, task, run.getId(), revision);
    jdbc.update("""
        insert into legacy_exam_task_plan_link(org_id,exam_task_id,plan_id,active_revision_id,source_fingerprint,
          projected_run_id,projected_run_revision_cursor)
        values (?,?,?,?,?,?,?)
        on conflict (org_id,exam_task_id) do update set active_revision_id=excluded.active_revision_id,
          source_fingerprint=excluded.source_fingerprint,projected_run_id=excluded.projected_run_id,
          projected_run_revision_cursor=excluded.projected_run_revision_cursor,projected_at=now(),updated_at=now(),
          version=legacy_exam_task_plan_link.version+1
        """, org, task, plan, revision, fingerprint, run.getId(), run.getRevisionCursor());
    jdbc.update("""
        update exam_plan set active_revision_id=?,updated_by=?,updated_at=now(),version=version+1
        where org_id=? and id=? and status not in ('PUBLISHED','EXECUTING','CLOSED','ARCHIVED')
        """, revision, actor, org, plan);
    var validation = plans.validate(plan, revision, actor, 0L);
    if (!validation.valid()) {
      throw notReady(validation.issues().getFirst().code() + ": " + validation.issues().getFirst().message());
    }
    plans.submitApproval(plan, revision, actor, validation.revision().revisionCursor());
    return view(org, task);
  }

  private PublicationView view(UUID org, UUID task) {
    ScheduleRun run = integrity.activeRun(org, task);
    Map<String, Object> binding = integrity.binding(org, task);
    if (binding.isEmpty()) {
      return new PublicationView(task, null, null, run.getId(), run.getRevisionCursor(), null,
          "NOT_SUBMITTED", LegacyPublicationIntegrity.MODE, LegacyPublicationIntegrity.NOTICE, false);
    }
    UUID plan = (UUID) binding.get("planId"), revision = (UUID) binding.get("revisionId");
    var version = plans.revision(plan, revision);
    return new PublicationView(task, plan, revision, run.getId(), run.getRevisionCursor(), version.revisionCursor(),
        version.status(), LegacyPublicationIntegrity.MODE, LegacyPublicationIntegrity.NOTICE,
        integrity.current(org, task, binding));
  }

  private Map<String, Object> requireTask(UUID org, UUID task, boolean lock) {
    List<Map<String, Object>> rows = jdbc.queryForList("select id,name,status,published_at from exam_task where org_id=? and id=?"
        + (lock ? " for update" : ""), org, task);
    if (rows.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "EXAM_TASK_NOT_FOUND", "Exam task not found");
    return rows.getFirst();
  }

  private List<SourceRoom> sourceRooms(UUID org, UUID task) {
    return jdbc.query("""
        select e.id,e.subject_name,e.grade,e.exam_date,e.start_time,e.end_time,e.settings::text,
          c.id classroom_id,c.exam_capacity
        from exam e join exam_room er on er.org_id=e.org_id and er.exam_id=e.id
        join classroom c on c.org_id=er.org_id and c.id=er.classroom_id
        where e.org_id=? and e.exam_task_id=? order by e.id,c.id
        """, (rs, index) -> {
          JsonNode settings = json(rs.getString("settings"));
          int demand = settings.path("seatDemand").asInt(Math.max(1, rs.getInt("exam_capacity")));
          int supervisors = settings.path("supervisorsPerRoom").asInt(2);
          return new SourceRoom(rs.getObject("id", UUID.class), rs.getObject("classroom_id", UUID.class),
              rs.getString("subject_name"), rs.getString("grade"), rs.getObject("exam_date", LocalDate.class),
              rs.getObject("start_time", LocalTime.class), rs.getObject("end_time", LocalTime.class), demand, supervisors);
        }, org, task);
  }

  private List<SourceDuty> sourceDuties(UUID org, UUID run) {
    return jdbc.query("""
        select id,exam_id,classroom_id,teacher_id,role,exam_date,start_time,end_time
        from schedule_assignment where org_id=? and schedule_run_id=? order by id
        """, (rs, index) -> new SourceDuty(rs.getObject("exam_id", UUID.class), rs.getObject("classroom_id", UUID.class),
            rs.getObject("teacher_id", UUID.class), rs.getString("role"), rs.getObject("exam_date", LocalDate.class),
            rs.getObject("start_time", LocalTime.class), rs.getObject("end_time", LocalTime.class)), org, run);
  }

  private void assertSourceReady(UUID org, ScheduleRun run, List<SourceRoom> rooms, List<SourceDuty> duties) {
    if (rooms.isEmpty() || duties.isEmpty()) throw notReady("考试、考场或监考排班为空");
    int examCount = jdbc.queryForObject("select count(*) from exam where org_id=? and exam_task_id=?",
        Integer.class, org, run.getExamTaskId());
    if (rooms.stream().map(SourceRoom::exam).distinct().count() != examCount) {
      throw notReady("每场考试都必须有有效考场，不能遗漏未分配考场的考试");
    }
    for (SourceRoom room : rooms) {
      if (room.demand() < 1 || room.supervisors() < 1 || !room.start().isBefore(room.end())) {
        throw notReady("考试人数、岗位数或时段无效");
      }
      List<SourceDuty> assigned = duties.stream().filter(d -> d.exam().equals(room.exam()) && d.room().equals(room.room())).toList();
      if (assigned.size() != room.supervisors() || assigned.stream().filter(d -> d.role().equals("main")).count() != 1) {
        throw notReady("考场必须覆盖全部监考岗位，且有且仅有一名主监考");
      }
    }
    for (SourceDuty duty : duties) {
      if (rooms.stream().noneMatch(r -> r.exam().equals(duty.exam()) && r.room().equals(duty.room())
          && r.date().equals(duty.date()) && r.start().equals(duty.start()) && r.end().equals(duty.end()))) {
        throw notReady("排班时间或考场与当前考试来源不一致");
      }
    }
    JsonNode policy = mapper.valueToTree(run.getStats()).path("policySnapshot");
    if (policy.path("avoidHomeroomClass").asBoolean(true)) {
      int conflicts = jdbc.queryForObject("""
          select count(*) from schedule_assignment a join teacher t on t.org_id=a.org_id and t.id=a.teacher_id
          where a.org_id=? and a.schedule_run_id=? and exists (
            select 1 from exam_class ec join school_class c on c.org_id=ec.org_id and c.id=ec.class_id
            where ec.org_id=a.org_id and ec.exam_id=a.exam_id
              and (c.homeroom_teacher_id=t.id or c.id=t.homeroom_class_id))
          """, Integer.class, org, run.getId());
      if (conflicts > 0) throw notReady("H3_HOMEROOM_AVOID: 班主任不得监考本班");
    }
    if ("HARD".equals(policy.path("sameGrade").asText())) {
      int conflicts = jdbc.queryForObject("""
          select count(*) from schedule_assignment a
          join teacher t on t.org_id=a.org_id and t.id=a.teacher_id
          join exam e on e.org_id=a.org_id and e.id=a.exam_id
          where a.org_id=? and a.schedule_run_id=? and t.grade=e.grade and t.grade is not null
          """, Integer.class, org, run.getId());
      if (conflicts > 0) throw notReady("同年级硬回避规则未满足");
    }
  }

  private void project(UUID org, UUID revision, List<SourceRoom> rooms, List<SourceDuty> duties, UUID actor) {
    Map<UUID, UUID> sessions = new LinkedHashMap<>();
    for (SourceRoom room : rooms) {
      UUID session = sessions.get(room.exam());
      if (session == null) {
        session = UUID.randomUUID(); sessions.put(room.exam(), session);
        int candidates = rooms.stream().filter(r -> r.exam().equals(room.exam())).mapToInt(SourceRoom::demand).sum();
        jdbc.update("""
            insert into exam_plan_session(id,org_id,revision_id,exam_id,session_code,name,subject_name,grade,
              starts_at,ends_at,candidate_count,source_type,requirements,created_by,updated_by)
            values (?,?,?,?,?,?,?,?,?,?,?,'LEGACY',?::jsonb,?,?)
            """, session, org, revision, room.exam(), room.exam().toString(), room.subject(), room.subject(), room.grade(),
            room.starts(), room.ends(), candidates,
            mapper.createObjectNode().put("publicationScope", LegacyPublicationIntegrity.NOTICE).toString(), actor, actor);
      }
      UUID allocation = UUID.randomUUID();
      jdbc.update("""
          insert into exam_plan_room_assignment(id,org_id,revision_id,session_id,classroom_id,starts_at,ends_at,
            planned_capacity,allocated_candidates,created_by,updated_by) values (?,?,?,?,?,?,?,?,?,?,?)
          """, allocation, org, revision, session, room.room(), room.starts(), room.ends(), room.demand(), room.demand(), actor, actor);
      for (String role : List.of("main", "assistant")) {
        int required = role.equals("main") ? 1 : room.supervisors()-1;
        if (required == 0) continue;
        UUID requirement = UUID.randomUUID();
        String dutyRole = role.equals("main") ? "CHIEF" : "INVIGILATOR";
        String qualifications = role.equals("main") ? "{\"requiredQualifications\":[\"INVIGILATOR\",\"CHIEF\"]}"
            : "{\"requiredQualifications\":[\"INVIGILATOR\"]}";
        jdbc.update("""
            insert into exam_plan_duty_requirement(id,org_id,revision_id,session_id,room_assignment_id,duty_role,
              required_count,qualification,created_by,updated_by) values (?,?,?,?,?,?,?,?::jsonb,?,?)
            """, requirement, org, revision, session, allocation, dutyRole, required, qualifications, actor, actor);
        for (SourceDuty duty : duties) {
          if (!duty.exam().equals(room.exam()) || !duty.room().equals(room.room()) || !duty.role().equals(role)) continue;
          jdbc.update("""
              insert into exam_plan_duty_assignment(org_id,revision_id,requirement_id,teacher_id,starts_at,ends_at,
                duty_role,source,created_by,updated_by) values (?,?,?,?,?,?,?,'MANUAL',?,?)
              """, org, revision, requirement, duty.teacher(), room.starts(), room.ends(), dutyRole, actor, actor);
        }
      }
    }
  }

  private JsonNode json(String value) {
    try { return mapper.readTree(value); }
    catch (Exception error) { throw notReady("考试设置无法解析"); }
  }

  private ApiException notReady(String message) {
    return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "LEGACY_SCHEDULE_NOT_READY", message);
  }

  private record SourceRoom(UUID exam, UUID room, String subject, String grade, LocalDate date,
      LocalTime start, LocalTime end, int demand, int supervisors) {
    Timestamp starts() { return Timestamp.from(date.atTime(start).atZone(SCHOOL_ZONE).toInstant()); }
    Timestamp ends() { return Timestamp.from(date.atTime(end).atZone(SCHOOL_ZONE).toInstant()); }
  }
  private record SourceDuty(UUID exam, UUID room, UUID teacher, String role, LocalDate date, LocalTime start, LocalTime end) {}
}
