package com.jkxt.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jkxt.security.api.AuthDtos.CaptchaResponse;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest(properties = "jkxt.deploy.mode=saas")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class P12SaasTenantHeaderIntegrationTest {

  private static final String TENANT_HEADER = "X-Tenant-Id";
  private static final String DEMO_ORG_ID = "00000000-0000-0000-0000-000000000001";
  private static final String NANKAI_ORG_ID = "00000000-0000-0000-0000-000000010002";

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private ObjectMapper objectMapper;

  @Test
  void superAdminCanUseTenantHeaderToSelectSaasTenantContext() throws Exception {
    Cookie session = login("ops.demo");
    String teacherNo = "P12S-" + UUID.randomUUID().toString().substring(0, 8);

    mockMvc.perform(post("/api/v1/teachers")
            .cookie(session)
            .header(TENANT_HEADER, NANKAI_ORG_ID)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                  "teacherNo": "%s",
                  "name": "P12 SaaS Header Teacher",
                  "grade": "G10",
                  "status": "active"
                }
                """.formatted(teacherNo)))
        .andExpect(status().isOk())
        .andExpect(header().string(TENANT_HEADER, NANKAI_ORG_ID))
        .andExpect(jsonPath("$.teacherNo").value(teacherNo));

    JsonNode nankaiTeachers = json(mockMvc.perform(get("/api/v1/teachers")
            .cookie(session)
            .header(TENANT_HEADER, NANKAI_ORG_ID))
        .andExpect(status().isOk())
        .andExpect(header().string(TENANT_HEADER, NANKAI_ORG_ID))
        .andReturn());
    assertThat(nankaiTeachers.toString()).contains(teacherNo);

    JsonNode demoTeachers = json(mockMvc.perform(get("/api/v1/teachers")
            .cookie(session)
            .header(TENANT_HEADER, DEMO_ORG_ID))
        .andExpect(status().isOk())
        .andExpect(header().string(TENANT_HEADER, DEMO_ORG_ID))
        .andReturn());
    assertThat(demoTeachers.toString()).doesNotContain(teacherNo);
  }

  @Test
  void normalAdminCannotUseTenantHeaderToCrossTenant() throws Exception {
    Cookie session = login("jwc.demo");

    mockMvc.perform(get("/api/v1/teachers")
            .cookie(session)
            .header(TENANT_HEADER, NANKAI_ORG_ID))
        .andExpect(status().isForbidden());
  }

  @Test
  void superAdminTenantHeaderRejectsUnknownTenant() throws Exception {
    Cookie session = login("ops.demo");

    mockMvc.perform(get("/api/v1/teachers")
            .cookie(session)
            .header(TENANT_HEADER, "00000000-0000-0000-0000-000000999999"))
        .andExpect(status().isNotFound());
  }

  private Cookie login(String username) throws Exception {
    CaptchaResponse captcha = captcha();
    MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                  "username": "%s",
                  "password": "Demo@2025",
                  "captchaId": "%s",
                  "captcha": "%s"
                }
                """.formatted(username, captcha.captchaId(), captcha.debugAnswer())))
        .andExpect(status().isOk())
        .andReturn();
    Cookie session = result.getResponse().getCookie("JKXT_SESSION");
    assertThat(session).isNotNull();
    return session;
  }

  private CaptchaResponse captcha() throws Exception {
    MvcResult result = mockMvc.perform(post("/api/v1/auth/captcha"))
        .andExpect(status().isOk())
        .andReturn();
    return objectMapper.readValue(result.getResponse().getContentAsString(StandardCharsets.UTF_8), CaptchaResponse.class);
  }

  private JsonNode json(MvcResult result) throws Exception {
    return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
  }
}
