package com.jkxt.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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

  private static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private ObjectMapper objectMapper;

  @Test
  void schedulesRealImportedExamsCommittedViaImportEndpoint() throws Exception {
    Cookie session = login();
    String stamp = Long.toString(System.nanoTime());
    String roomCode = "RF-ROOM-" + stamp;
    // A subject name unique to this run so we can assert the assignments come from THIS exam,
    // independent of any other data already in the shared demo org.
    String subject = "RealFlow-" + stamp;

    // 1) Roster: three eligible invigilators (grade 高三, distinct from the exam's 高一) + one big room.
    commit(session, "teachers", null, workbook("teachers.xlsx",
        List.of("teacherNo", "name", "gender", "grade"),
        List.of(
            List.of("RF-T-" + stamp + "-1", "Invigilator One", "female", "高三"),
            List.of("RF-T-" + stamp + "-2", "Invigilator Two", "male", "高三"),
            List.of("RF-T-" + stamp + "-3", "Invigilator Three", "female", "高三")
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
    JsonNode started = json(mockMvc.perform(post("/api/v1/exam-tasks/{taskId}/schedule-jobs", taskId).cookie(session))
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
    Cookie session = login();
    UUID taskId = createTask(session, "Empty task should not schedule");

    // Decision R2: never silently fabricate a schedule — surface the missing input.
    mockMvc.perform(post("/api/v1/exam-tasks/{taskId}/schedule-jobs", taskId).cookie(session))
        .andExpect(status().isBadRequest());

    // The task must NOT have advanced past draft on a rejected schedule attempt.
    mockMvc.perform(get("/api/v1/exam-tasks/{taskId}", taskId).cookie(session))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("draft"));
  }

  private UUID createTask(Cookie session, String name) throws Exception {
    JsonNode created = json(mockMvc.perform(post("/api/v1/exam-tasks")
            .cookie(session)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"%s\",\"source\":\"quick\"}".formatted(name)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").exists())
        .andReturn());
    return UUID.fromString(created.get("id").asText());
  }

  private void commit(Cookie session, String kind, UUID taskId, MockMultipartFile file) throws Exception {
    var request = multipart("/api/v1/import/{kind}/commit", kind).file(file).cookie(session);
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

  private JsonNode waitForTerminal(UUID jobId, Cookie session) throws Exception {
    Instant deadline = Instant.now().plus(Duration.ofSeconds(45));
    JsonNode latest = null;
    while (Instant.now().isBefore(deadline)) {
      latest = json(mockMvc.perform(get("/api/v1/schedule-jobs/{jobId}", jobId).cookie(session))
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
