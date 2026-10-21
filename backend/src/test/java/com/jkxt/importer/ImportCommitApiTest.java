package com.jkxt.importer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jkxt.security.api.AuthDtos.CaptchaResponse;
import jakarta.servlet.http.Cookie;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.UUID;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** Epic 1.2 — import-commit really persists rows (not validate-and-discard). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ImportCommitApiTest {

  private static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private ObjectMapper objectMapper;

  @Test
  void teacherImportCommitPersistsAndAppearsInRoster() throws Exception {
    Cookie session = login();
    String teacherNo = "IMPORT-T-" + System.nanoTime();

    MockMultipartFile file = new MockMultipartFile(
        "file", "teachers.xlsx", XLSX, validTeachersWorkbook(teacherNo, "导入校验教师"));

    // commit -> 200 with real insert count
    mockMvc.perform(multipart("/api/v1/import/teachers/commit")
            .file(file)
            .cookie(session))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.kind").value("teachers"))
        .andExpect(jsonPath("$.rows", greaterThanOrEqualTo(1)))
        .andExpect(jsonPath("$.inserted", greaterThanOrEqualTo(1)));

    // the imported teacher is now a real row served by the roster endpoint.
    // (Assert on the unique ASCII teacherNo — MockMvc getContentAsString defaults to
    //  ISO-8859-1 so a Chinese substring would mojibake; the teacherNo proves persistence.)
    mockMvc.perform(get("/api/v1/teachers").cookie(session))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString(teacherNo)));
  }

  @Test
  void commitRejectsFileWithValidationErrors() throws Exception {
    Cookie session = login();
    // Missing required 姓名 column value -> validation error -> commit must 400
    MockMultipartFile bad = new MockMultipartFile(
        "file", "bad.xlsx", XLSX, teachersMissingName());

    mockMvc.perform(multipart("/api/v1/import/teachers/commit")
            .file(bad)
            .cookie(session))
        .andExpect(status().isBadRequest());
  }

  @Test
  void teacherReimportWithoutStatusColumnPreservesDisabledAndLeftStatuses() throws Exception {
    Cookie session = login();
    String suffix = UUID.randomUUID().toString().substring(0, 8);
    String disabledNo = "IMPORT-DISABLED-" + suffix;
    String leftNo = "IMPORT-LEFT-" + suffix;
    JsonNode disabled = createTeacher(session, disabledNo, "disabled");
    JsonNode left = createTeacher(session, leftNo, "left");

    // This workbook deliberately has no status column. An upsert may refresh the supplied master
    // data, but absence of that field must not silently reactivate a teacher whom an administrator
    // explicitly disabled or marked as having left the school.
    MockMultipartFile file = new MockMultipartFile(
        "file", "teachers-without-status.xlsx", XLSX,
        teachersWorkbookWithoutStatus(List.of(
            List.of(disabledNo, "停用教师（重导入）", "女", "高二"),
            List.of(leftNo, "离职教师（重导入）", "男", "高三")
        )));

    mockMvc.perform(multipart("/api/v1/import/teachers/commit")
            .file(file)
            .cookie(session))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.updated").value(2));

    mockMvc.perform(get("/api/v1/teachers/{id}", disabled.get("uuid").asText()).cookie(session))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.name").value("停用教师（重导入）"))
        .andExpect(jsonPath("$.status").value("disabled"));
    mockMvc.perform(get("/api/v1/teachers/{id}", left.get("uuid").asText()).cookie(session))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.name").value("离职教师（重导入）"))
        .andExpect(jsonPath("$.status").value("left"));
  }

  private JsonNode createTeacher(Cookie session, String teacherNo, String teacherStatus) throws Exception {
    MvcResult result = mockMvc.perform(post("/api/v1/teachers")
            .cookie(session)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                  "teacherNo": "%s",
                  "name": "状态保留测试教师",
                  "gender": "unknown",
                  "grade": "高一",
                  "status": "%s",
                  "isHomeroom": false
                }
                """.formatted(teacherNo, teacherStatus)))
        .andExpect(status().isOk())
        .andReturn();
    return objectMapper.readTree(result.getResponse().getContentAsString());
  }

  private byte[] validTeachersWorkbook(String teacherNo, String name) throws Exception {
    try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      Sheet sheet = workbook.createSheet("teachers");
      Row header = sheet.createRow(0);
      header.createCell(0).setCellValue("工号");
      header.createCell(1).setCellValue("姓名");
      header.createCell(2).setCellValue("性别");
      header.createCell(3).setCellValue("年级");
      header.createCell(4).setCellValue("邮箱");
      Row row = sheet.createRow(1);
      row.createCell(0).setCellValue(teacherNo);
      row.createCell(1).setCellValue(name);
      row.createCell(2).setCellValue("女");
      row.createCell(3).setCellValue("高一");
      row.createCell(4).setCellValue(teacherNo.toLowerCase(java.util.Locale.ROOT) + "@example.com");
      workbook.write(output);
      return output.toByteArray();
    }
  }

  private byte[] teachersWorkbookWithoutStatus(List<List<String>> rows) throws Exception {
    try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      Sheet sheet = workbook.createSheet("teachers");
      Row header = sheet.createRow(0);
      header.createCell(0).setCellValue("工号");
      header.createCell(1).setCellValue("姓名");
      header.createCell(2).setCellValue("性别");
      header.createCell(3).setCellValue("年级");
      for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
        Row row = sheet.createRow(rowIndex + 1);
        List<String> values = rows.get(rowIndex);
        for (int cellIndex = 0; cellIndex < values.size(); cellIndex++) {
          row.createCell(cellIndex).setCellValue(values.get(cellIndex));
        }
      }
      workbook.write(output);
      return output.toByteArray();
    }
  }

  private byte[] teachersMissingName() throws Exception {
    try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      Sheet sheet = workbook.createSheet("teachers");
      Row header = sheet.createRow(0);
      header.createCell(0).setCellValue("工号");
      header.createCell(1).setCellValue("姓名");
      Row row = sheet.createRow(1);
      row.createCell(0).setCellValue("IMPORT-BAD-001");
      // name cell intentionally left blank -> required-empty error
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
    Cookie session = login.getResponse().getCookie("JKXT_SCHOOL_SESSION");
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
