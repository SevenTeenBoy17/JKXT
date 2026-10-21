package com.jkxt.importer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jkxt.security.api.AuthDtos.CaptchaResponse;
import jakarta.servlet.http.Cookie;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.sql.Date;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.StreamSupport;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Two school-size XLSX/API persistence rounds, NOT two scheduling/publication E2E rounds.
 * Every teacher is explicitly synthetic and created through the real import endpoint.
 * The current Excel schema has no availability/leave columns: those fields are maintained
 * through the real teacher PUT endpoint, never represented as successful Excel imports.
 * No outer test transaction, service mocks, manually injected session, or direct teacher inserts.
 * The test-only TestDatabaseIsolationGuard remains mandatory through spring.factories.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SchoolScaleImportIntegrationTest {
  private static final UUID SEED_ORG = UUID.fromString("00000000-0000-0000-0000-000000000001");
  private static final List<String> HEADERS = List.of("工号", "姓名", "性别", "年级", "任教学科",
      "手机号", "邮箱", "班主任", "所带班级", "监考资格", "资格有效期", "每日监考上限");
  private static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
  private static final List<String> SUBJECTS = List.of("合成语文", "合成数学", "合成英语", "合成物理", "合成历史", "合成体育");
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper mapper;
  @Autowired JdbcTemplate jdbc;
  private final UUID org = UUID.randomUUID();
  private final Map<String, UUID> classes = new LinkedHashMap<>();
  private Cookie session;
  private Path artifacts;

  @BeforeEach
  void freshGuardedSchoolAndAuthenticatedOperator() throws Exception {
    String reports = System.getProperty("jkxt.test.reportsDirectory");
    assertThat(reports).as("an explicit per-run evidence directory is required").isNotBlank();
    Path reportsPath = Path.of(reports).toAbsolutePath().normalize();
    assertThat(reportsPath.getParent()).isNotNull();
    artifacts = reportsPath.resolveSibling("artifacts").resolve("school-scale-import-" + org);
    Files.createDirectories(artifacts);

    // Administrative fixture only. No SQL creates or updates teacher records or subject links.
    assertThat(jdbc.update("insert into organization(id,name,slug) values (?,'SYNTHETIC 学校规模导入验收',?)",
        org, "synthetic-scale-" + org)).isOne();
    assertThat(jdbc.update("""
        insert into role(id,org_id,code,name,permissions,system_role)
        select ?,?,code,name,permissions,true from role where org_id=? and code='ADMIN'
        """, UUID.randomUUID(), org, SEED_ORG)).isOne();
    UUID user = UUID.randomUUID();
    String username = "synthetic.scale." + user.toString().substring(0, 12);
    assertThat(jdbc.update("""
        insert into app_user(id,org_id,username,display_name,email,password_hash,role,status,must_change_password,onboarded)
        select ?,?,?,'SYNTHETIC 导入验收员',?,password_hash,'ADMIN','active',false,true
        from app_user where org_id=? and username='jwc.demo'
        """, user, org, username, username + "@example.invalid", SEED_ORG)).isOne();
    assertThat(jdbc.update("insert into user_role(user_id,role_id,org_id) select ?,id,org_id from role where org_id=? and code='ADMIN'",
        user, org)).isOne();
    CaptchaResponse captcha = mapper.readValue(mvc.perform(post("/api/v1/auth/captcha"))
        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8), CaptchaResponse.class);
    session = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
        .content(mapper.writeValueAsBytes(Map.of("username", username, "password", "Demo@2025",
            "captchaId", captcha.captchaId(), "captcha", captcha.debugAnswer()))))
        .andExpect(status().isOk()).andReturn().getResponse().getCookie("JKXT_SCHOOL_SESSION");
    assertThat(session).isNotNull();
    for (int i = 0; i < SUBJECTS.size(); i++) {
      JsonNode subject = create("/api/v1/subjects", Map.of("code", "SYN-S" + i, "name", SUBJECTS.get(i),
          "category", "main", "examType", "written", "hours", 2, "status", "active"));
      assertThat(subject.path("name").asText()).isEqualTo(SUBJECTS.get(i));
    }
    for (int i = 0; i < 9; i++) {
      String code = "SYN-C" + i;
      JsonNode schoolClass = create("/api/v1/classes", Map.of("code", code, "name", "合成班级" + i,
          "grade", "G" + (10 + i % 3), "stage", "高中", "studentCount", 40, "status", "active"));
      classes.put(code, UUID.fromString(schoolClass.path("uuid").asText()));
    }
    assertThat(jdbc.queryForObject("select count(*) from teacher where org_id=?", Integer.class, org)).isZero();
    // Synthetic schools are retained solely in the disposable, guard-protected test database.
    // The owning runner archives evidence and destroys its exact tmpfs resources after testing.
  }

  @ParameterizedTest(name = "SYNTHETIC {0} teachers: XLSX import, API windows and stable re-import")
  @ValueSource(ints = {120, 150})
  void realSchoolSizeFilesPersistEverySupportedFieldAndPreserveApiWindows(int count) throws Exception {
    List<ExpectedTeacher> expected = teachers(count);
    MockMultipartFile file = workbook("synthetic-" + count + "-teachers", rows(expected));
    JsonNode validation = validate(file);
    assertThat(validation.path("severity").asText()).isEqualTo("pass");
    assertThat(validation.path("counts").path("error").asInt()).isZero();
    assertThat(validation.path("overview").path("teachers").asInt()).isEqualTo(count);
    assertCommit(commit(file), count, count, 0, 0);
    Map<String, UUID> originalIds = assertAllTeachers(expected, false);

    for (ExpectedTeacher teacher : expected) {
      UUID id = originalIds.get(teacher.no());
      JsonNode updated = mapper.readTree(mvc.perform(put("/api/v1/teachers/{id}", id).cookie(session)
          .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(windowUpdate(teacher))))
          .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
      assertApiTeacher(updated, teacher, true);
      JsonNode reread = mapper.readTree(mvc.perform(get("/api/v1/teachers/{id}", id).cookie(session))
          .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
      assertApiTeacher(reread, teacher, true);
      assertDatabaseTeacher(teacher, id, true);
    }
    // Natural-key re-import is an update, not a new teacher, and must not erase API-only windows.
    assertCommit(commit(file), count, 0, count, 0);
    assertThat(assertAllTeachers(expected, true)).isEqualTo(originalIds);

    List<List<String>> duplicateRows = rows(expected);
    List<String> conflictingLaterRow = new ArrayList<>(duplicateRows.get(0));
    conflictingLaterRow.set(1, "SYNTHETIC 后行不应覆盖首行");
    conflictingLaterRow.set(9, "INVIGILATOR");
    conflictingLaterRow.set(11, "12");
    duplicateRows.add(conflictingLaterRow);
    MockMultipartFile duplicate = workbook("synthetic-" + count + "-duplicate-natural-key", duplicateRows);
    assertThat(validate(duplicate).path("counts").path("error").asInt()).isZero();
    JsonNode duplicateResult = commit(duplicate);
    assertCommit(duplicateResult, count + 1, 0, count, 1);
    assertThat(duplicateResult.path("warnings").size()).isOne();
    assertThat(duplicateResult.path("warnings").get(0).asText())
        .contains("第 " + (count + 2) + " 行", expected.get(0).no(), "重复", "已跳过");
    assertThat(assertAllTeachers(expected, true)).isEqualTo(originalIds);
  }

  @ParameterizedTest(name = "SYNTHETIC {0} rows: one invalid cell blocks existing updates and all new rows")
  @ValueSource(ints = {120, 150})
  void oneBadRowRejectsTheWholeSchoolFileWithoutPartialWrites(int count) throws Exception {
    List<ExpectedTeacher> expected = teachers(count);
    assertCommit(commit(workbook("synthetic-" + count + "-atomic-baseline", rows(expected.subList(0, 2)))), 2, 2, 0, 0);
    Map<String, String> before = persistedSnapshot();
    List<List<String>> badRows = rows(expected);
    badRows.get(0).set(1, "SYNTHETIC 不允许部分更新");
    String field = count == 120 ? "name" : "maxDailySessions";
    String errorCode = count == 120 ? "IMPORT_REQUIRED_EMPTY" : "TEACHER_MAX_DAILY_INVALID";
    int badIndex = count - 2; // valid rows before AND after the invalid row
    badRows.get(badIndex).set(count == 120 ? 1 : 11, count == 120 ? "" : "13");
    MockMultipartFile badFile = workbook("synthetic-" + count + "-atomic-invalid", badRows);
    JsonNode validation = validate(badFile);
    assertThat(validation.path("severity").asText()).isEqualTo("error");
    assertThat(validation.path("counts").path("error").asInt()).isOne();
    assertThat(validation.path("overview").path("completeness").asInt()).isZero();
    List<JsonNode> errors = nodes(validation.path("items")).stream()
        .filter(item -> item.path("severity").asText().equals("error")).toList();
    assertThat(errors).hasSize(1);
    assertThat(errors.get(0).path("row").asInt()).isEqualTo(badIndex + 2);
    assertThat(errors.get(0).path("field").asText()).isEqualTo(field);
    assertThat(errors.get(0).path("code").asText()).isEqualTo(errorCode);
    assertThat(persistedSnapshot()).as("validation is read-only").isEqualTo(before);
    mvc.perform(multipart("/api/v1/import/teachers/commit").file(badFile).cookie(session))
        .andExpect(status().isBadRequest());
    assertThat(persistedSnapshot()).as("no existing update, inserted row, relation or import notice escapes rejection").isEqualTo(before);
    assertAllTeachers(expected.subList(0, 2), false);
  }

  private List<ExpectedTeacher> teachers(int count) {
    List<ExpectedTeacher> result = new ArrayList<>();
    for (int i = 0; i < count; i++) {
      List<String> subjects = i % 3 == 0
          ? List.of(SUBJECTS.get(i % 6), SUBJECTS.get((i + 1) % 6)) : List.of(SUBJECTS.get(i % 6));
      result.add(new ExpectedTeacher("SYN-R" + count + "-" + String.format("%03d", i + 1),
          "SYNTHETIC 合成教师" + String.format("%03d", i + 1), i % 2 == 0 ? "female" : "male",
          "G" + (10 + i % 3), subjects, String.format("000%08d", i + 1),
          "teacher" + i + "@" + org + ".example.invalid", i < 9 ? "SYN-C" + i : null,
          i % 3 == 0 ? List.of("INVIGILATOR", "CHIEF") : List.of("INVIGILATOR"),
          LocalDate.of(2040 + i % 4, 12, 31), 1 + i % 4,
          i % 4 == 0 ? List.of(window("2042-07-01T08:00", "2042-07-01T10:00", "SYNTHETIC 仅早场可用"))
              : i % 4 == 1 ? List.of(window("2042-07-01T08:00", "2042-07-01T17:00", "SYNTHETIC 全天可用"))
              : i % 4 == 2 ? List.of(window("2042-07-01T08:00", "2042-07-01T10:00", "SYNTHETIC 早场"),
                  window("2042-07-01T14:00", "2042-07-01T17:00", "SYNTHETIC 晚场")) : List.of(),
          i % 5 == 0 ? List.of(window("2042-07-01T08:30", "2042-07-01T09:30", "SYNTHETIC 请假窗口")) : List.of()));
    }
    return result;
  }

  private List<List<String>> rows(List<ExpectedTeacher> expected) {
    List<List<String>> rows = new ArrayList<>();
    for (ExpectedTeacher t : expected) rows.add(new ArrayList<>(List.of(t.no(), t.name(),
        t.gender().equals("female") ? "女" : "男", t.grade(), String.join(",", t.subjects()), t.phone(), t.email(),
        t.classCode() == null ? "否" : "是", t.classCode() == null ? "" : t.classCode(),
        String.join(",", t.qualifications()), t.expiry().toString(), Integer.toString(t.dailyLimit()))));
    return rows;
  }

  private Map<String, Object> windowUpdate(ExpectedTeacher t) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("teacherNo", t.no()); body.put("name", t.name()); body.put("gender", t.gender());
    body.put("grade", t.grade()); body.put("phone", t.phone()); body.put("email", t.email());
    body.put("status", "active"); body.put("isHomeroom", t.classCode() != null);
    body.put("homeroomClassId", classes.get(t.classCode()));
    body.put("qualifications", t.qualifications()); body.put("qualificationExpiry", t.expiry().toString());
    body.put("maxDailySessions", t.dailyLimit()); body.put("dutyWindows", t.duty()); body.put("leaveWindows", t.leave());
    // subjectIds intentionally omitted: PUT must preserve existing imported subject associations.
    return body;
  }

  private Map<String, UUID> assertAllTeachers(List<ExpectedTeacher> expected, boolean withWindows) throws Exception {
    JsonNode api = mapper.readTree(mvc.perform(get("/api/v1/teachers").cookie(session))
        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    assertThat(api.isArray()).isTrue();
    assertThat(api.size()).isEqualTo(expected.size());
    assertThat(jdbc.queryForObject("select count(*) from teacher where org_id=?", Integer.class, org)).isEqualTo(expected.size());
    assertThat(jdbc.queryForObject("select count(*) from teacher_subject where org_id=?", Integer.class, org))
        .isEqualTo(expected.stream().mapToInt(t -> t.subjects().size()).sum());
    Map<String, UUID> ids = new LinkedHashMap<>();
    for (ExpectedTeacher t : expected) {
      List<JsonNode> matched = nodes(api).stream().filter(row -> row.path("teacherNo").asText().equals(t.no())).toList();
      assertThat(matched).as(t.no() + " must appear exactly once").hasSize(1);
      JsonNode row = matched.get(0);
      UUID id = UUID.fromString(row.path("uuid").asText());
      assertApiTeacher(row, t, withWindows);
      assertDatabaseTeacher(t, id, withWindows);
      ids.put(t.no(), id);
    }
    assertThat(ids.values().stream().distinct().count()).isEqualTo((long) expected.size());
    return ids;
  }

  private void assertApiTeacher(JsonNode row, ExpectedTeacher t, boolean withWindows) {
    assertThat(row.path("teacherNo").asText()).isEqualTo(t.no());
    assertThat(row.path("id").asText()).isEqualTo(t.no());
    assertThat(row.path("name").asText()).isEqualTo(t.name());
    assertThat(row.path("gender").asText()).isEqualTo(t.gender());
    assertThat(row.path("grade").asText()).isEqualTo(t.grade());
    assertThat(row.path("phone").asText()).isEqualTo(t.phone());
    assertThat(row.path("email").asText()).isEqualTo(t.email());
    assertThat(row.path("status").asText()).isEqualTo("active");
    assertThat(row.path("isHomeroom").asBoolean()).isEqualTo(t.classCode() != null);
    assertThat(row.path("homeroomClassId").isNull()).isEqualTo(t.classCode() == null);
    if (t.classCode() != null) assertThat(row.path("homeroomClassId").asText()).isEqualTo(classes.get(t.classCode()).toString());
    assertThat(nodes(row.path("subjects")).stream().map(JsonNode::asText).toList()).containsExactlyInAnyOrderElementsOf(t.subjects());
    assertThat(nodes(row.path("qualifications")).stream().map(JsonNode::asText).toList()).containsExactlyElementsOf(t.qualifications());
    assertThat(row.path("qualificationExpiry").asText()).isEqualTo(t.expiry().toString());
    assertThat(row.path("maxDailySessions").asInt()).isEqualTo(t.dailyLimit());
    assertWindows(row.path("dutyWindows"), withWindows ? t.duty() : List.of());
    assertWindows(row.path("leaveWindows"), withWindows ? t.leave() : List.of());
  }

  private void assertDatabaseTeacher(ExpectedTeacher t, UUID id, boolean withWindows) throws Exception {
    Map<String, Object> row = jdbc.queryForMap("""
        select id,teacher_no,name,gender,grade,phone,email,status,is_homeroom,homeroom_class_id,
               qualifications::text as qualifications,qualification_expiry,max_daily_sessions,
               duty_windows::text as duty_windows,leave_windows::text as leave_windows
          from teacher where org_id=? and teacher_no=?
        """, org, t.no());
    assertThat(row).containsEntry("id", id).containsEntry("teacher_no", t.no()).containsEntry("name", t.name())
        .containsEntry("gender", t.gender()).containsEntry("grade", t.grade()).containsEntry("phone", t.phone())
        .containsEntry("email", t.email()).containsEntry("status", "active")
        .containsEntry("is_homeroom", t.classCode() != null).containsEntry("homeroom_class_id", classes.get(t.classCode()))
        .containsEntry("qualification_expiry", Date.valueOf(t.expiry())).containsEntry("max_daily_sessions", t.dailyLimit());
    assertThat(mapper.readTree((String) row.get("qualifications"))).isEqualTo(mapper.valueToTree(t.qualifications()));
    assertThat(jdbc.queryForList("select s.name from teacher_subject ts join subject s on s.org_id=ts.org_id and s.id=ts.subject_id where ts.org_id=? and ts.teacher_id=?", String.class, org, id))
        .containsExactlyInAnyOrderElementsOf(t.subjects());
    assertWindows(mapper.readTree((String) row.get("duty_windows")), withWindows ? t.duty() : List.of());
    assertWindows(mapper.readTree((String) row.get("leave_windows")), withWindows ? t.leave() : List.of());
  }

  private void assertWindows(JsonNode actual, List<Map<String, String>> expected) {
    assertThat(actual.isArray()).isTrue();
    assertThat(actual.size()).isEqualTo(expected.size());
    for (int i = 0; i < expected.size(); i++) {
      assertThat(LocalDateTime.parse(actual.get(i).path("start").asText())).isEqualTo(LocalDateTime.parse(expected.get(i).get("start")));
      assertThat(LocalDateTime.parse(actual.get(i).path("end").asText())).isEqualTo(LocalDateTime.parse(expected.get(i).get("end")));
      assertThat(actual.get(i).path("reason").asText()).isEqualTo(expected.get(i).get("reason"));
    }
  }

  private Map<String, String> persistedSnapshot() {
    Map<String, String> result = new LinkedHashMap<>();
    // Closed constant identifiers, all scoped to this test's unique organization.
    for (String table : List.of("teacher", "teacher_subject", "notification")) result.put(table,
        jdbc.queryForObject("select coalesce(jsonb_agg(to_jsonb(v) order by to_jsonb(v)::text),'[]'::jsonb)::text from "
            + table + " v where org_id=?", String.class, org));
    return result;
  }

  private MockMultipartFile workbook(String name, List<List<String>> rows) throws Exception {
    byte[] bytes;
    try (var book = new XSSFWorkbook(); var output = new ByteArrayOutputStream()) {
      var sheet = book.createSheet("SYNTHETIC 合成教师");
      var header = sheet.createRow(0);
      for (int i = 0; i < HEADERS.size(); i++) header.createCell(i).setCellValue(HEADERS.get(i));
      for (int r = 0; r < rows.size(); r++) {
        var row = sheet.createRow(r + 1);
        for (int c = 0; c < rows.get(r).size(); c++) row.createCell(c).setCellValue(rows.get(r).get(c));
      }
      book.write(output); bytes = output.toByteArray();
    }
    Path file = artifacts.resolve(name + ".xlsx");
    Files.write(file, bytes, StandardOpenOption.CREATE_NEW);
    byte[] reread = Files.readAllBytes(file);
    assertThat(reread).containsExactly(bytes);
    String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(reread))).isEqualTo(hash);
    Files.writeString(artifacts.resolve(name + ".sha256"), hash + "  " + file.getFileName() + "\n",
        StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
    try (var restored = WorkbookFactory.create(new ByteArrayInputStream(reread))) {
      assertThat(restored.getSheetAt(0).getSheetName()).contains("SYNTHETIC");
      assertThat(restored.getSheetAt(0).getLastRowNum()).isEqualTo(rows.size());
      for (int r = 0; r < rows.size(); r++) for (int c = 0; c < HEADERS.size(); c++)
        assertThat(restored.getSheetAt(0).getRow(r + 1).getCell(c).getStringCellValue()).isEqualTo(rows.get(r).get(c));
    }
    return new MockMultipartFile("file", file.getFileName().toString(), XLSX, reread);
  }

  private JsonNode validate(MockMultipartFile file) throws Exception {
    return mapper.readTree(mvc.perform(multipart("/api/v1/import/teachers/validate").file(file).cookie(session))
        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
  }

  private JsonNode commit(MockMultipartFile file) throws Exception {
    return mapper.readTree(mvc.perform(multipart("/api/v1/import/teachers/commit").file(file).cookie(session))
        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
  }

  private void assertCommit(JsonNode result, int rows, int inserted, int updated, int skipped) {
    assertThat(result.path("kind").asText()).isEqualTo("teachers");
    assertThat(result.path("rows").asInt()).isEqualTo(rows);
    assertThat(result.path("inserted").asInt()).isEqualTo(inserted);
    assertThat(result.path("updated").asInt()).isEqualTo(updated);
    assertThat(result.path("skipped").asInt()).isEqualTo(skipped);
    if (skipped == 0) assertThat(result.path("warnings").size()).isZero();
  }

  private JsonNode create(String url, Map<String, Object> request) throws Exception {
    return mapper.readTree(mvc.perform(post(url).cookie(session).contentType(MediaType.APPLICATION_JSON)
        .content(mapper.writeValueAsBytes(request))).andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
  }

  private static List<JsonNode> nodes(JsonNode value) { return StreamSupport.stream(value.spliterator(), false).toList(); }
  private static Map<String, String> window(String start, String end, String reason) { return Map.of("start", start, "end", end, "reason", reason); }
  private record ExpectedTeacher(String no, String name, String gender, String grade, List<String> subjects,
      String phone, String email, String classCode, List<String> qualifications, LocalDate expiry, int dailyLimit,
      List<Map<String, String>> duty, List<Map<String, String>> leave) {}
}
