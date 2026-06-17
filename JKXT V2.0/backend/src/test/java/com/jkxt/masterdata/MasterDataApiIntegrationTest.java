package com.jkxt.masterdata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jkxt.security.api.AuthDtos.CaptchaResponse;
import jakarta.servlet.http.Cookie;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MasterDataApiIntegrationTest {

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private ObjectMapper objectMapper;

  @Test
  void masterDataListsReturnTenantScopedDemoSeedRows() throws Exception {
    Cookie session = login();

    mockMvc.perform(get("/api/v1/teachers").cookie(session))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].uuid").exists())
        .andExpect(jsonPath("$[0].teacherNo").exists())
        .andExpect(jsonPath("$[0].subjects").isArray());

    mockMvc.perform(get("/api/v1/classrooms").cookie(session))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].code").exists())
        .andExpect(jsonPath("$[0].features").isArray());

    mockMvc.perform(get("/api/v1/classes").cookie(session))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].code").exists())
        .andExpect(jsonPath("$[0].subjects").exists());

    mockMvc.perform(get("/api/v1/subjects").cookie(session))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].name").exists())
        .andExpect(jsonPath("$[0].examCount").exists());
  }

  @Test
  void teacherCrudRoundTripUsesUuidPathAndTeacherNoDisplayId() throws Exception {
    Cookie session = login();
    String code = "T-IT-" + UUID.randomUUID().toString().substring(0, 8);

    JsonNode created = json(mockMvc.perform(post("/api/v1/teachers")
            .cookie(session)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                  "teacherNo": "%s",
                  "name": "Integration Teacher",
                  "gender": "unknown",
                  "grade": "G9",
                  "phone": "13812345678",
                  "email": "%s@demo.local",
                  "status": "active",
                  "isHomeroom": false
                }
                """.formatted(code, code.toLowerCase())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(code))
        .andExpect(jsonPath("$.uuid").exists())
        .andReturn());

    String uuid = created.get("uuid").asText();

    mockMvc.perform(put("/api/v1/teachers/{id}", uuid)
            .cookie(session)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                  "teacherNo": "%s",
                  "name": "Integration Teacher Updated",
                  "gender": "male",
                  "grade": "G10",
                  "phone": "13812345679",
                  "email": "%s@demo.local",
                  "status": "active",
                  "isHomeroom": false
                }
                """.formatted(code, code.toLowerCase())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.name").value("Integration Teacher Updated"))
        .andExpect(jsonPath("$.gender").value("male"));

    mockMvc.perform(get("/api/v1/teachers/{id}", uuid).cookie(session))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.teacherNo").value(code));

    mockMvc.perform(delete("/api/v1/teachers/{id}", uuid).cookie(session))
        .andExpect(status().isNoContent());

    mockMvc.perform(get("/api/v1/teachers/{id}", uuid).cookie(session))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("NOT_FOUND"));
  }

  @Test
  void classroomsClassesAndSubjectsSupportCrud() throws Exception {
    Cookie session = login();
    String suffix = UUID.randomUUID().toString().substring(0, 8);

    JsonNode room = json(mockMvc.perform(post("/api/v1/classrooms")
            .cookie(session)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                  "code": "R-%s",
                  "name": "Integration Room",
                  "building": "Test Building",
                  "floor": "5F",
                  "capacity": 40,
                  "examCapacity": 30,
                  "roomType": "standard",
                  "features": ["广播", "监控"],
                  "status": "active"
                }
                """.formatted(suffix)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value("R-" + suffix))
        .andExpect(jsonPath("$.features[0]").value("广播"))
        .andReturn());

    JsonNode schoolClass = json(mockMvc.perform(post("/api/v1/classes")
            .cookie(session)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                  "code": "C-%s",
                  "name": "Integration Class",
                  "grade": "G10",
                  "stage": "High",
                  "studentCount": 35,
                  "defaultClassroomId": "%s",
                  "status": "active"
                }
                """.formatted(suffix, room.get("uuid").asText())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.students").value(35))
        .andReturn());

    JsonNode subject = json(mockMvc.perform(post("/api/v1/subjects")
            .cookie(session)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                  "code": "S-%s",
                  "name": "Integration Subject %s",
                  "category": "secondary",
                  "examType": "written",
                  "hours": 2.5,
                  "color": "cyan",
                  "status": "active"
                }
                """.formatted(suffix, suffix)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.category").value("secondary"))
        .andReturn());

    mockMvc.perform(put("/api/v1/subjects/{id}", subject.get("uuid").asText())
            .cookie(session)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                  "code": "S-%s",
                  "name": "Integration Subject %s Updated",
                  "category": "activity",
                  "examType": "practical",
                  "hours": 1,
                  "color": "emerald",
                  "status": "active"
                }
                """.formatted(suffix, suffix)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.examType").value("practical"));

    mockMvc.perform(delete("/api/v1/subjects/{id}", subject.get("uuid").asText()).cookie(session))
        .andExpect(status().isNoContent());
    mockMvc.perform(delete("/api/v1/classes/{id}", schoolClass.get("uuid").asText()).cookie(session))
        .andExpect(status().isNoContent());
    mockMvc.perform(delete("/api/v1/classrooms/{id}", room.get("uuid").asText()).cookie(session))
        .andExpect(status().isNoContent());
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
    return objectMapper.readTree(result.getResponse().getContentAsString());
  }
}
