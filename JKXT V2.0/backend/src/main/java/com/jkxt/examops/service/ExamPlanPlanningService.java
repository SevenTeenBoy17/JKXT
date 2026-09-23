package com.jkxt.examops.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jkxt.common.tenant.TenantContext;
import com.jkxt.common.web.ApiException;
import com.jkxt.examops.api.PlanningJobDtos.PlanningJobRequest;
import com.jkxt.examops.api.PlanningJobDtos.PlanningJobView;
import com.jkxt.examops.joint.DeterministicJointPlanner;
import com.jkxt.examops.joint.JointPlanningModel.Diagnostic;
import com.jkxt.examops.joint.JointPlanningModel.DutyAssignment;
import com.jkxt.examops.joint.JointPlanningModel.ExamDemand;
import com.jkxt.examops.joint.JointPlanningModel.PlanningControl;
import com.jkxt.examops.joint.JointPlanningModel.Result;
import com.jkxt.examops.joint.JointPlanningModel.ResultStatus;
import com.jkxt.examops.joint.JointPlanningModel.RoomAssignment;
import com.jkxt.examops.joint.JointPlanningModel.SeatAssignment;
import com.jkxt.examops.joint.JointPlanningModel.SessionAssignment;
import com.jkxt.examops.joint.JointPlanningModel.SpecialRequirement;
import com.jkxt.examops.service.JointPlanningSnapshotLoader.CandidateMetadata;
import com.jkxt.examops.service.JointPlanningSnapshotLoader.DemandClassKey;
import com.jkxt.examops.service.JointPlanningSnapshotLoader.DemandMetadata;
import com.jkxt.examops.service.JointPlanningSnapshotLoader.DemandRoomKey;
import com.jkxt.examops.service.JointPlanningSnapshotLoader.LoadedSnapshot;
import com.jkxt.examops.service.JointPlanningSnapshotLoader.LockedDutyKey;
import com.jkxt.examops.service.JointPlanningSnapshotLoader.RoomMetadata;
import com.jkxt.examops.service.JointPlanningSnapshotLoader.SeatMetadata;
import com.jkxt.scheduling.solver.ScheduleFixture;
import com.jkxt.scheduling.solver.ScheduleFixture.ExamRoomInput;
import com.jkxt.scheduling.solver.ScheduleFixture.RoomInput;
import com.jkxt.scheduling.solver.ScheduleFixture.TeacherInput;
import com.jkxt.scheduling.solver.ScheduleFixture.TeacherWindow;
import com.jkxt.scheduling.solver.ScheduleSolverResult;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class ExamPlanPlanningService {

  private static final ZoneId SCHOOL_ZONE = ZoneId.of("Asia/Shanghai");
  private static final Set<String> IMMUTABLE = Set.of("PUBLISHED", "EXECUTING", "CLOSED", "ARCHIVED");

  private final JdbcTemplate jdbcTemplate;
  private final ObjectMapper objectMapper;
  private final DeterministicInvigilatorAllocator allocator;
  private final ExamOperationsHardConstraints hardConstraints;
  private final JointPlanningSnapshotLoader jointSnapshotLoader;
  private final DeterministicJointPlanner jointPlanner;
  private final Executor executor;
  private final TransactionTemplate tx;

  public ExamPlanPlanningService(
      JdbcTemplate jdbcTemplate,
      ObjectMapper objectMapper,
      DeterministicInvigilatorAllocator allocator,
      ExamOperationsHardConstraints hardConstraints,
      JointPlanningSnapshotLoader jointSnapshotLoader,
      @Qualifier("scheduleTaskExecutor") Executor executor,
      PlatformTransactionManager transactionManager
  ) {
    this.jdbcTemplate = jdbcTemplate;
    this.objectMapper = objectMapper;
    this.allocator = allocator;
    this.hardConstraints = hardConstraints;
    this.jointSnapshotLoader = jointSnapshotLoader;
    this.jointPlanner = new DeterministicJointPlanner();
    this.executor = executor;
    this.tx = new TransactionTemplate(transactionManager);
  }

  @Transactional
  public PlanningJobView start(
      UUID planId,
      UUID revisionId,
      PlanningJobRequest request,
      String idempotencyKey,
      UUID actorId
  ) {
    UUID orgId = TenantContext.getRequired();
    String key = requireKey(idempotencyKey);
    String operation = "plan-revision:planning:" + revisionId;
    PlanningJobRequest normalized = request == null
        ? new PlanningJobRequest("INVIGILATOR_ONLY", 20260831L, true, 120)
        : request;
    String mode = normalized.mode() == null
        ? "INVIGILATOR_ONLY" : normalized.mode().trim().toUpperCase(Locale.ROOT);
    if (!Set.of("INVIGILATOR_ONLY", "JOINT").contains(mode)) {
      throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "PLANNING_MODE_UNSUPPORTED",
          "mode must be INVIGILATOR_ONLY or JOINT");
    }
    long seed = normalized.seed() == null ? 20260831L : normalized.seed();
    boolean failOnPartial = normalized.failOnPartialCoverage() == null
        || normalized.failOnPartialCoverage();
    int timeoutSeconds = normalized.timeoutSeconds() == null ? 120 : normalized.timeoutSeconds();
    int minimumTimeout = "JOINT".equals(mode) ? 0 : 1;
    if (timeoutSeconds < minimumTimeout || timeoutSeconds > 600) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "PLANNING_REQUEST_INVALID",
          "timeoutSeconds must be between " + minimumTimeout + " and 600");
    }
    PlanningJobRequest plannedRequest = new PlanningJobRequest(mode, seed, failOnPartial, timeoutSeconds);
    String plannedRequestHash = requestHash(plannedRequest);
    // Serialize starts per revision, not per idempotency key. Different request keys must never
    // create two active jobs that can race to replace the same assignment set.
    jdbcTemplate.queryForObject("select pg_advisory_xact_lock(hashtext(?), hashtext(?))", Object.class,
        orgId.toString(), operation);
    UUID existing = idempotent(orgId, operation, key);
    if (existing != null) {
      String existingHash = jdbcTemplate.queryForObject(
          "select request_hash from exam_plan_planning_job where org_id=? and id=?",
          String.class, orgId, existing);
      if (existingHash != null && !existingHash.equals(plannedRequestHash)) {
        throw new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED",
            "Idempotency-Key was already used with a different planning request");
      }
      return get(existing);
    }
    UUID activeJob = jdbcTemplate.query("""
        select id from exam_plan_planning_job
        where org_id=? and revision_id=? and status in ('QUEUED','RUNNING')
        order by created_at limit 1
        """, rs -> rs.next() ? rs.getObject(1, UUID.class) : null, orgId, revisionId);
    if (activeJob != null) {
      throw new ApiException(HttpStatus.CONFLICT, "PLANNING_ALREADY_RUNNING",
          "A planning job is already active for this revision");
    }
    requireMutable(orgId, planId, revisionId);

    UUID jobId = UUID.randomUUID();
    jdbcTemplate.update("""
        insert into exam_plan_planning_job (
          id, org_id, plan_id, revision_id, status, stage, progress, request, request_hash,
          created_by, updated_by
        ) values (?, ?, ?, ?, 'QUEUED', 'QUEUED', 0, ?::jsonb, ?, ?, ?)
        """, jobId, orgId, planId, revisionId, objectMapper.valueToTree(plannedRequest).toString(),
        plannedRequestHash, actorId, actorId);
    remember(orgId, actorId, operation, key, jobId);
    jdbcTemplate.update("""
        update exam_plan_revision set status='SOLVING', updated_by=?, updated_at=now(), version=version+1
        where org_id=? and id=?
        """, actorId, orgId, revisionId);
    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
      @Override
      public void afterCommit() {
        CompletableFuture.runAsync(() -> execute(orgId, planId, revisionId, jobId, actorId, plannedRequest), executor);
      }
    });
    return get(jobId);
  }

  @Transactional(readOnly = true)
  public PlanningJobView get(UUID jobId) {
    UUID orgId = TenantContext.getRequired();
    List<PlanningJobView> rows = jdbcTemplate.query("""
        select id, plan_id, revision_id, status, stage, progress, diagnostics::text, stats::text,
               cancel_requested, started_at, finished_at, created_at, version,
               input_hash, result_hash, solver_version, iteration_count, request_hash
        from exam_plan_planning_job where org_id=? and id=?
        """, this::job, orgId, jobId);
    if (rows.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "PLANNING_JOB_NOT_FOUND", "Planning job is unavailable");
    return rows.getFirst();
  }

  @Transactional
  public PlanningJobView cancel(UUID jobId, UUID actorId) {
    UUID orgId = TenantContext.getRequired();
    int changed = jdbcTemplate.update("""
        update exam_plan_planning_job set cancel_requested=true,
          status=case when status='QUEUED' then 'CANCELLED' else status end,
          stage=case when status='QUEUED' then 'CANCELLED' else stage end,
          finished_at=case when status='QUEUED' then now() else finished_at end,
          updated_by=?, updated_at=now(), version=version+1
        where org_id=? and id=? and status in ('QUEUED','RUNNING')
        """, actorId, orgId, jobId);
    if (changed == 0) return get(jobId);
    PlanningJobView job = get(jobId);
    if ("CANCELLED".equals(job.status())) {
      setAggregateStatus(orgId, job.planId(), job.revisionId(), "DRAFT", actorId);
      return get(jobId);
    }
    return job;
  }

  private void execute(
      UUID orgId,
      UUID planId,
      UUID revisionId,
      UUID jobId,
      UUID actorId,
      PlanningJobRequest request
  ) {
    try {
      if ("JOINT".equals(request.mode())) {
        executeJoint(orgId, planId, revisionId, jobId, actorId, request);
      } else {
        executeInvigilatorOnly(orgId, planId, revisionId, jobId, actorId, request);
      }
    } catch (IllegalArgumentException invalidInput) {
      tx.executeWithoutResult(status -> finishInvalid(
          orgId, planId, revisionId, jobId, actorId, invalidInput.getMessage()));
    } catch (Exception exception) {
      tx.executeWithoutResult(status -> finishFailed(orgId, planId, revisionId, jobId, actorId, exception));
    }
  }

  private void executeInvigilatorOnly(
      UUID orgId,
      UUID planId,
      UUID revisionId,
      UUID jobId,
      UUID actorId,
      PlanningJobRequest request
  ) {
      Boolean started = tx.execute(status -> markRunning(orgId, jobId, actorId));
      if (!Boolean.TRUE.equals(started)) {
        tx.executeWithoutResult(status -> finishCancelled(orgId, planId, revisionId, jobId, actorId));
        return;
      }
      FixtureBuild build = tx.execute(status -> buildFixture(orgId, revisionId, request));
      if (build == null) throw new IllegalStateException("Planning input transaction returned no fixture");
      if (!build.issues().isEmpty()) {
        tx.executeWithoutResult(status -> finishInfeasible(orgId, planId, revisionId, jobId, actorId, build.issues(), build.stats()));
        return;
      }
      if (cancelRequested(orgId, jobId)) {
        tx.executeWithoutResult(status -> finishCancelled(orgId, planId, revisionId, jobId, actorId));
        return;
      }
      ScheduleSolverResult result = allocator.allocate(
          build.fixture(), build.eligibility(), build.roomCampuses(),
          build.dutyQualifications(), build.existingDuties());
      if (!result.feasible()) {
        ArrayNode diagnostics = objectMapper.valueToTree(result.conflictSet());
        if (diagnostics.isEmpty()) diagnostics.add(issue("SCHEDULE_INFEASIBLE", "revision", revisionId,
            result.errorMessage() == null ? "No complete invigilation assignment is feasible" : result.errorMessage(),
            "Review capacity, availability and hard constraints"));
        Map<String, Object> stats = new LinkedHashMap<>(build.stats());
        stats.putAll(result.stats());
        stats.put("solveMillis", result.solveMillis());
        if (result.solverStatus() != null && result.solverStatus().contains("UNKNOWN")) {
          tx.executeWithoutResult(status -> finishUnresolved(orgId, planId, revisionId, jobId, actorId, diagnostics, stats));
        } else {
          tx.executeWithoutResult(status -> finishInfeasible(orgId, planId, revisionId, jobId, actorId, diagnostics, stats));
        }
        return;
      }
      int expected = build.fixture().examRooms().stream().mapToInt(ExamRoomInput::supervisorsPerRoom).sum();
      if (result.assignments().size() != expected) {
        ArrayNode diagnostics = objectMapper.createArrayNode().add(issue(
            "DUTIES_UNCOVERED", "revision", revisionId,
            "Solver returned " + result.assignments().size() + " of " + expected + " required assignments",
            "Add eligible teachers or reduce simultaneous duty demand"));
        tx.executeWithoutResult(status -> finishInfeasible(orgId, planId, revisionId, jobId, actorId, diagnostics, build.stats()));
        return;
      }
      tx.executeWithoutResult(status -> persistSuccess(orgId, planId, revisionId, jobId, actorId, build, result));
  }

  private void executeJoint(
      UUID orgId,
      UUID planId,
      UUID revisionId,
      UUID jobId,
      UUID actorId,
      PlanningJobRequest request
  ) {
    tx.executeWithoutResult(status -> updateQueuedStage(orgId, jobId, actorId, "LOADING_INPUT", 5));
    if (cancelRequested(orgId, jobId)) {
      tx.executeWithoutResult(status -> finishCancelled(orgId, planId, revisionId, jobId, actorId));
      return;
    }
    LoadedSnapshot loaded = tx.execute(status -> jointSnapshotLoader.load(orgId, revisionId));
    if (loaded == null) throw new IllegalStateException("Joint input transaction returned no snapshot");
    long seed = request.seed() == null ? 20260831L : request.seed();
    String inputHash = jointPlanner.canonicalInputHash(loaded.snapshot(), seed);
    String plannedRequestHash = requestHash(request);
    Boolean started = tx.execute(status -> markJointRunning(
        orgId, jobId, actorId, inputHash, plannedRequestHash));
    if (!Boolean.TRUE.equals(started)) {
      tx.executeWithoutResult(status -> finishCancelled(orgId, planId, revisionId, jobId, actorId));
      return;
    }
    tx.executeWithoutResult(status -> updateRunningStage(
        orgId, jobId, actorId, "SOLVING_TIME_ROOM_SEAT_DUTY", 35));
    Instant deadline = Instant.now().plusSeconds(request.timeoutSeconds() == null ? 120 : request.timeoutSeconds());
    Result result = jointPlanner.solve(loaded.snapshot(), new PlanningControl(
        seed, deadline, () -> cancelRequested(orgId, jobId), 64));
    if (!inputHash.equals(result.inputHash())) {
      throw new IllegalStateException("Joint solver input provenance changed during execution");
    }
    Map<String, Object> stats = jointStats(loaded, result);
    ArrayNode diagnostics = objectMapper.valueToTree(result.diagnostics());
    if (result.status() == ResultStatus.SUCCESS) {
      if (cancelRequested(orgId, jobId)) {
        tx.executeWithoutResult(status -> finishCancelled(orgId, planId, revisionId, jobId, actorId));
        return;
      }
      tx.executeWithoutResult(status -> updateRunningStage(
          orgId, jobId, actorId, "PERSISTING_COMPLETE_RESULT", 90));
      tx.executeWithoutResult(status -> persistJointSuccess(
          orgId, planId, revisionId, jobId, actorId, loaded, result, stats));
      return;
    }
    switch (result.status()) {
      case INFEASIBLE -> tx.executeWithoutResult(status -> finishJointTerminal(
          orgId, planId, revisionId, jobId, actorId, "INFEASIBLE", "DIAGNOSTICS",
          "INFEASIBLE", result, diagnostics, stats));
      case CANCELLED -> tx.executeWithoutResult(status -> finishCancelledWithProvenance(
          orgId, planId, revisionId, jobId, actorId, result, diagnostics, stats));
      case DEADLINE_EXCEEDED -> tx.executeWithoutResult(status -> finishJointTerminal(
          orgId, planId, revisionId, jobId, actorId, "FAILED", "DEADLINE_EXCEEDED",
          "FAILED", result, diagnostics, stats));
      default -> throw new IllegalStateException("Unexpected joint planning status " + result.status());
    }
  }

  private FixtureBuild buildFixture(UUID orgId, UUID revisionId, PlanningJobRequest request) {
    List<SessionRoom> slots = jdbcTemplate.query("""
        select s.id as session_id, s.session_code, s.subject_name, s.grade, s.starts_at, s.ends_at,
               s.candidate_count, s.requirements::text, r.id as room_assignment_id,
               r.classroom_id, r.allocated_candidates, c.code as room_code, c.name as room_name,
               c.exam_capacity, c.features::text room_features
        from exam_plan_session s
        left join exam_plan_room_assignment r on r.org_id=s.org_id and r.revision_id=s.revision_id
          and r.session_id=s.id and r.status<>'CANCELLED'
        left join classroom c on c.org_id=r.org_id and c.id=r.classroom_id
        where s.org_id=? and s.revision_id=? and s.status<>'CANCELLED'
        order by s.starts_at, s.session_code, c.code
        """, this::slot, orgId, revisionId);
    ArrayNode issues = objectMapper.createArrayNode();
    if (slots.isEmpty()) {
      issues.add(issue("PLAN_NO_SESSIONS", "revision", revisionId, "No examination sessions are defined", "Upload sessions before planning"));
      return new FixtureBuild(null, issues, Map.of("sessions", 0), Map.of(), Map.of(), Map.of(), Map.of());
    }
    if (slots.stream().anyMatch(slot -> slot.roomId() == null)) {
      slots.stream().filter(slot -> slot.roomId() == null).forEach(slot -> issues.add(issue(
          "SESSION_ROOM_MISSING", "session", slot.sessionId(), "Session " + slot.sessionCode() + " has no room allocation",
          "Upload an active classroom allocation")));
      return new FixtureBuild(null, issues, Map.of("sessions", slots.size()), Map.of(), Map.of(), Map.of(), Map.of());
    }

    List<RequirementRow> persistedRequirements = jdbcTemplate.query("""
        select q.session_id, r.classroom_id, q.duty_role, q.required_count, q.qualification::text
        from exam_plan_duty_requirement q
        left join exam_plan_room_assignment r on r.org_id=q.org_id and r.revision_id=q.revision_id
          and r.id=q.room_assignment_id
        where q.org_id=? and q.revision_id=?
        order by q.session_id, r.classroom_id, q.duty_role, q.created_at
        """, (rs, row) -> new RequirementRow(
        rs.getObject("session_id", UUID.class), rs.getObject("classroom_id", UUID.class),
        rs.getString("duty_role"), rs.getInt("required_count"),
        json(rs.getString("qualification"), "{}")), orgId, revisionId);
    Map<SlotIdentity, List<RequirementRow>> requirementsBySlot = new LinkedHashMap<>();
    for (RequirementRow requirement : persistedRequirements) {
      if (requirement.roomId() == null) {
        issues.add(issue("DUTY_REQUIREMENT_ROOM_MISSING", "session", requirement.sessionId(),
            "A persisted duty requirement is not linked to an active room assignment",
            "Link the requirement to a room before planning"));
        continue;
      }
      requirementsBySlot.computeIfAbsent(
          new SlotIdentity(requirement.sessionId(), requirement.roomId()), ignored -> new ArrayList<>())
          .add(requirement);
    }

    List<TeacherRow> teacherRows = jdbcTemplate.query("""
        select id, teacher_no, name, grade, is_homeroom, homeroom_class_id,
               leave_windows::text, duty_windows::text, qualifications::text, position,
               qualification_expiry, max_daily_sessions, preferred_campus
        from teacher where org_id=? and status='active' order by teacher_no
        """, this::teacher, orgId);
    Map<String, List<TeacherWindow>> constraintLeaves = leaveConstraints(orgId, revisionId);
    List<TeacherInput> teachers = new ArrayList<>();
    Map<UUID, ExamOperationsHardConstraints.TeacherEligibility> eligibility = new LinkedHashMap<>();
    for (TeacherRow row : teacherRows) {
      List<TeacherWindow> leaves = new ArrayList<>(windows(row.leaveWindows()));
      leaves.addAll(constraintLeaves.getOrDefault(row.teacherNo(), List.of()));
      teachers.add(new TeacherInput(row.id(), row.teacherNo(), row.name(), row.grade(), row.homeroom(),
          row.homeroomClassId() == null ? Set.of() : Set.of(row.homeroomClassId()), Set.of(),
          List.copyOf(leaves), windows(row.dutyWindows()),
          new java.util.HashSet<>(objectMapper.convertValue(row.qualifications(), new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {})),
          row.qualificationExpiry()));
      eligibility.put(row.id(), new ExamOperationsHardConstraints.TeacherEligibility(
          row.qualifications(), row.position(), row.homeroom(), row.qualificationExpiry(),
          row.maxDailySessions(), row.preferredCampus()));
    }
    Map<UUID, RoomInput> rooms = new LinkedHashMap<>();
    Map<UUID, String> roomCampuses = new LinkedHashMap<>();
    Map<DeterministicInvigilatorAllocator.DutyKey, JsonNode> dutyQualifications = new LinkedHashMap<>();
    List<ExamRoomInput> examRooms = new ArrayList<>();
    for (SessionRoom slot : slots) {
      rooms.putIfAbsent(slot.roomId(), new RoomInput(slot.roomId(), slot.roomCode(), slot.roomName(), slot.examCapacity()));
      String campus = hardConstraints.campusCode(slot.roomFeatures());
      if (campus != null) roomCampuses.putIfAbsent(slot.roomId(), campus);
      List<RequirementRow> persisted = requirementsBySlot.getOrDefault(
          new SlotIdentity(slot.sessionId(), slot.roomId()), List.of());
      int supervisors = Math.max(1, slot.requiredInvigilators());
      if (!persisted.isEmpty()) {
        List<RequirementRow> chiefs = persisted.stream()
            .filter(requirement -> "CHIEF".equalsIgnoreCase(requirement.role())).toList();
        List<RequirementRow> invigilators = persisted.stream()
            .filter(requirement -> "INVIGILATOR".equalsIgnoreCase(requirement.role())).toList();
        boolean unknownRole = persisted.stream().anyMatch(requirement ->
            !Set.of("CHIEF", "INVIGILATOR").contains(requirement.role().toUpperCase(Locale.ROOT)));
        if (unknownRole || chiefs.size() != 1 || chiefs.getFirst().requiredCount() != 1
            || invigilators.size() > 1) {
          issues.add(issue("DUTY_REQUIREMENT_CONTRACT_INVALID", "session", slot.sessionId(),
              "Persisted duty requirements must contain exactly one CHIEF post and at most one INVIGILATOR group",
              "Correct the role/count contract before planning"));
          continue;
        }
        supervisors = persisted.stream().mapToInt(RequirementRow::requiredCount).sum();
        dutyQualifications.put(new DeterministicInvigilatorAllocator.DutyKey(
            slot.sessionId(), slot.roomId(), "CHIEF"), chiefs.getFirst().qualification());
        if (!invigilators.isEmpty()) {
          dutyQualifications.put(new DeterministicInvigilatorAllocator.DutyKey(
              slot.sessionId(), slot.roomId(), "INVIGILATOR"), invigilators.getFirst().qualification());
        }
      }
      LocalDateTime start = LocalDateTime.ofInstant(slot.startsAt(), SCHOOL_ZONE);
      LocalDateTime end = LocalDateTime.ofInstant(slot.endsAt(), SCHOOL_ZONE);
      examRooms.add(new ExamRoomInput(slot.sessionId(), Set.of(), slot.roomId(), slot.subjectName(), slot.grade(),
          start.toLocalDate(), start.toLocalTime(), end.toLocalTime(), slot.allocatedCandidates(), supervisors, Set.of()));
    }
    long seed = request.seed() == null ? 20260831L : request.seed();
    if (!issues.isEmpty()) {
      return new FixtureBuild(null, issues, Map.of("sessions", slots.size()), eligibility,
          Map.copyOf(roomCampuses), Map.copyOf(dutyQualifications), Map.of());
    }
    int peakRequired = peakRequiredRooms(examRooms);
    if (teachers.size() < peakRequired) {
      issues.add(issue("INVIGILATOR_CAPACITY_SHORTAGE", "teacher", "active-roster",
          "Peak simultaneous demand is " + peakRequired + " but only " + teachers.size() + " active teachers exist",
          "Import/activate more teachers or move sessions to different times"));
      issues.add(issue("DUTIES_UNCOVERED", "revision", revisionId,
          "The available teacher roster cannot cover every required duty", "Resolve teacher capacity before publishing"));
      return new FixtureBuild(null, issues, Map.of("teachers", teachers.size(), "peakRequired", peakRequired),
          eligibility, Map.copyOf(roomCampuses), Map.copyOf(dutyQualifications), Map.of());
    }
    ScheduleFixture fixture = new ScheduleFixture("plan-revision-" + revisionId,
        "Versioned exam operations plan", seed, List.copyOf(teachers), List.copyOf(rooms.values()), List.copyOf(examRooms));
    Map<String, Object> stats = new LinkedHashMap<>();
    stats.put("sessions", slots.stream().map(SessionRoom::sessionId).distinct().count());
    stats.put("rooms", slots.size());
    stats.put("teachers", teachers.size());
    stats.put("dutiesRequired", examRooms.stream().mapToInt(ExamRoomInput::supervisorsPerRoom).sum());
    LocalDate firstDutyDate = slots.stream().map(slot ->
        LocalDateTime.ofInstant(slot.startsAt(), SCHOOL_ZONE).toLocalDate()).min(LocalDate::compareTo).orElse(null);
    stats.put("chiefQualifiedTeachers", eligibility.values().stream().filter(profile ->
        hardConstraints.teacherEligible("CHIEF", hardConstraints.defaultDutyQualification("CHIEF"),
            profile, firstDutyDate)).count());
    stats.put("inputSource", "exam-plan-revision");
    Map<UUID, List<DeterministicInvigilatorAllocator.ExistingDuty>> existingDuties =
        existingPublishedDuties(orgId, revisionId);
    return new FixtureBuild(fixture, issues, stats, Map.copyOf(eligibility), Map.copyOf(roomCampuses),
        Map.copyOf(dutyQualifications), existingDuties);
  }

  private void persistJointSuccess(
      UUID orgId,
      UUID planId,
      UUID revisionId,
      UUID jobId,
      UUID actorId,
      LoadedSnapshot loaded,
      Result result,
      Map<String, Object> stats
  ) {
    Map<String, Object> jobState = jdbcTemplate.queryForMap("""
        select status, cancel_requested from exam_plan_planning_job
        where org_id=? and id=? for update
        """, orgId, jobId);
    if (!"RUNNING".equals(jobState.get("status")) || Boolean.TRUE.equals(jobState.get("cancel_requested"))) {
      finishCancelledWithProvenance(orgId, planId, revisionId, jobId, actorId, result,
          objectMapper.createArrayNode().add(issue("PLANNING_CANCELLED", "planningJob", jobId,
              "Planning was cancelled before the complete result was committed",
              "Start a new planning job when ready")), stats);
      return;
    }
    String revisionStatus = jdbcTemplate.queryForObject("""
        select status from exam_plan_revision where org_id=? and id=? for update
        """, String.class, orgId, revisionId);
    if (!"SOLVING".equals(revisionStatus)) {
      throw new IllegalStateException("Joint result cannot be persisted outside the SOLVING revision state");
    }

    // Build the entire result in memory first, then replace the version graph in one transaction.
    // Any constraint or persistence failure rolls back this deletion and every subsequent insert.
    jdbcTemplate.update("delete from exam_plan_duty_assignment where org_id=? and revision_id=?", orgId, revisionId);
    jdbcTemplate.update("delete from exam_plan_duty_requirement where org_id=? and revision_id=?", orgId, revisionId);
    jdbcTemplate.update("delete from exam_plan_seat_assignment where org_id=? and revision_id=?", orgId, revisionId);
    jdbcTemplate.update("delete from exam_plan_class_session_assignment where org_id=? and revision_id=?", orgId, revisionId);
    jdbcTemplate.update("delete from exam_plan_room_assignment where org_id=? and revision_id=?", orgId, revisionId);
    jdbcTemplate.update("delete from exam_plan_session where org_id=? and revision_id=?", orgId, revisionId);

    Map<UUID, ExamDemand> demands = loaded.snapshot().demands().stream()
        .collect(java.util.stream.Collectors.toMap(ExamDemand::id, value -> value));
    Map<UUID, Long> candidateCounts = result.seats().stream().collect(
        java.util.stream.Collectors.groupingBy(SeatAssignment::demandId, java.util.stream.Collectors.counting()));
    Map<UUID, SessionAssignment> sessionResults = result.sessions().stream()
        .collect(java.util.stream.Collectors.toMap(SessionAssignment::demandId, value -> value));
    Map<UUID, UUID> sessionIds = new LinkedHashMap<>();
    result.sessions().stream().sorted(Comparator.comparing(value -> value.demandId().toString()))
        .forEach(value -> {
          ExamDemand demand = demands.get(value.demandId());
          DemandMetadata metadata = loaded.demands().get(value.demandId());
          if (demand == null || metadata == null) {
            throw new IllegalStateException("Solver session references an unknown demand");
          }
          UUID sessionId = UUID.randomUUID();
          sessionIds.put(value.demandId(), sessionId);
          ObjectNode requirements = objectMapper.createObjectNode()
              .put("requiredInvigilators", demand.invigilatorsPerRoom())
              .put("planningMode", "JOINT");
          jdbcTemplate.update("""
              insert into exam_plan_session (
                id, org_id, revision_id, session_code, name, subject_name, starts_at, ends_at,
                candidate_count, status, requirements, demand_id, source_type, created_by, updated_by
              ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, 'JOINT', ?, ?)
              """, sessionId, orgId, revisionId, metadata.code(), metadata.name(), metadata.subjectName(),
              Timestamp.from(value.start()), Timestamp.from(value.end()),
              Math.toIntExact(candidateCounts.getOrDefault(value.demandId(), 0L)),
              loaded.lockedSessionDemands().contains(value.demandId()) ? "LOCKED" : "PLANNED",
              requirements.toString(), value.demandId(), actorId, actorId);
        });

    for (Map.Entry<DemandClassKey, UUID> entry : loaded.demandClassIds().entrySet()) {
      DemandClassKey key = entry.getKey();
      UUID sessionId = sessionIds.get(key.demandId());
      SessionAssignment session = sessionResults.get(key.demandId());
      if (sessionId == null || session == null) {
        throw new IllegalStateException("Demand/class membership has no planned session");
      }
      jdbcTemplate.update("""
          insert into exam_plan_class_session_assignment (
            org_id, revision_id, session_id, demand_id, demand_class_id, class_id,
            starts_at, ends_at, source_type, status, created_by, updated_by
          ) values (?, ?, ?, ?, ?, ?, ?, ?, 'JOINT', 'ASSIGNED', ?, ?)
          """, orgId, revisionId, sessionId, key.demandId(), entry.getValue(), key.classId(),
          Timestamp.from(session.start()), Timestamp.from(session.end()), actorId, actorId);
    }

    Map<DemandRoomKey, UUID> roomAssignmentIds = new LinkedHashMap<>();
    result.rooms().stream().sorted(Comparator
        .comparing((RoomAssignment value) -> value.demandId().toString())
        .thenComparingInt(RoomAssignment::splitIndex)
        .thenComparing(value -> value.roomId().toString())).forEach(value -> {
      UUID sessionId = sessionIds.get(value.demandId());
      SessionAssignment session = sessionResults.get(value.demandId());
      RoomMetadata room = loaded.rooms().get(value.roomId());
      ExamDemand demand = demands.get(value.demandId());
      if (sessionId == null || session == null || room == null || demand == null) {
        throw new IllegalStateException("Solver room assignment references an unknown version resource");
      }
      UUID assignmentId = UUID.randomUUID();
      DemandRoomKey key = new DemandRoomKey(value.demandId(), value.roomId());
      roomAssignmentIds.put(key, assignmentId);
      jdbcTemplate.update("""
          insert into exam_plan_room_assignment (
            id, org_id, revision_id, session_id, classroom_id, starts_at, ends_at,
            planned_capacity, allocated_candidates, status, required_features, created_by, updated_by
          ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?)
          """, assignmentId, orgId, revisionId, sessionId, value.roomId(),
          Timestamp.from(session.start()), Timestamp.from(session.end()), room.examCapacity(),
          value.allocatedCandidates(), loaded.lockedRooms().contains(key) ? "LOCKED" : "PLANNED",
          objectMapper.valueToTree(demand.requiredRoomFeatures()).toString(), actorId, actorId);
    });

    Map<UUID, CandidateMetadata> candidateMetadata = loaded.candidates();
    result.seats().stream().sorted(Comparator
        .comparing((SeatAssignment value) -> value.demandId().toString())
        .thenComparing(value -> value.candidateId().toString())).forEach(value -> {
      UUID sessionId = sessionIds.get(value.demandId());
      UUID roomAssignmentId = roomAssignmentIds.get(new DemandRoomKey(value.demandId(), value.roomId()));
      CandidateMetadata candidate = candidateMetadata.get(value.candidateId());
      SeatMetadata seat = loaded.seats().get(value.seatId());
      if (sessionId == null || roomAssignmentId == null || candidate == null || seat == null) {
        throw new IllegalStateException("Solver seat assignment references an unknown version resource");
      }
      jdbcTemplate.update("""
          insert into exam_plan_seat_assignment (
            org_id, revision_id, session_id, room_assignment_id, candidate_external_id,
            seat_label, special_arrangement, status, candidate_id, seat_id, starts_at, ends_at,
            source_type, created_by, updated_by
          ) values (?, ?, ?, ?, ?, ?, ?::jsonb, 'ASSIGNED', ?, ?, ?, ?, 'JOINT', ?, ?)
          """, orgId, revisionId, sessionId, roomAssignmentId, candidate.code(), seat.code(),
          specialArrangement(loaded, value).toString(), value.candidateId(), value.seatId(),
          Timestamp.from(value.start()), Timestamp.from(value.end()), actorId, actorId);
    });

    Map<DutyGroupKey, List<DutyAssignment>> duties = result.duties().stream().collect(
        java.util.stream.Collectors.groupingBy(
            value -> new DutyGroupKey(value.demandId(), value.roomId(), value.role()),
            LinkedHashMap::new, java.util.stream.Collectors.toList()));
    for (Map.Entry<DutyGroupKey, List<DutyAssignment>> entry : duties.entrySet()) {
      DutyGroupKey key = entry.getKey();
      List<DutyAssignment> assignments = entry.getValue().stream()
          .sorted(Comparator.comparingInt(DutyAssignment::position)).toList();
      UUID sessionId = sessionIds.get(key.demandId());
      UUID roomAssignmentId = roomAssignmentIds.get(new DemandRoomKey(key.demandId(), key.roomId()));
      if (sessionId == null || roomAssignmentId == null) {
        throw new IllegalStateException("Solver duty assignment references an unknown session room");
      }
      UUID requirementId = UUID.randomUUID();
      jdbcTemplate.update("""
          insert into exam_plan_duty_requirement (
            id, org_id, revision_id, session_id, room_assignment_id, duty_role,
            required_count, qualification, created_by, updated_by
          ) values (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?)
          """, requirementId, orgId, revisionId, sessionId, roomAssignmentId, key.role(),
          assignments.size(), hardConstraints.defaultDutyQualification(key.role()).toString(), actorId, actorId);
      for (DutyAssignment assignment : assignments) {
        LockedDutyKey lockKey = new LockedDutyKey(assignment.demandId(), assignment.roomId(),
            assignment.role(), assignment.position());
        boolean locked = assignment.teacherId().equals(loaded.lockedDuties().get(lockKey));
        jdbcTemplate.update("""
            insert into exam_plan_duty_assignment (
              org_id, revision_id, requirement_id, teacher_id, starts_at, ends_at,
              duty_role, status, source, created_by, updated_by
            ) values (?, ?, ?, ?, ?, ?, ?, 'DRAFT', ?, ?, ?)
            """, orgId, revisionId, requirementId, assignment.teacherId(),
            Timestamp.from(assignment.start()), Timestamp.from(assignment.end()), assignment.role(),
            locked ? "MANUAL" : "SOLVER", actorId, actorId);
      }
    }

    jdbcTemplate.update("""
        update exam_plan_planning_job set status='SUCCEEDED', stage='COMPLETE', progress=100,
          diagnostics='[]'::jsonb, stats=?::jsonb, result_hash=?, iteration_count=?,
          finished_at=now(), updated_by=?, updated_at=now(), version=version+1
        where org_id=? and id=? and status='RUNNING'
        """, objectMapper.valueToTree(stats).toString(), result.resultHash(), result.iterationCount(),
        actorId, orgId, jobId);
    jdbcTemplate.update("""
        update exam_plan_revision set status='REVIEW_REQUIRED', updated_by=?, updated_at=now(), version=version+1
        where org_id=? and id=?
        """, actorId, orgId, revisionId);
    jdbcTemplate.update("""
        update exam_plan set status='REVIEW_REQUIRED', active_revision_id=?,
          updated_by=?, updated_at=now(), version=version+1
        where org_id=? and id=? and status not in ('PUBLISHED','EXECUTING','CLOSED','ARCHIVED')
        """, revisionId, actorId, orgId, planId);
  }

  private ObjectNode specialArrangement(LoadedSnapshot loaded, SeatAssignment assignment) {
    List<SpecialRequirement> requirements = loaded.snapshot().specialRequirements().stream()
        .filter(value -> value.candidateId().equals(assignment.candidateId()))
        .filter(value -> value.demandId() == null || value.demandId().equals(assignment.demandId()))
        .toList();
    ObjectNode result = objectMapper.createObjectNode();
    result.set("requirementCodes", objectMapper.valueToTree(
        requirements.stream().map(SpecialRequirement::requirementCode).sorted().toList()));
    result.set("requiredFeatures", objectMapper.valueToTree(requirements.stream()
        .flatMap(value -> value.requiredFeatures().stream()).distinct().sorted().toList()));
    result.put("extraMinutes", requirements.stream().mapToInt(SpecialRequirement::extraMinutes).max().orElse(0));
    result.put("separateRoom", requirements.stream().anyMatch(SpecialRequirement::separateRoom));
    return result;
  }

  private Map<String, Object> jointStats(LoadedSnapshot loaded, Result result) {
    Map<String, Object> stats = new LinkedHashMap<>(result.metrics());
    stats.put("mode", "JOINT");
    stats.put("demands", loaded.snapshot().demands().size());
    stats.put("candidates", loaded.snapshot().candidates().size());
    stats.put("rooms", loaded.snapshot().rooms().size());
    stats.put("teachers", loaded.snapshot().teachers().size());
    stats.put("sessions", result.sessions().size());
    stats.put("roomAssignments", result.rooms().size());
    stats.put("seatAssignments", result.seats().size());
    stats.put("dutyAssignments", result.duties().size());
    stats.put("partialAssignmentsExposed", false);
    if (result.objective() != null) stats.put("objective", result.objective());
    return Map.copyOf(stats);
  }

  private void persistSuccess(
      UUID orgId, UUID planId, UUID revisionId, UUID jobId, UUID actorId,
      FixtureBuild build, ScheduleSolverResult result
  ) {
    if (cancelRequested(orgId, jobId)) {
      finishCancelled(orgId, planId, revisionId, jobId, actorId);
      return;
    }
    jdbcTemplate.update("delete from exam_plan_duty_assignment where org_id=? and revision_id=?", orgId, revisionId);
    jdbcTemplate.update("delete from exam_plan_duty_requirement where org_id=? and revision_id=?", orgId, revisionId);
    Map<SlotRole, UUID> requirements = new HashMap<>();
    for (ExamRoomInput slot : build.fixture().examRooms()) {
      UUID chief = insertRequirement(orgId, revisionId, slot.examId(), slot.roomId(), "CHIEF", 1,
          dutyQualification(build, slot, "CHIEF"), actorId);
      requirements.put(new SlotRole(slot.examId(), slot.roomId(), "CHIEF"), chief);
      int assistants = slot.supervisorsPerRoom() - 1;
      if (assistants > 0) {
        UUID invigilator = insertRequirement(orgId, revisionId, slot.examId(), slot.roomId(), "INVIGILATOR", assistants,
            dutyQualification(build, slot, "INVIGILATOR"), actorId);
        requirements.put(new SlotRole(slot.examId(), slot.roomId(), "INVIGILATOR"), invigilator);
      }
    }
    for (ScheduleSolverResult.Assignment assignment : result.assignments()) {
      String role = "main".equalsIgnoreCase(assignment.role()) ? "CHIEF" : "INVIGILATOR";
      UUID requirement = requirements.get(new SlotRole(assignment.examId(), assignment.roomId(), role));
      if (requirement == null) throw new IllegalStateException("Solver assignment did not map to a duty requirement");
      Instant start = assignment.date().atTime(assignment.start()).atZone(SCHOOL_ZONE).toInstant();
      Instant end = assignment.date().atTime(assignment.end()).atZone(SCHOOL_ZONE).toInstant();
      jdbcTemplate.update("""
          insert into exam_plan_duty_assignment (
            org_id, revision_id, requirement_id, teacher_id, starts_at, ends_at,
            duty_role, status, source, created_by, updated_by
          ) values (?, ?, ?, ?, ?, ?, ?, 'DRAFT', 'SOLVER', ?, ?)
          """, orgId, revisionId, requirement, assignment.teacherId(), Timestamp.from(start), Timestamp.from(end),
          role, actorId, actorId);
    }
    Map<String, Object> stats = new LinkedHashMap<>(build.stats());
    stats.putAll(result.stats());
    stats.put("assignments", result.assignments().size());
    stats.put("solveMillis", result.solveMillis());
    jdbcTemplate.update("""
        update exam_plan_planning_job set status='SUCCEEDED', stage='COMPLETE', progress=100,
          diagnostics='[]'::jsonb, stats=?::jsonb, finished_at=now(), updated_by=?, updated_at=now(), version=version+1
        where org_id=? and id=?
        """, objectMapper.valueToTree(stats).toString(), actorId, orgId, jobId);
    jdbcTemplate.update("""
        update exam_plan_revision set status='REVIEW_REQUIRED', updated_by=?, updated_at=now(), version=version+1
        where org_id=? and id=?
        """, actorId, orgId, revisionId);
    jdbcTemplate.update("""
        update exam_plan set status='REVIEW_REQUIRED', active_revision_id=?, updated_by=?, updated_at=now(), version=version+1
        where org_id=? and id=? and status not in ('PUBLISHED','EXECUTING','CLOSED','ARCHIVED')
        """, revisionId, actorId, orgId, planId);
  }

  private UUID insertRequirement(UUID orgId, UUID revisionId, UUID sessionId, UUID roomId,
      String role, int count, JsonNode qualification, UUID actorId) {
    UUID roomAssignment = jdbcTemplate.query("""
        select id from exam_plan_room_assignment
        where org_id=? and revision_id=? and session_id=? and classroom_id=? and status<>'CANCELLED'
        """, rs -> rs.next() ? rs.getObject(1, UUID.class) : null, orgId, revisionId, sessionId, roomId);
    if (roomAssignment == null) throw new IllegalStateException("Room assignment disappeared during planning");
    UUID id = UUID.randomUUID();
    jdbcTemplate.update("""
        insert into exam_plan_duty_requirement (
          id, org_id, revision_id, session_id, room_assignment_id, duty_role,
          required_count, qualification, created_by, updated_by
        ) values (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?)
        """, id, orgId, revisionId, sessionId, roomAssignment, role, count,
        qualification.toString(), actorId, actorId);
    return id;
  }

  private JsonNode dutyQualification(FixtureBuild build, ExamRoomInput slot, String role) {
    return build.dutyQualifications().getOrDefault(
        new DeterministicInvigilatorAllocator.DutyKey(slot.examId(), slot.roomId(), role),
        hardConstraints.defaultDutyQualification(role));
  }

  private boolean markRunning(UUID orgId, UUID jobId, UUID actorId) {
    return jdbcTemplate.update("""
        update exam_plan_planning_job set status='RUNNING', stage='SOLVING', progress=20,
          solver_version='INVIGILATOR_ONLY', started_at=now(),
          updated_by=?, updated_at=now(), version=version+1
        where org_id=? and id=? and status='QUEUED' and cancel_requested=false
        """, actorId, orgId, jobId) == 1;
  }

  private void updateQueuedStage(UUID orgId, UUID jobId, UUID actorId, String stage, int progress) {
    jdbcTemplate.update("""
        update exam_plan_planning_job set stage=?, progress=?,
          updated_by=?, updated_at=now(), version=version+1
        where org_id=? and id=? and status='QUEUED' and cancel_requested=false
        """, stage, progress, actorId, orgId, jobId);
  }

  private boolean markJointRunning(
      UUID orgId,
      UUID jobId,
      UUID actorId,
      String inputHash,
      String plannedRequestHash
  ) {
    return jdbcTemplate.update("""
        update exam_plan_planning_job set status='RUNNING', stage='SOLVING_JOINT', progress=20,
          input_hash=?, request_hash=?, solver_version=?, started_at=now(),
          updated_by=?, updated_at=now(), version=version+1
        where org_id=? and id=? and status='QUEUED' and cancel_requested=false
        """, inputHash, plannedRequestHash, DeterministicJointPlanner.SOLVER_VERSION,
        actorId, orgId, jobId) == 1;
  }

  private void updateRunningStage(UUID orgId, UUID jobId, UUID actorId, String stage, int progress) {
    jdbcTemplate.update("""
        update exam_plan_planning_job set stage=?, progress=?,
          updated_by=?, updated_at=now(), version=version+1
        where org_id=? and id=? and status='RUNNING'
        """, stage, progress, actorId, orgId, jobId);
  }

  private void finishJointTerminal(
      UUID orgId,
      UUID planId,
      UUID revisionId,
      UUID jobId,
      UUID actorId,
      String jobStatus,
      String stage,
      String aggregateStatus,
      Result result,
      JsonNode diagnostics,
      Map<String, Object> stats
  ) {
    int changed = jdbcTemplate.update("""
        update exam_plan_planning_job set status=?, stage=?, progress=100,
          diagnostics=?::jsonb, stats=?::jsonb, result_hash=?, iteration_count=?,
          finished_at=now(), updated_by=?, updated_at=now(), version=version+1
        where org_id=? and id=? and status='RUNNING'
        """, jobStatus, stage, diagnostics.toString(), objectMapper.valueToTree(stats).toString(),
        result.resultHash(), result.iterationCount(), actorId, orgId, jobId);
    if (changed == 1) setAggregateStatus(orgId, planId, revisionId, aggregateStatus, actorId);
  }

  private void finishCancelledWithProvenance(
      UUID orgId,
      UUID planId,
      UUID revisionId,
      UUID jobId,
      UUID actorId,
      Result result,
      JsonNode diagnostics,
      Map<String, Object> stats
  ) {
    int changed = jdbcTemplate.update("""
        update exam_plan_planning_job set status='CANCELLED', stage='CANCELLED', progress=100,
          diagnostics=?::jsonb, stats=?::jsonb, result_hash=?, iteration_count=?,
          finished_at=now(), updated_by=?, updated_at=now(), version=version+1
        where org_id=? and id=? and status='RUNNING'
        """, diagnostics.toString(), objectMapper.valueToTree(stats).toString(),
        result.resultHash(), result.iterationCount(), actorId, orgId, jobId);
    if (changed == 1) setAggregateStatus(orgId, planId, revisionId, "DRAFT", actorId);
  }

  private void finishInvalid(
      UUID orgId,
      UUID planId,
      UUID revisionId,
      UUID jobId,
      UUID actorId,
      String message
  ) {
    ArrayNode issues = objectMapper.createArrayNode().add(issue(
        "JOINT_INPUT_INVALID", "revision", revisionId,
        message == null || message.isBlank() ? "Joint planning input is invalid" : message,
        "Repair the version input and start a new planning job"));
    int changed = jdbcTemplate.update("""
        update exam_plan_planning_job set status='INVALID', stage='INPUT_INVALID', progress=100,
          diagnostics=?::jsonb,
          stats=jsonb_set(stats, '{partialAssignmentsExposed}', 'false'::jsonb, true),
          finished_at=now(), updated_by=?, updated_at=now(), version=version+1
        where org_id=? and id=? and status in ('QUEUED','RUNNING')
        """, issues.toString(), actorId, orgId, jobId);
    if (changed == 1) setAggregateStatus(orgId, planId, revisionId, "INVALID", actorId);
  }

  private void finishInfeasible(UUID orgId, UUID planId, UUID revisionId, UUID jobId, UUID actorId,
      JsonNode issues, Map<String, Object> stats) {
    int changed = jdbcTemplate.update("""
        update exam_plan_planning_job set status='INFEASIBLE', stage='DIAGNOSTICS', progress=100,
          diagnostics=?::jsonb, stats=?::jsonb, finished_at=now(), updated_by=?, updated_at=now(), version=version+1
        where org_id=? and id=? and status in ('QUEUED','RUNNING')
        """, issues.toString(), objectMapper.valueToTree(stats).toString(), actorId, orgId, jobId);
    if (changed == 1) setAggregateStatus(orgId, planId, revisionId, "INFEASIBLE", actorId);
  }

  private void finishUnresolved(UUID orgId, UUID planId, UUID revisionId, UUID jobId, UUID actorId,
      JsonNode issues, Map<String, Object> stats) {
    int changed = jdbcTemplate.update("""
        update exam_plan_planning_job set status='FAILED', stage='UNRESOLVED', progress=100,
          diagnostics=?::jsonb, stats=?::jsonb, finished_at=now(), updated_by=?, updated_at=now(), version=version+1
        where org_id=? and id=? and status in ('QUEUED','RUNNING')
        """, issues.toString(), objectMapper.valueToTree(stats).toString(), actorId, orgId, jobId);
    if (changed == 1) setAggregateStatus(orgId, planId, revisionId, "FAILED", actorId);
  }

  private void finishFailed(UUID orgId, UUID planId, UUID revisionId, UUID jobId, UUID actorId, Exception exception) {
    ArrayNode issues = objectMapper.createArrayNode().add(issue("PLANNING_EXECUTION_FAILED", "planningJob", jobId,
        "Planning execution failed without producing a complete assignment",
        "Retry after reviewing server diagnostics; no partial result was published"));
    int changed = jdbcTemplate.update("""
        update exam_plan_planning_job set status='FAILED', stage='FAILED', progress=100,
          diagnostics=?::jsonb, stats=jsonb_set(stats, '{exceptionType}', to_jsonb(?::text), true),
          finished_at=now(), updated_by=?, updated_at=now(), version=version+1
        where org_id=? and id=? and status in ('QUEUED','RUNNING')
        """, issues.toString(), exception.getClass().getSimpleName(), actorId, orgId, jobId);
    if (changed == 1) setAggregateStatus(orgId, planId, revisionId, "FAILED", actorId);
  }

  private void finishCancelled(UUID orgId, UUID planId, UUID revisionId, UUID jobId, UUID actorId) {
    jdbcTemplate.update("""
        update exam_plan_planning_job set status='CANCELLED', stage='CANCELLED', progress=100,
          finished_at=coalesce(finished_at, now()), updated_by=?, updated_at=now(), version=version+1
        where org_id=? and id=? and status in ('QUEUED','RUNNING','CANCELLED')
        """, actorId, orgId, jobId);
    // Cancellation stops this run; it does not cancel the exam plan aggregate.
    setAggregateStatus(orgId, planId, revisionId, "DRAFT", actorId);
  }

  private void setAggregateStatus(UUID orgId, UUID planId, UUID revisionId, String status, UUID actorId) {
    jdbcTemplate.update("update exam_plan_revision set status=?, updated_by=?, updated_at=now(), version=version+1 where org_id=? and id=?",
        status, actorId, orgId, revisionId);
    jdbcTemplate.update("""
        update exam_plan set status=?, updated_by=?, updated_at=now(), version=version+1
        where org_id=? and id=? and status not in ('PUBLISHED','EXECUTING','CLOSED','ARCHIVED')
        """, status, actorId, orgId, planId);
  }

  private Map<UUID, List<DeterministicInvigilatorAllocator.ExistingDuty>> existingPublishedDuties(
      UUID orgId,
      UUID currentRevisionId
  ) {
    Map<UUID, List<DeterministicInvigilatorAllocator.ExistingDuty>> result = new LinkedHashMap<>();
    jdbcTemplate.query("""
        select a.teacher_id, a.starts_at, a.ends_at
        from exam_plan_duty_assignment a
        join exam_plan_revision r on r.org_id=a.org_id and r.id=a.revision_id
        where a.org_id=? and a.revision_id<>? and r.status in ('PUBLISHED','EXECUTING')
          and r.plan_id<>(
            select current_revision.plan_id from exam_plan_revision current_revision
            where current_revision.org_id=? and current_revision.id=?
          )
          and a.status not in ('CANCELLED','DECLINED')
        """, (org.springframework.jdbc.core.RowCallbackHandler) row -> result
        .computeIfAbsent(row.getObject("teacher_id", UUID.class), ignored -> new ArrayList<>())
        .add(new DeterministicInvigilatorAllocator.ExistingDuty(
            LocalDateTime.ofInstant(row.getTimestamp("starts_at").toInstant(), SCHOOL_ZONE),
            LocalDateTime.ofInstant(row.getTimestamp("ends_at").toInstant(), SCHOOL_ZONE))),
        orgId, currentRevisionId, orgId, currentRevisionId);
    result.replaceAll((ignored, duties) -> List.copyOf(duties));
    return Map.copyOf(result);
  }

  private int peakRequired(List<SessionRoom> slots) {
    int peak = 0;
    for (SessionRoom current : slots) {
      int demand = slots.stream()
          .filter(other -> other.startsAt().isBefore(current.endsAt()) && other.endsAt().isAfter(current.startsAt()))
          .mapToInt(SessionRoom::requiredInvigilators).sum();
      peak = Math.max(peak, demand);
    }
    return peak;
  }

  private int peakRequiredRooms(List<ExamRoomInput> slots) {
    int peak = 0;
    for (ExamRoomInput current : slots) {
      LocalDateTime currentStart = current.date().atTime(current.start());
      LocalDateTime currentEnd = current.date().atTime(current.end());
      int demand = slots.stream().filter(other -> {
        LocalDateTime otherStart = other.date().atTime(other.start());
        LocalDateTime otherEnd = other.date().atTime(other.end());
        return otherStart.isBefore(currentEnd) && otherEnd.isAfter(currentStart);
      }).mapToInt(ExamRoomInput::supervisorsPerRoom).sum();
      peak = Math.max(peak, demand);
    }
    return peak;
  }

  private Map<String, List<TeacherWindow>> leaveConstraints(UUID orgId, UUID revisionId) {
    Map<String, List<TeacherWindow>> result = new LinkedHashMap<>();
    jdbcTemplate.query("""
        select resource_id, expression::text from exam_plan_constraint
        where org_id=? and revision_id=? and enabled and severity='HARD' and constraint_type='TEACHER_LEAVE'
        """, rs -> {
          JsonNode expression = json(rs.getString(2), "{}");
          if (!expression.path("approved").asBoolean(true)) return;
          Instant start = parseInstant(expression.path("startsAt").asText(null));
          Instant end = parseInstant(expression.path("endsAt").asText(null));
          if (start != null && end != null && start.isBefore(end)) {
            result.computeIfAbsent(rs.getString(1), ignored -> new ArrayList<>())
                .add(new TeacherWindow(LocalDateTime.ofInstant(start, SCHOOL_ZONE), LocalDateTime.ofInstant(end, SCHOOL_ZONE)));
          }
        }, orgId, revisionId);
    return result;
  }

  private List<TeacherWindow> windows(JsonNode value) {
    if (value == null || !value.isArray()) return List.of();
    List<TeacherWindow> result = new ArrayList<>();
    for (JsonNode item : value) {
      Instant start = parseInstant(item.path("start").asText(item.path("startsAt").asText(null)));
      Instant end = parseInstant(item.path("end").asText(item.path("endsAt").asText(null)));
      if (start != null && end != null && start.isBefore(end)) {
        result.add(new TeacherWindow(LocalDateTime.ofInstant(start, SCHOOL_ZONE), LocalDateTime.ofInstant(end, SCHOOL_ZONE)));
      }
    }
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

  private SessionRoom slot(ResultSet rs, int row) throws SQLException {
    UUID roomId = rs.getObject("classroom_id", UUID.class);
    JsonNode requirements = json(rs.getString("requirements"), "{}");
    return new SessionRoom(
        rs.getObject("session_id", UUID.class), rs.getString("session_code"),
        rs.getString("subject_name"), rs.getString("grade"),
        rs.getTimestamp("starts_at").toInstant(), rs.getTimestamp("ends_at").toInstant(),
        rs.getInt("candidate_count"), Math.max(1, requirements.path("requiredInvigilators").asInt(2)),
        roomId, rs.getObject("room_assignment_id", UUID.class), rs.getString("room_code"),
        rs.getString("room_name"), rs.getInt("exam_capacity"), rs.getInt("allocated_candidates"),
        json(rs.getString("room_features"), "[]"));
  }

  private TeacherRow teacher(ResultSet rs, int row) throws SQLException {
    return new TeacherRow(rs.getObject("id", UUID.class), rs.getString("teacher_no"), rs.getString("name"),
        rs.getString("grade"), rs.getBoolean("is_homeroom"), rs.getObject("homeroom_class_id", UUID.class),
        json(rs.getString("leave_windows"), "[]"), json(rs.getString("duty_windows"), "[]"),
        json(rs.getString("qualifications"), "[]"), rs.getString("position"),
        rs.getObject("qualification_expiry", LocalDate.class), rs.getInt("max_daily_sessions"),
        rs.getString("preferred_campus"));
  }

  private PlanningJobView job(ResultSet rs, int row) throws SQLException {
    UUID id = rs.getObject("id", UUID.class);
    return new PlanningJobView(id, id, rs.getObject("plan_id", UUID.class), rs.getObject("revision_id", UUID.class),
        rs.getString("status"), rs.getString("stage"), rs.getInt("progress"),
        json(rs.getString("diagnostics"), "[]"), json(rs.getString("stats"), "{}"),
        rs.getBoolean("cancel_requested"), instant(rs, "started_at"), instant(rs, "finished_at"),
        instant(rs, "created_at"), rs.getLong("version"), rs.getString("input_hash"),
        rs.getString("result_hash"), rs.getString("solver_version"),
        integer(rs.getObject("iteration_count")), rs.getString("request_hash"));
  }

  private Instant instant(ResultSet rs, String column) throws SQLException {
    Timestamp value = rs.getTimestamp(column);
    return value == null ? null : value.toInstant();
  }

  private Integer integer(Object value) {
    return value == null ? null : ((Number) value).intValue();
  }

  private JsonNode json(String value, String fallback) {
    try {
      return objectMapper.readTree(value == null ? fallback : value);
    } catch (Exception ignored) {
      return "[]".equals(fallback) ? objectMapper.createArrayNode() : objectMapper.createObjectNode();
    }
  }

  private ObjectNode issue(String code, String resourceType, Object resourceId, String message, String suggestion) {
    return objectMapper.createObjectNode().put("code", code).put("severity", "HARD")
        .put("resourceType", resourceType).put("resourceId", String.valueOf(resourceId))
        .put("message", message).put("suggestion", suggestion);
  }

  private void requireMutable(UUID orgId, UUID planId, UUID revisionId) {
    String status = jdbcTemplate.query("""
        select r.status from exam_plan_revision r join exam_plan p on p.org_id=r.org_id and p.id=r.plan_id
        where r.org_id=? and r.plan_id=? and r.id=?
        """, rs -> rs.next() ? rs.getString(1) : null, orgId, planId, revisionId);
    if (status == null) throw new ApiException(HttpStatus.NOT_FOUND, "EXAM_PLAN_NOT_FOUND", "Plan revision is unavailable");
    if (IMMUTABLE.contains(status)) throw new ApiException(HttpStatus.CONFLICT, "REVISION_IMMUTABLE", "Published revision is immutable; create a successor revision");
    if ("SOLVING".equals(status)) throw new ApiException(HttpStatus.CONFLICT, "PLANNING_ALREADY_RUNNING", "A planning job is already running for this revision");
  }

  private String requireKey(String value) {
    if (value == null || value.isBlank()) throw new ApiException(HttpStatus.BAD_REQUEST, "IDEMPOTENCY_KEY_REQUIRED", "Idempotency-Key is required");
    String key = value.trim();
    if (key.length() > 160) throw new ApiException(HttpStatus.BAD_REQUEST, "IDEMPOTENCY_KEY_INVALID", "Idempotency-Key exceeds 160 characters");
    return key;
  }

  private String requestHash(PlanningJobRequest request) {
    String canonical = String.join("|",
        request.mode() == null ? "INVIGILATOR_ONLY" : request.mode().trim().toUpperCase(Locale.ROOT),
        String.valueOf(request.seed() == null ? 20260831L : request.seed()),
        String.valueOf(request.failOnPartialCoverage() == null || request.failOnPartialCoverage()),
        String.valueOf(request.timeoutSeconds() == null ? 120 : request.timeoutSeconds()));
    try {
      return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
          .digest(canonical.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is unavailable", impossible);
    }
  }

  private UUID idempotent(UUID orgId, String operation, String key) {
    return jdbcTemplate.query("select resource_id from api_idempotency_record where org_id=? and operation=? and idempotency_key=?",
        rs -> rs.next() ? rs.getObject(1, UUID.class) : null, orgId, operation, key);
  }

  private void remember(UUID orgId, UUID actorId, String operation, String key, UUID resourceId) {
    jdbcTemplate.update("""
        insert into api_idempotency_record (
          org_id, idempotency_key, operation, resource_type, resource_id, created_by, updated_by
        ) values (?, ?, ?, 'exam_plan_planning_job', ?, ?, ?)
        """, orgId, key, operation, resourceId, actorId, actorId);
  }

  private boolean cancelRequested(UUID orgId, UUID jobId) {
    Boolean value = jdbcTemplate.queryForObject(
        "select cancel_requested from exam_plan_planning_job where org_id=? and id=?", Boolean.class, orgId, jobId);
    return Boolean.TRUE.equals(value);
  }

  private record FixtureBuild(
      ScheduleFixture fixture,
      ArrayNode issues,
      Map<String, Object> stats,
      Map<UUID, ExamOperationsHardConstraints.TeacherEligibility> eligibility,
      Map<UUID, String> roomCampuses,
      Map<DeterministicInvigilatorAllocator.DutyKey, JsonNode> dutyQualifications,
      Map<UUID, List<DeterministicInvigilatorAllocator.ExistingDuty>> existingDuties
  ) {
  }

  private record SessionRoom(
      UUID sessionId, String sessionCode, String subjectName, String grade, Instant startsAt, Instant endsAt,
      int candidateCount, int requiredInvigilators, UUID roomId, UUID roomAssignmentId, String roomCode,
      String roomName, int examCapacity, int allocatedCandidates, JsonNode roomFeatures
  ) {
  }

  private record TeacherRow(
      UUID id, String teacherNo, String name, String grade, boolean homeroom, UUID homeroomClassId,
      JsonNode leaveWindows, JsonNode dutyWindows, JsonNode qualifications, String position,
      LocalDate qualificationExpiry, int maxDailySessions, String preferredCampus
  ) {
  }

  private record RequirementRow(
      UUID sessionId, UUID roomId, String role, int requiredCount, JsonNode qualification
  ) {
  }

  private record SlotIdentity(UUID sessionId, UUID roomId) {
  }

  private record SlotRole(UUID sessionId, UUID roomId, String role) {
  }

  private record DutyGroupKey(UUID demandId, UUID roomId, String role) {
  }
}
