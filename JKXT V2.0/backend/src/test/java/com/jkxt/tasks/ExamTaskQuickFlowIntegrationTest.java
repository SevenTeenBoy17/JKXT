package com.jkxt.tasks;

import static org.assertj.core.api.Assertions.assertThat;
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
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ExamTaskQuickFlowIntegrationTest {

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private ObjectMapper objectMapper;

  @Test
  void quickFlowCreatesTaskUploadsValidatesRunsAndExports() throws Exception {
    Cookie session = login();

    JsonNode created = json(mockMvc.perform(post("/api/v1/exam-tasks")
            .cookie(session)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                  "name": "P7 Quick integration flow",
                  "source": "quick"
                }
                """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").exists())
        .andExpect(jsonPath("$.status").value("draft"))
        .andReturn());
    UUID taskId = UUID.fromString(created.get("id").asText());

    upload(session, taskId, "t01", workbook("teachers.xlsx",
        List.of("teacherNo", "name", "gender", "grade"),
        List.of(
            List.of("T-QK-001", "Teacher A", "female", "G11"),
            List.of("T-QK-002", "Teacher B", "male", "G12")
        )));
    upload(session, taskId, "t02", workbook("rooms.xlsx",
        List.of("code", "name", "capacity", "examCapacity"),
        List.of(
            List.of("P6-R101", "Room 101", "48", "36"),
            List.of("P6-R201", "Room 201", "52", "40")
        )));
    upload(session, taskId, "t03", workbook("exams.xlsx",
        List.of("subjectName", "grade", "examDate", "startTime", "endTime", "roomCode", "seatDemand", "supervisorsPerRoom"),
        List.of(
            List.of("Math", "G10", "2026-06-08", "08:30", "10:00", "P6-R101", "36", "2"),
            List.of("English", "G10", "2026-06-08", "08:30", "10:00", "P6-R201", "40", "2")
        )));

    mockMvc.perform(post("/api/v1/exam-tasks/{taskId}/imports/validate", taskId).cookie(session))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("ready"))
        .andExpect(jsonPath("$.report.severity").value("pass"))
        .andExpect(jsonPath("$.report.overview.teachers").value(2))
        .andExpect(jsonPath("$.report.overview.rooms").value(2))
        .andExpect(jsonPath("$.report.overview.exams").value(2));

    JsonNode started = json(mockMvc.perform(post("/api/v1/exam-tasks/{taskId}/schedule-jobs", taskId).cookie(session))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.jobId").exists())
        .andExpect(jsonPath("$.eventsPath").exists())
        .andReturn());
    UUID jobId = UUID.fromString(started.get("jobId").asText());
    JsonNode done = waitForTerminal(jobId, session);

    assertThat(done.get("taskId").asText()).isEqualTo(taskId.toString());
    assertThat(done.get("status").asText()).isEqualTo("succeeded");
    // Real data flow: 2 imported exams × supervisorsPerRoom(2) = 4 invigilation assignments,
    // and every assignment references a subject the user actually imported (not a hardcoded fixture).
    assertThat(done.get("assignments")).hasSize(4);
    assertThat(done.get("assignments").findValuesAsText("subjectName"))
        .isNotEmpty()
        .allMatch(subject -> subject.equals("Math") || subject.equals("English"));
    assertThat(done.get("fairness")).hasSize(5);
    assertThat(done.get("ai").get("targets")).hasSize(3);
    assertThat(done.get("ai").get("targets").findValuesAsText("kind")).contains("teacher", "room", "slot");

    mockMvc.perform(get("/api/v1/exam-tasks/{taskId}/assignments", taskId).cookie(session))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.rows[0].teacherName").exists())
        .andExpect(jsonPath("$.ai.targets[0].kind").value("teacher"));

    mockMvc.perform(get("/api/v1/exam-tasks").cookie(session))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].id").exists());

    mockMvc.perform(get("/api/v1/exam-tasks/{taskId}", taskId).cookie(session))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.latestJob.status").value("succeeded"))
        .andExpect(jsonPath("$.stats.assignments").value(4));

    byte[] xlsx = mockMvc.perform(get("/api/v1/exam-tasks/{taskId}/export", taskId)
            .queryParam("format", "xlsx")
            .cookie(session))
        .andExpect(status().isOk())
        .andExpect(content().contentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
        .andReturn()
        .getResponse()
        .getContentAsByteArray();
    assertThat(xlsx.length).isGreaterThan(200);

    byte[] pdf = mockMvc.perform(get("/api/v1/exam-tasks/{taskId}/export", taskId)
            .queryParam("format", "pdf")
            .cookie(session))
        .andExpect(status().isOk())
        .andExpect(content().contentType(MediaType.APPLICATION_PDF))
        .andReturn()
        .getResponse()
        .getContentAsByteArray();
    assertThat(new String(pdf, 0, 8, StandardCharsets.ISO_8859_1)).startsWith("%PDF-");
  }

  private void upload(Cookie session, UUID taskId, String kind, MockMultipartFile file) throws Exception {
    mockMvc.perform(multipart("/api/v1/exam-tasks/{taskId}/uploads", taskId)
            .file(file)
            .param("kind", kind)
            .cookie(session))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.kind").exists())
        .andExpect(jsonPath("$.report.severity").exists());
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
      return new MockMultipartFile(
          "file",
          filename,
          "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
          output.toByteArray()
      );
    }
  }

  private JsonNode waitForTerminal(UUID jobId, Cookie session) throws Exception {
    Instant deadline = Instant.now().plus(Duration.ofSeconds(45));
    JsonNode latest = null;
    while (Instant.now().isBefore(deadline)) {
      latest = json(mockMvc.perform(get("/api/v1/schedule-jobs/{jobId}", jobId).cookie(session))
          .andExpect(status().isOk())
          .andReturn());
      String status = latest.get("status").asText();
      if ("succeeded".equals(status) || "failed".equals(status) || "infeasible".equals(status)) {
        return latest;
      }
      Thread.sleep(250);
    }
    throw new AssertionError("Schedule job did not finish in time. Last snapshot: " + latest);
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

  private JsonNode json(MvcResult result) throws Exception {
    return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
  }
}
