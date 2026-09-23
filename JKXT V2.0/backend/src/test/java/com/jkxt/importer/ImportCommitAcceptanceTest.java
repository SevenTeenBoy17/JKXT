package com.jkxt.importer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jkxt.security.api.AuthDtos.CaptchaResponse;
import jakarta.servlet.http.Cookie;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
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
 * Acceptance for the two import-commit defects:
 *  - P2-1: the 任教学科 column must be persisted into teacher_subject (was dropped).
 *  - P3-5: a teacherNo repeated inside one upload must be reported, not silently counted.
 *
 * <p>Drives the real REST commit endpoint (so TenantContext is bound to the demo org seeded
 * in V5, which already contains the 语文/历史 subjects) and verifies the join table directly
 * via {@link JdbcTemplate}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ImportCommitAcceptanceTest {

  private static final String XLSX =
      "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
  private static final UUID DEMO_ORG = UUID.fromString("00000000-0000-0000-0000-000000000001");

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private ObjectMapper objectMapper;

  @Autowired
  private JdbcTemplate jdbcTemplate;

  /** P2-1: committing a teacher with 任教学科 "语文,历史" writes both teacher_subject links. */
  @Test
  void teacherImportPersistsSubjectAssociations() throws Exception {
    Cookie session = login();
    String teacherNo = "IMPORT-SUBJ-" + System.nanoTime();

    MockMultipartFile file = new MockMultipartFile(
        "file", "teachers.xlsx", XLSX, teacherWithSubjects(teacherNo, "学科导入老师", "语文,历史"));

    mockMvc.perform(multipart("/api/v1/import/teachers/commit").file(file).cookie(session))
        .andExpect(status().isOk());

    UUID teacherId = jdbcTemplate.queryForObject(
        "select id from teacher where org_id = ? and teacher_no = ?",
        UUID.class, DEMO_ORG, teacherNo);

    List<String> linkedSubjects = jdbcTemplate.queryForList("""
        select s.name
        from subject s
        join teacher_subject ts on ts.subject_id = s.id
        where ts.org_id = ? and ts.teacher_id = ?
        order by s.name
        """, String.class, DEMO_ORG, teacherId);

    // Both named subjects exist in the demo org seed and must now be linked.
    assertThat(linkedSubjects).containsExactlyInAnyOrder("语文", "历史");
  }

  /** P2-1: an unknown 任教学科 name does not fail the row — it warns and is skipped. */
  @Test
  void unknownSubjectNameWarnsButStillCommitsTeacher() throws Exception {
    Cookie session = login();
    String teacherNo = "IMPORT-BADSUBJ-" + System.nanoTime();

    MockMultipartFile file = new MockMultipartFile(
        "file", "teachers.xlsx", XLSX,
        teacherWithSubjects(teacherNo, "未知学科老师", "语文,量子力学"));

    JsonNode body = commit(session, file);
    // Teacher still inserted (row not failed)...
    assertThat(body.get("inserted").asInt()).isGreaterThanOrEqualTo(1);
    // ...known subject linked, unknown one skipped with a warning.
    UUID teacherId = jdbcTemplate.queryForObject(
        "select id from teacher where org_id = ? and teacher_no = ?",
        UUID.class, DEMO_ORG, teacherNo);
    Integer linkCount = jdbcTemplate.queryForObject(
        "select count(*) from teacher_subject where org_id = ? and teacher_id = ?",
        Integer.class, DEMO_ORG, teacherId);
    assertThat(linkCount).isEqualTo(1);
    assertThat(warnings(body)).anyMatch(w -> w.contains("量子力学") && w.contains("未找到"));
  }

  /** P3-5: a teacherNo repeated inside one upload is reported (warning) and counted skipped. */
  @Test
  void inFileDuplicateTeacherNoProducesWarning() throws Exception {
    Cookie session = login();
    String teacherNo = "IMPORT-DUP-" + System.nanoTime();

    MockMultipartFile file = new MockMultipartFile(
        "file", "teachers.xlsx", XLSX, duplicateTeacherWorkbook(teacherNo));

    JsonNode body = commit(session, file);
    // The second occurrence is skipped, not counted as an update.
    assertThat(body.get("skipped").asInt()).isGreaterThanOrEqualTo(1);
    assertThat(warnings(body))
        .anyMatch(w -> w.contains(teacherNo) && w.contains("重复"));
  }

  /** 迭代7: 文件内重复考场编号被跳过并告警（此前 classrooms 缺 in-file 去重，静默 last-write-wins）。 */
  @Test
  void inFileDuplicateClassroomCodeProducesWarning() throws Exception {
    Cookie session = login();
    String code = "ROOM-DUP-" + System.nanoTime();
    MockMultipartFile file = new MockMultipartFile("file", "classrooms.xlsx", XLSX,
        classroomWorkbook(new String[][] {
            { code, "房间甲", "48", "40" },
            { code, "房间乙", "50", "42" } }));

    JsonNode body = commitKind(session, file, "classrooms");
    assertThat(body.get("skipped").asInt()).isGreaterThanOrEqualTo(1);
    assertThat(warnings(body)).anyMatch(w -> w.contains(code) && w.contains("重复"));
  }

  /** 迭代7: 数字字段填文本被强转为默认值时记一条告警（此前静默降级、warnings 为空）。 */
  @Test
  void nonNumericClassroomCapacityWarnsButStillCommits() throws Exception {
    Cookie session = login();
    String code = "ROOM-NUM-" + System.nanoTime();
    MockMultipartFile file = new MockMultipartFile("file", "classrooms.xlsx", XLSX,
        classroomWorkbook(new String[][] { { code, "数字房间", "48", "很多" } }));

    JsonNode body = commitKind(session, file, "classrooms");
    assertThat(body.get("inserted").asInt()).isGreaterThanOrEqualTo(1);
    assertThat(warnings(body)).anyMatch(w -> w.contains("不是有效数字"));
  }

  /**
   * 迭代9 F2(终审 · 承重项)：重导入考试名册的"守卫先于破坏"不变量。先导入 1 条有效考试建立既有数据,再
   * 导入"格式合法但考场不存在"的全无效文件——commitExams 的两阶段守卫必须在 deleteAll 之前抛
   * EXAM_IMPORT_NO_VALID_ROW(400),既有考试行原样保留(修 P1-J:坏文件重导曾静默销毁已发布排班)。
   */
  @Test
  void examReimportGuardRejectsAllInvalidAndPreservesExistingData() throws Exception {
    Cookie session = login();
    UUID taskId = createExamTask(session);
    // 1) 有效导入(引用 demo 播种考场 R101) → 建立既有数据
    MockMultipartFile valid = new MockMultipartFile("file", "exams.xlsx", XLSX,
        examWorkbook(new String[][] { { "语文", "高一", "2026-06-15", "08:00", "10:00", "R101", "40", "2", "" } }));
    mockMvc.perform(multipart("/api/v1/import/exams/commit").file(valid).param("taskId", taskId.toString()).cookie(session))
        .andExpect(status().isOk());
    Integer before = jdbcTemplate.queryForObject(
        "select count(*) from exam where exam_task_id = ?", Integer.class, taskId);
    assertThat(before).isEqualTo(1);

    // 2) 全无效重导(格式合法但考场不存在,能过上传校验、只在 commitExams 查房时失效) → 400 且不销毁旧数据
    MockMultipartFile bad = new MockMultipartFile("file", "exams.xlsx", XLSX,
        examWorkbook(new String[][] {
            { "数学", "高一", "2026-06-16", "08:00", "10:00", "GHOST_ROOM_A", "40", "2", "" },
            { "英语", "高二", "2026-06-17", "09:00", "11:00", "GHOST_ROOM_B", "30", "2", "" } }));
    MvcResult badResult = mockMvc.perform(
            multipart("/api/v1/import/exams/commit").file(bad).param("taskId", taskId.toString()).cookie(session))
        .andExpect(status().isBadRequest())
        .andReturn();
    JsonNode badBody = objectMapper.readTree(badResult.getResponse().getContentAsString(StandardCharsets.UTF_8));
    assertThat(badBody.get("code").asText()).isEqualTo("EXAM_IMPORT_NO_VALID_ROW");

    Integer after = jdbcTemplate.queryForObject(
        "select count(*) from exam where exam_task_id = ?", Integer.class, taskId);
    assertThat(after).as("守卫必须先于 deleteAll:坏文件重导入不得销毁既有考试").isEqualTo(1);

    jdbcTemplate.update("delete from exam_task where id = ?", taskId);
  }

  // ---- helpers ----

  private UUID createExamTask(Cookie session) throws Exception {
    MvcResult result = mockMvc.perform(post("/api/v1/exam-tasks").cookie(session)
            .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"F2守卫测试\",\"source\":\"manual\"}"))
        .andExpect(status().isOk())
        .andReturn();
    JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    return UUID.fromString(body.get("id").asText());
  }

  private byte[] examWorkbook(String[][] rows) throws Exception {
    String[] headers = { "考试科目", "年级", "考试日期", "开始时间", "结束时间", "考场编号", "人数", "监考人数", "班级" };
    try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      Sheet sheet = workbook.createSheet("exams");
      Row header = sheet.createRow(0);
      for (int c = 0; c < headers.length; c++) {
        header.createCell(c).setCellValue(headers[c]);
      }
      for (int i = 0; i < rows.length; i++) {
        Row r = sheet.createRow(i + 1);
        for (int c = 0; c < rows[i].length; c++) {
          r.createCell(c).setCellValue(rows[i][c]);
        }
      }
      workbook.write(output);
      return output.toByteArray();
    }
  }

  private JsonNode commit(Cookie session, MockMultipartFile file) throws Exception {
    return commitKind(session, file, "teachers");
  }

  private JsonNode commitKind(Cookie session, MockMultipartFile file, String kind) throws Exception {
    MvcResult result = mockMvc.perform(
            multipart("/api/v1/import/" + kind + "/commit").file(file).cookie(session))
        .andExpect(status().isOk())
        .andReturn();
    String json = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    return objectMapper.readTree(json);
  }

  private byte[] classroomWorkbook(String[][] rows) throws Exception {
    try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      Sheet sheet = workbook.createSheet("classrooms");
      Row header = sheet.createRow(0);
      header.createCell(0).setCellValue("考场编号");
      header.createCell(1).setCellValue("考场名称");
      header.createCell(2).setCellValue("容量");
      header.createCell(3).setCellValue("考试容量");
      for (int i = 0; i < rows.length; i++) {
        Row r = sheet.createRow(i + 1);
        r.createCell(0).setCellValue(rows[i][0]);
        r.createCell(1).setCellValue(rows[i][1]);
        r.createCell(2).setCellValue(rows[i][2]);
        r.createCell(3).setCellValue(rows[i][3]);
      }
      workbook.write(output);
      return output.toByteArray();
    }
  }

  private List<String> warnings(JsonNode body) {
    return objectMapper.convertValue(
        body.get("warnings"), objectMapper.getTypeFactory()
            .constructCollectionType(List.class, String.class));
  }

  private byte[] teacherWithSubjects(String teacherNo, String name, String subjects)
      throws Exception {
    try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      Sheet sheet = workbook.createSheet("teachers");
      Row header = sheet.createRow(0);
      header.createCell(0).setCellValue("工号");
      header.createCell(1).setCellValue("姓名");
      header.createCell(2).setCellValue("性别");
      header.createCell(3).setCellValue("年级");
      header.createCell(4).setCellValue("任教学科");
      Row row = sheet.createRow(1);
      row.createCell(0).setCellValue(teacherNo);
      row.createCell(1).setCellValue(name);
      row.createCell(2).setCellValue("女");
      row.createCell(3).setCellValue("高一");
      row.createCell(4).setCellValue(subjects);
      workbook.write(output);
      return output.toByteArray();
    }
  }

  private byte[] duplicateTeacherWorkbook(String teacherNo) throws Exception {
    try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      Sheet sheet = workbook.createSheet("teachers");
      Row header = sheet.createRow(0);
      header.createCell(0).setCellValue("工号");
      header.createCell(1).setCellValue("姓名");
      // same teacherNo on two data rows -> the second is an in-file duplicate
      Row first = sheet.createRow(1);
      first.createCell(0).setCellValue(teacherNo);
      first.createCell(1).setCellValue("重复老师甲");
      Row second = sheet.createRow(2);
      second.createCell(0).setCellValue(teacherNo);
      second.createCell(1).setCellValue("重复老师乙");
      workbook.write(output);
      return output.toByteArray();
    }
  }

  private Cookie login() throws Exception {
    CaptchaResponse captcha = captcha();
    MvcResult login = mockMvc.perform(post("/api/v1/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                  "username": "jwc.demo",
                  "password": "Demo@2025",
                  "captchaId": "%s",
                  "captcha": "%s"
                }
                """.formatted(captcha.captchaId(), captcha.debugAnswer())))
        .andExpect(status().isOk())
        .andReturn();
    Cookie session = login.getResponse().getCookie("JKXT_SESSION");
    assertThat(session).isNotNull();
    return session;
  }

  private CaptchaResponse captcha() throws Exception {
    MvcResult result = mockMvc.perform(post("/api/v1/auth/captcha"))
        .andExpect(status().isOk())
        .andReturn();
    return objectMapper.readValue(result.getResponse().getContentAsString(), CaptchaResponse.class);
  }
}
