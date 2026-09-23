package com.jkxt.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jkxt.security.PortalHttpFixture;
import com.jkxt.security.PortalHttpFixture.AuthenticatedSession;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Epic 1 killer-bug fix: the scheduler must solve the task's REAL imported data
 * (exams bound to the task + org teacher roster), not a hardcoded fixture.
 *
 * <p>Proves two things the quick-flow test does not:
 * <ol>
 *   <li>scheduling reads data persisted via the {@code /import/{kind}/commit} endpoint, and</li>
 *   <li>decision R2 — a task with no imported exams returns 400 (input incomplete) instead of
 *       silently fabricating a schedule from a fixture.</li>
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RealDataScheduleFlowIntegrationTest {
  @Autowired private org.springframework.context.ApplicationContext application;
  private PortalHttpFixture auth;
  @org.junit.jupiter.api.BeforeEach void prepareAuth(){auth=new PortalHttpFixture(application);}
  @org.junit.jupiter.api.AfterEach void closeAuth(){auth.close();}


  private static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private ObjectMapper objectMapper;

  @Autowired
  private JdbcTemplate jdbcTemplate;

  @Test
  void schedulesRealImportedExamsCommittedViaImportEndpoint() throws Exception {
    AuthenticatedSession session = login();
    String stamp = Long.toString(System.nanoTime());
    String roomCode = "RF-ROOM-" + stamp;
    // A subject name unique to this run so we can assert the assignments come from THIS exam,
    // independent of any other data already in the shared demo org.
    String subject = "RealFlow-" + stamp;

    // 1) Roster: three eligible invigilators (grade 高三, distinct from the exam's 高一) + one big room.
    commit(session, "teachers", null, workbook("teachers.xlsx",
        List.of("teacherNo", "name", "gender", "grade", "qualifications"),
        List.of(
            List.of("RF-T-" + stamp + "-1", "Invigilator One", "female", "高三", "INVIGILATOR,CHIEF"),
            List.of("RF-T-" + stamp + "-2", "Invigilator Two", "male", "高三", "INVIGILATOR,CHIEF"),
            List.of("RF-T-" + stamp + "-3", "Invigilator Three", "female", "高三", "INVIGILATOR,CHIEF")
        )));
    commit(session, "classrooms", null, workbook("rooms.xlsx",
        List.of("code", "name", "capacity", "examCapacity"),
        List.of(List.of(roomCode, "Real Flow Hall", "80", "60"))));

    // 2) A task that imports its own exam schedule bound to it.
    UUID taskId = createTask(session, "Real data schedule flow");
    commit(session, "exams", taskId, workbook("exams.xlsx",
        List.of("subjectName", "grade", "examDate", "startTime", "endTime", "roomCode", "seatDemand", "supervisorsPerRoom"),
        List.of(List.of(subject, "高一", "2026-06-09", "09:00", "10:30", roomCode, "30", "2"))));

    // 3) Schedule -> the solver runs over the imported exam + roster.
    JsonNode started = json(mockMvc.perform(auth.apply(post("/api/v1/exam-tasks/{taskId}/schedule-jobs", taskId),session))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.jobId").exists())
        .andReturn());
    JsonNode done = waitForTerminal(UUID.fromString(started.get("jobId").asText()), session);

    // 4) One exam-room × supervisorsPerRoom(2) = 2 invigilation assignments, all for THIS imported exam.
    assertThat(done.get("status").asText()).isEqualTo("succeeded");
    assertThat(done.get("assignments")).hasSize(2);
    assertThat(done.get("assignments").findValuesAsText("subjectName"))
        .isNotEmpty()
        .allMatch(s -> s.equals(subject));
    // Every assignment references a real teacher from the roster (non-blank teacherNo).
    assertThat(done.get("assignments").findValuesAsText("teacherNo"))
        .hasSize(2)
        .allMatch(no -> no != null && !no.isBlank());
  }

  @Test
  void schedulingTaskWithoutImportedExamsReturnsBadRequest() throws Exception {
    AuthenticatedSession session = login();
    UUID taskId = createTask(session, "Empty task should not schedule");

    // Decision R2: never silently fabricate a schedule — surface the missing input with a stable
    // machine code the frontend can branch on to route the user back to data import.
    mockMvc.perform(auth.apply(post("/api/v1/exam-tasks/{taskId}/schedule-jobs", taskId),session))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("SCHEDULE_INPUT_INCOMPLETE"));

    // The task must NOT have advanced past draft on a rejected schedule attempt.
    mockMvc.perform(auth.apply(get("/api/v1/exam-tasks/{taskId}", taskId),session))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("draft"));
  }

  @Test
  void scheduleCannotUseDisabledOrLeftTeachersToSatisfyDemand() throws Exception {
    AuthenticatedSession session = login();
    RosterSnapshot roster = quarantineExistingRoster();
    String token = UUID.randomUUID().toString().substring(0, 8);
    List<String> testTeachers = new ArrayList<>();
    try {
      String activeNo = "POL-A-" + token;
      String disabledNo = "POL-D-" + token;
      String leftNo = "POL-L-" + token;
      testTeachers.addAll(List.of(activeNo, disabledNo, leftNo));
      createPolicyTeacher(session, activeNo, "active", List.of(), List.of());
      createPolicyTeacher(session, disabledNo, "disabled", List.of(), List.of());
      createPolicyTeacher(session, leftNo, "left", List.of(), List.of());

      // Two supervisors are required but exactly one teacher is active. A correct roster filter
      // therefore reports infeasible; it must never fill the gap with disabled/left teachers.
      JsonNode done = scheduleSingleExam(
          session, "status-" + token, "2026-10-12", "09:00", "10:00", 2);
      assertThat(done.path("status").asText()).isEqualTo("infeasible");
      assertThat(done.path("assignments")).isEmpty();
    } finally {
      disableTestTeachers(roster.orgId(), testTeachers);
      restoreRoster(roster);
    }
  }

  @Test
  void leaveWindowsUseOpenIntervalOverlapAndAllowBoundaryTouching() throws Exception {
    AuthenticatedSession session = login();
    RosterSnapshot roster = quarantineExistingRoster();
    String token = UUID.randomUUID().toString().substring(0, 8);
    List<String> testTeachers = new ArrayList<>();
    try {
      String boundaryNo = "POL-B-" + token;
      testTeachers.add(boundaryNo);
      JsonNode boundaryTeacher = createPolicyTeacher(
          session,
          boundaryNo,
          "active",
          List.of(window("2026-10-12T07:00:00", "2026-10-12T09:00:00", "morning-leave")),
          List.of());

      // Leave ends exactly when the exam starts: touching endpoints do not overlap.
      JsonNode boundaryRun = scheduleSingleExam(
          session, "leave-boundary-" + token, "2026-10-12", "09:00", "10:00", 1);
      assertThat(boundaryRun.path("status").asText()).isEqualTo("succeeded");
      assertThat(boundaryRun.path("assignments").findValuesAsText("teacherId"))
          .containsExactly(boundaryTeacher.path("uuid").asText());

      setTeacherStatus(roster.orgId(), boundaryNo, "disabled");
      String overlapNo = "POL-O-" + token;
      testTeachers.add(overlapNo);
      createPolicyTeacher(
          session,
          overlapNo,
          "active",
          List.of(window("2026-10-12T08:30:00", "2026-10-12T09:30:00", "sick-leave")),
          List.of());

      // This leave overlaps the 09:00-10:00 exam by 30 minutes, so no eligible teacher remains.
      JsonNode overlapRun = scheduleSingleExam(
          session, "leave-overlap-" + token, "2026-10-12", "09:00", "10:00", 1);
      assertThat(overlapRun.path("status").asText()).isEqualTo("infeasible");
      assertThat(overlapRun.path("assignments")).isEmpty();
    } finally {
      disableTestTeachers(roster.orgId(), testTeachers);
      restoreRoster(roster);
    }
  }

  @Test
  void dutyWindowsRestrictAssignmentsWhileAnEmptyListIsUnrestricted() throws Exception {
    AuthenticatedSession session = login();
    RosterSnapshot roster = quarantineExistingRoster();
    String token = UUID.randomUUID().toString().substring(0, 8);
    List<String> testTeachers = new ArrayList<>();
    try {
      String unrestrictedNo = "POL-U-" + token;
      testTeachers.add(unrestrictedNo);
      JsonNode unrestrictedTeacher = createPolicyTeacher(
          session, unrestrictedNo, "active", List.of(), List.of());

      // Empty dutyWindows means the teacher has no duty-window restriction.
      JsonNode unrestrictedRun = scheduleSingleExam(
          session, "duty-empty-" + token, "2026-10-13", "09:00", "10:00", 1);
      assertThat(unrestrictedRun.path("status").asText()).isEqualTo("succeeded");
      assertThat(unrestrictedRun.path("assignments").findValuesAsText("teacherId"))
          .containsExactly(unrestrictedTeacher.path("uuid").asText());

      setTeacherStatus(roster.orgId(), unrestrictedNo, "disabled");
      String afternoonOnlyNo = "POL-W-" + token;
      testTeachers.add(afternoonOnlyNo);
      createPolicyTeacher(
          session,
          afternoonOnlyNo,
          "active",
          List.of(),
          List.of(window("2026-10-13T14:00:00", "2026-10-13T16:00:00", null)));

      // A declared duty window is an allow-list. The 09:00 exam is outside 14:00-16:00.
      JsonNode outsideRun = scheduleSingleExam(
          session, "duty-outside-" + token, "2026-10-13", "09:00", "10:00", 1);
      assertThat(outsideRun.path("status").asText()).isEqualTo("infeasible");
      assertThat(outsideRun.path("assignments")).isEmpty();
    } finally {
      disableTestTeachers(roster.orgId(), testTeachers);
      restoreRoster(roster);
    }
  }

  @Test
  void realTaskExcludesHomeroomTeachersBoundFromBothMasterDataSources() throws Exception {
    AuthenticatedSession session = login();
    RosterSnapshot roster = quarantineExistingRoster();
    String token = UUID.randomUUID().toString().substring(0, 8);
    List<String> testTeachers = new ArrayList<>();
    try {
      String teacherLinkedNo = "POL-HT-" + token;
      String classLinkedNo = "POL-HC-" + token;
      String neutralOneNo = "POL-N1-" + token;
      String neutralTwoNo = "POL-N2-" + token;
      testTeachers.addAll(List.of(teacherLinkedNo, classLinkedNo, neutralOneNo, neutralTwoNo));

      UUID teacherLinkedId = UUID.fromString(createPolicyTeacher(
          session, teacherLinkedNo, "active", List.of(), List.of()).path("uuid").asText());
      UUID classLinkedId = UUID.fromString(createPolicyTeacher(
          session, classLinkedNo, "active", List.of(), List.of()).path("uuid").asText());
      createPolicyTeacher(session, neutralOneNo, "active", List.of(), List.of());
      createPolicyTeacher(session, neutralTwoNo, "active", List.of(), List.of());

      UUID teacherLinkedClassId = UUID.randomUUID();
      UUID classLinkedClassId = UUID.randomUUID();
      jdbcTemplate.update(
          "insert into school_class(id, org_id, code, name, grade, student_count, status) "
              + "values (?, ?, ?, ?, 'POLICY-GRADE', 30, 'active')",
          teacherLinkedClassId, roster.orgId(), "POL-CL-T-" + token, "Teacher-bound class " + token);
      jdbcTemplate.update(
          "insert into school_class(id, org_id, code, name, grade, student_count, homeroom_teacher_id, status) "
              + "values (?, ?, ?, ?, 'POLICY-GRADE', 30, ?, 'active')",
          classLinkedClassId, roster.orgId(), "POL-CL-C-" + token, "Class-bound class " + token,
          classLinkedId);
      jdbcTemplate.update(
          "update teacher set is_homeroom = true, homeroom_class_id = ? where org_id = ? and id = ?",
          teacherLinkedClassId, roster.orgId(), teacherLinkedId);

      String stamp = "homeroom-union-" + token;
      String roomCode = "POL-R-" + stamp;
      commit(session, "classrooms", null, workbook("homeroom-room.xlsx",
          List.of("code", "name", "capacity", "examCapacity"),
          List.of(List.of(roomCode, "Homeroom Union Hall", "80", "60"))));
      UUID taskId = createTask(session, "Homeroom union " + token);
      commit(session, "exams", taskId, workbook("homeroom-exam.xlsx",
          List.of("subjectName", "grade", "examDate", "startTime", "endTime", "roomCode",
              "seatDemand", "supervisorsPerRoom"),
          List.of(List.of("Homeroom-" + token, "POLICY-GRADE", "2026-10-14", "09:00", "10:00",
              roomCode, "30", "3"))));

      UUID examId = jdbcTemplate.queryForObject(
          "select id from exam where org_id = ? and exam_task_id = ?",
          UUID.class, roster.orgId(), taskId);
      jdbcTemplate.update(
          "insert into exam_class(exam_id, class_id, org_id) values (?, ?, ?), (?, ?, ?)",
          examId, teacherLinkedClassId, roster.orgId(), examId, classLinkedClassId, roster.orgId());

      JsonNode started = json(mockMvc.perform(
              auth.apply(post("/api/v1/exam-tasks/{taskId}/schedule-jobs", taskId),session))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.jobId").exists())
          .andReturn());
      JsonNode done = waitForTerminal(UUID.fromString(started.path("jobId").asText()), session);

      // Four active teachers exist, but the two homeroom teachers are excluded through different
      // persisted relationships. Only two neutral teachers remain for three required positions.
      // If either relationship source is ignored the task becomes feasible, so this assertion
      // protects the complete task -> fixture -> solver path for the union logic.
      assertThat(done.path("status").asText()).isEqualTo("infeasible");
      assertThat(done.path("assignments")).isEmpty();
      assertThat(jdbcTemplate.queryForObject(
          "select count(*) from exam_class where org_id = ? and exam_id = ?", Integer.class,
          roster.orgId(), examId)).isEqualTo(2);
    } finally {
      disableTestTeachers(roster.orgId(), testTeachers);
      restoreRoster(roster);
    }
  }

  private JsonNode scheduleSingleExam(
      AuthenticatedSession session,
      String stamp,
      String date,
      String start,
      String end,
      int supervisors
  ) throws Exception {
    String roomCode = "POL-R-" + stamp;
    commit(session, "classrooms", null, workbook("policy-room.xlsx",
        List.of("code", "name", "capacity", "examCapacity"),
        List.of(List.of(roomCode, "Policy Test Room", "80", "60"))));
    UUID taskId = createTask(session, "Teacher policy " + stamp);
    commit(session, "exams", taskId, workbook("policy-exam.xlsx",
        List.of("subjectName", "grade", "examDate", "startTime", "endTime", "roomCode", "seatDemand", "supervisorsPerRoom"),
        List.of(List.of("Policy-" + stamp, "POLICY-GRADE", date, start, end, roomCode, "30",
            Integer.toString(supervisors)))));
    JsonNode started = json(mockMvc.perform(auth.apply(post("/api/v1/exam-tasks/{taskId}/schedule-jobs", taskId),session))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.jobId").exists())
        .andReturn());
    return waitForTerminal(UUID.fromString(started.path("jobId").asText()), session);
  }

  private JsonNode createPolicyTeacher(
      AuthenticatedSession session,
      String teacherNo,
      String teacherStatus,
      List<Map<String, String>> leaveWindows,
      List<Map<String, String>> dutyWindows
  ) throws Exception {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("teacherNo", teacherNo);
    body.put("name", "Policy teacher " + teacherNo);
    body.put("gender", "unknown");
    body.put("grade", "OTHER-GRADE");
    body.put("status", teacherStatus);
    body.put("isHomeroom", false);
    body.put("age", 40);
    body.put("position", "教师");
    body.put("leaveWindows", leaveWindows);
    body.put("functionalAccommodations", List.of());
    body.put("dutyWindows", dutyWindows);
    body.put("qualifications", List.of("INVIGILATOR", "CHIEF"));
    return json(mockMvc.perform(auth.apply(post("/api/v1/teachers")
            ,session)
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsBytes(body)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.uuid").exists())
        .andReturn());
  }

  private Map<String, String> window(String start, String end, String reason) {
    Map<String, String> window = new LinkedHashMap<>();
    window.put("start", start);
    window.put("end", end);
    if (reason != null) {
      window.put("reason", reason);
    }
    return window;
  }

  private RosterSnapshot quarantineExistingRoster() {
    UUID orgId = jdbcTemplate.queryForObject(
        "select org_id from app_user where username = 'jwc.demo' order by created_at limit 1",
        UUID.class);
    Map<UUID, String> statuses = new LinkedHashMap<>();
    for (Map<String, Object> row : jdbcTemplate.queryForList(
        "select id, status from teacher where org_id = ? order by teacher_no", orgId)) {
      statuses.put((UUID) row.get("id"), (String) row.get("status"));
    }
    jdbcTemplate.update("update teacher set status = 'disabled' where org_id = ?", orgId);
    return new RosterSnapshot(orgId, statuses);
  }

  private void setTeacherStatus(UUID orgId, String teacherNo, String teacherStatus) {
    jdbcTemplate.update(
        "update teacher set status = ? where org_id = ? and teacher_no = ?",
        teacherStatus,
        orgId,
        teacherNo);
  }

  private void disableTestTeachers(UUID orgId, List<String> teacherNos) {
    for (String teacherNo : teacherNos) {
      setTeacherStatus(orgId, teacherNo, "disabled");
    }
  }

  private void restoreRoster(RosterSnapshot roster) {
    roster.statuses().forEach((teacherId, teacherStatus) -> jdbcTemplate.update(
        "update teacher set status = ? where org_id = ? and id = ?",
        teacherStatus,
        roster.orgId(),
        teacherId));
  }

  private record RosterSnapshot(UUID orgId, Map<UUID, String> statuses) {
  }

  private UUID createTask(AuthenticatedSession session, String name) throws Exception {
    JsonNode created = json(mockMvc.perform(auth.apply(post("/api/v1/exam-tasks")
            ,session)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"%s\",\"source\":\"quick\"}".formatted(name)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").exists())
        .andReturn());
    return UUID.fromString(created.get("id").asText());
  }

  private void commit(AuthenticatedSession session, String kind, UUID taskId, MockMultipartFile file) throws Exception {
    var request = auth.apply(multipart("/api/v1/import/{kind}/commit", kind).file(file),session);
    if (taskId != null) {
      request = request.param("taskId", taskId.toString());
    }
    mockMvc.perform(request)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.inserted").exists());
  }

  private MockMultipartFile workbook(String filename, List<String> headers, List<List<String>> rows) throws Exception {
    try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      Sheet sheet = workbook.createSheet("data");
      Row header = sheet.createRow(0);
      for (int i = 0; i < headers.size(); i++) {
        header.createCell(i).setCellValue(headers.get(i));
      }
      for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
        Row row = sheet.createRow(rowIndex + 1);
        List<String> values = rows.get(rowIndex);
        for (int cellIndex = 0; cellIndex < values.size(); cellIndex++) {
          row.createCell(cellIndex).setCellValue(values.get(cellIndex));
        }
      }
      workbook.write(output);
      return new MockMultipartFile("file", filename, XLSX, output.toByteArray());
    }
  }

  private JsonNode waitForTerminal(UUID jobId, AuthenticatedSession session) throws Exception {
    // Native CP-SAT initialization can exceed 45 seconds on a cold, resource-constrained CI
    // container. The job itself has a bounded solver timeout; this outer wait only allows the
    // asynchronous worker to finish initialization and publish its terminal snapshot.
    Instant deadline = Instant.now().plus(Duration.ofSeconds(120));
    JsonNode latest = null;
    while (Instant.now().isBefore(deadline)) {
      latest = json(mockMvc.perform(auth.apply(get("/api/v1/schedule-jobs/{jobId}", jobId),session))
          .andExpect(status().isOk())
          .andReturn());
      String s = latest.get("status").asText();
      if ("succeeded".equals(s) || "failed".equals(s) || "infeasible".equals(s)) {
        return latest;
      }
      Thread.sleep(250);
    }
    throw new AssertionError("Schedule job did not finish in time. Last snapshot: " + latest);
  }

  private com.jkxt.security.PortalHttpFixture.AuthenticatedSession login() throws Exception {
    return auth.login(com.jkxt.security.auth.PortalPurpose.SCHOOL,auth.account("ADMIN"));
  }

  private JsonNode json(MvcResult result) throws Exception {
    return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
  }
}
