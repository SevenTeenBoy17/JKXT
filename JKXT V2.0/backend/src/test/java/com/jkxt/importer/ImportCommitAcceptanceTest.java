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

  // ---- helpers ----

  private JsonNode commit(Cookie session, MockMultipartFile file) throws Exception {
    MvcResult result = mockMvc.perform(
            multipart("/api/v1/import/teachers/commit").file(file).cookie(session))
        .andExpect(status().isOk())
        .andReturn();
    String json = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    return objectMapper.readTree(json);
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
