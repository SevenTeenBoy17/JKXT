package com.jkxt.notify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jkxt.notify.domain.Notification;
import com.jkxt.notify.repo.NotificationRepository;
import com.jkxt.security.api.AuthDtos.CaptchaResponse;
import jakarta.servlet.http.Cookie;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class NotificationApiIntegrationTest {

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private ObjectMapper objectMapper;

  @Autowired
  private JdbcTemplate jdbcTemplate;

  @Autowired
  private NotificationRepository notificationRepository;

  private UUID seededId;

  @AfterEach
  void cleanup() {
    if (seededId != null) {
      jdbcTemplate.update("delete from notification_read where notification_id = ?", seededId);
      notificationRepository.deleteById(seededId);
    }
  }

  @Test
  void inboxListsUnreadMarkReadAndMarkAllRead() throws Exception {
    Cookie session = login("jwc.demo", "Demo@2025");
    UUID orgId = jdbcTemplate.queryForObject(
        "select org_id from app_user where username = 'jwc.demo'", UUID.class);

    Notification seeded = new Notification();
    seeded.setOrgId(orgId);
    seeded.setType("schedule_published");
    seeded.setTitle("验收测试 · 监考排班已发布");
    seeded.setBody("body");
    seeded.setStatus("sent");
    seeded.setTargetUrl("/tasks/acc-test");
    seeded.setPayload(Map.of("teacherCount", 3,
        "channels", Map.of("email", Map.of("logged", 3), "sms", Map.of("logged", 3))));
    seededId = notificationRepository.saveAndFlush(seeded).getId();

    // The inbox lists it, initially unread.
    mockMvc.perform(get("/api/v1/notifications").cookie(session))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[?(@.id=='" + seededId + "')].read")
            .value(org.hamcrest.Matchers.hasItem(false)));

    long before = unreadCount(session);
    assertThat(before).isGreaterThanOrEqualTo(1);

    // Mark this one read -> unread strictly decreases.
    mockMvc.perform(post("/api/v1/notifications/" + seededId + "/read").cookie(session))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.unread").value(org.hamcrest.Matchers.lessThanOrEqualTo((int) before - 1)));

    // Mark all read -> zero unread for this user.
    mockMvc.perform(post("/api/v1/notifications/read-all").cookie(session))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.unread").value(0));

    assertThat(unreadCount(session)).isZero();
  }

  private long unreadCount(Cookie session) throws Exception {
    String body = mockMvc.perform(get("/api/v1/notifications/unread-count").cookie(session))
        .andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString();
    return objectMapper.readTree(body).path("count").asLong();
  }

  private Cookie login(String username, String password) throws Exception {
    CaptchaResponse captcha = objectMapper.readValue(
        mockMvc.perform(post("/api/v1/auth/captcha")).andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString(),
        CaptchaResponse.class);
    MvcResult login = mockMvc.perform(post("/api/v1/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                  "username": "%s",
                  "password": "%s",
                  "captchaId": "%s",
                  "captcha": "%s"
                }
                """.formatted(username, password, captcha.captchaId(), captcha.debugAnswer())))
        .andExpect(status().isOk())
        .andReturn();
    return login.getResponse().getCookie("JKXT_SESSION");
  }
}
