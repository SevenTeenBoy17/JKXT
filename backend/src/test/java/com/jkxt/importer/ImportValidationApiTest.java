package com.jkxt.importer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jkxt.security.api.AuthDtos.CaptchaResponse;
import jakarta.servlet.http.Cookie;
import java.io.ByteArrayOutputStream;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ImportValidationApiTest {

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private ObjectMapper objectMapper;

  @Test
  void templateDownloadReturnsXlsx() throws Exception {
    Cookie session = login();

    MvcResult result = mockMvc.perform(get("/api/v1/import/templates/teachers").cookie(session))
        .andExpect(status().isOk())
        .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, org.hamcrest.Matchers.containsString("template.xlsx")))
        .andExpect(content().contentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
        .andReturn();

    assertThat(result.getResponse().getContentAsByteArray().length).isGreaterThan(1000);
  }

  @Test
  void badWorkbookValidationReturnsQuickStep2CompatibleIssues() throws Exception {
    Cookie session = login();
    MockMultipartFile file = new MockMultipartFile(
        "file",
        "bad-teachers.xlsx",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        badTeachersWorkbook()
    );

    mockMvc.perform(multipart("/api/v1/import/validate")
            .file(file)
            .param("kind", "teachers")
            .cookie(session))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.severity").value("error"))
        .andExpect(jsonPath("$.category").value("fields"))
        .andExpect(jsonPath("$.overview.completeness").exists())
        .andExpect(jsonPath("$.counts.error", greaterThan(0)))
        .andExpect(jsonPath("$.cats.fields.error", greaterThan(0)))
        .andExpect(jsonPath("$.items[0].severity").exists())
        .andExpect(jsonPath("$.items[0].category").exists())
        .andExpect(jsonPath("$.items[0].source").exists())
        .andExpect(jsonPath("$.items[0].problem").exists())
        .andExpect(jsonPath("$.items[0].suggest").exists());
  }

  private byte[] badTeachersWorkbook() throws Exception {
    try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      Sheet sheet = workbook.createSheet("teachers");
      Row header = sheet.createRow(0);
      header.createCell(0).setCellValue("工号");
      header.createCell(1).setCellValue("性别");
      header.createCell(2).setCellValue("邮箱");
      Row row = sheet.createRow(1);
      row.createCell(0).setCellValue("T-BAD-001");
      row.createCell(1).setCellValue("maybe");
      row.createCell(2).setCellValue("not-an-email");
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
