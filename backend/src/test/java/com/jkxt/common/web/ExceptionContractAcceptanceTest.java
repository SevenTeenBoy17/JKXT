package com.jkxt.common.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jkxt.security.api.AuthDtos.CaptchaResponse;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Re-check fixes: previously-unmapped exceptions returned 500. These now map to the correct 4xx in
 * the uniform error envelope (415 wrong content-type, 400 malformed path/query arg, 400 unknown
 * fixture id). The deadlock->409 mapping is covered by the concurrency contract and is hard to
 * trigger deterministically via MockMvc, so it is asserted at the source level only.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ExceptionContractAcceptanceTest {

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private ObjectMapper objectMapper;

  @Test
  void malformedPathUuidReturns400NotwithStanding500() throws Exception {
    Cookie session = login();
    // A non-UUID path variable -> 400 (was 500), distinct from the 404 for a valid-but-missing id.
    mockMvc.perform(get("/api/v1/teachers/not-a-uuid").cookie(session))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("BAD_REQUEST"))
        .andExpect(jsonPath("$.traceId").exists());
  }

  @Test
  void wrongContentTypeReturns415() throws Exception {
    Cookie session = login();
    mockMvc.perform(post("/api/v1/teachers")
            .cookie(session)
            .contentType(MediaType.TEXT_PLAIN)
            .content("{\"teacherNo\":\"X\",\"name\":\"Y\"}"))
        .andExpect(status().isUnsupportedMediaType())
        .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));
  }

  @Test
  void unknownSchedulingFixtureReturns400NotInternalError() throws Exception {
    Cookie session = login();
    // ScheduleFixtureFactory throws IllegalArgumentException for an unknown id -> now 400 (was 500).
    mockMvc.perform(post("/api/v1/scheduling/fixtures/NOPE/solve").cookie(session))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
  }

  private Cookie login() throws Exception {
    CaptchaResponse captcha = objectMapper.readValue(
        mockMvc.perform(post("/api/v1/auth/captcha")).andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString(),
        CaptchaResponse.class);
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
    return login.getResponse().getCookie("JKXT_SCHOOL_SESSION");
  }
}
