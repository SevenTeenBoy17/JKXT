package com.jkxt.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jkxt.security.api.AuthDtos.CaptchaResponse;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** Real HTTP/service/database contract: unsupported delivery must not invent success or readership. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AnnouncementTruthIntegrationTest {
  @Autowired private MockMvc mvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private ObjectMapper jsonMapper;

  private final List<UUID> ownedAnnouncements = new ArrayList<>();

  @AfterEach
  void removeOnlyOwnedSyntheticAnnouncements() {
    for (UUID id : ownedAnnouncements) {
      jdbc.update("delete from audit_log where target_type = 'announcement' and target_id = ?", id);
      jdbc.update("delete from announcement where id = ?", id);
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"draft", "published", "retracted"})
  void pushRejectsUnsupportedDeliveryWithoutChangingAnyAnnouncementOrAuditField(String initialStatus)
      throws Exception {
    Cookie operator = login("admin", "Super001!!!");
    UUID id = fixture(initialStatus, 37);
    String before = storedAnnouncement(id);
    long auditBefore = announcementAudits(id);

    JsonNode response = json(mvc.perform(post(path(id, "/push")).cookie(operator))
        .andExpect(status().isNotImplemented()).andReturn());

    assertThat(response.path("message").asText()).contains("未接入");
    assertThat(storedAnnouncement(id))
        .as("push must not change read_by, status, publish_at, updated_at or any other persisted field")
        .isEqualTo(before);
    assertThat(announcementAudits(id)).as("no successful push audit may be invented").isEqualTo(auditBefore);
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 57})
  void readStatisticsStayUnknownEvenWhenLegacyCounterHasAValue(int legacyReadBy) throws Exception {
    Cookie operator = login("admin", "Super001!!!");
    UUID id = fixture("published", legacyReadBy);
    String before = storedAnnouncement(id);

    JsonNode stats = json(mvc.perform(get(path(id, "/read-stats")).cookie(operator))
        .andExpect(status().isOk()).andReturn());

    assertThat(stats.path("announcementId").asText()).isEqualTo(id.toString());
    assertExplicitNull(stats, "readBy");
    assertExplicitNull(stats, "unread");
    assertThat(stats.path("supported").isBoolean()).isTrue();
    assertThat(stats.path("supported").booleanValue()).isFalse();
    assertThat(stats.path("status").asText()).isEqualTo("unsupported");
    assertThat(stats.path("message").asText()).contains("未接入");
    assertThat(storedAnnouncement(id)).as("historical data is not silently rewritten").isEqualTo(before);
    assertThat(announcementAudits(id)).isZero();
  }

  @Test
  void listDoesNotPresentHistoricalCountersAsVerifiedReaders() throws Exception {
    Cookie operator = login("admin", "Super001!!!");
    UUID id = fixture("published", 912);

    JsonNode list = json(mvc.perform(get("/api/v1/admin/announcements").cookie(operator))
        .andExpect(status().isOk()).andReturn());
    JsonNode row = findAnnouncement(list, id);

    assertExplicitNull(row, "readBy");
    assertThat(row.path("readStatsSupported").isBoolean()).isTrue();
    assertThat(row.path("readStatsSupported").booleanValue()).isFalse();
    assertThat(row.path("deliverySupported").isBoolean()).isTrue();
    assertThat(row.path("deliverySupported").booleanValue()).isFalse();
    assertThat(jdbc.queryForObject("select read_by from announcement where id = ?", Integer.class, id))
        .as("do not erase existing stored counters as part of this capability correction").isEqualTo(912);
  }

  @Test
  void createEditPublishAndRetractStillPersistRealStateAndMatchingAudits() throws Exception {
    Cookie operator = login("admin", "Super001!!!");
    String title = "隔离验收公告-" + UUID.randomUUID();
    JsonNode created = json(mvc.perform(post("/api/v1/admin/announcements").cookie(operator)
            .contentType(MediaType.APPLICATION_JSON)
            .content(jsonMapper.writeValueAsString(Map.of(
                "title", title, "body", "原始正文", "status", "draft", "target", "all"))))
        .andExpect(status().isOk()).andReturn());
    UUID id = UUID.fromString(created.path("uuid").asText());
    ownedAnnouncements.add(id);
    assertThat(created.path("status").asText()).isEqualTo("draft");
    assertThat(jdbc.queryForObject("select publish_at from announcement where id = ?", Timestamp.class, id))
        .isNull();

    JsonNode edited = json(mvc.perform(put(path(id, "")).cookie(operator)
            .contentType(MediaType.APPLICATION_JSON)
            .content(jsonMapper.writeValueAsString(Map.of("title", title + "已修改", "body", "经核对的正文"))))
        .andExpect(status().isOk()).andReturn());
    assertThat(edited.path("body").asText()).isEqualTo("经核对的正文");
    assertThat(jdbc.queryForObject("select body from announcement where id = ?", String.class, id))
        .isEqualTo("经核对的正文");

    JsonNode published = json(mvc.perform(post(path(id, "/publish")).cookie(operator))
        .andExpect(status().isOk()).andReturn());
    assertThat(published.path("status").asText()).isEqualTo("published");
    assertExplicitNull(published, "readBy");
    assertThat(jdbc.queryForObject("select status from announcement where id = ?", String.class, id))
        .isEqualTo("published");
    Timestamp publishedAt = jdbc.queryForObject("select publish_at from announcement where id = ?", Timestamp.class, id);
    assertThat(publishedAt).isNotNull();

    JsonNode retracted = json(mvc.perform(post(path(id, "/retract")).cookie(operator))
        .andExpect(status().isOk()).andReturn());
    assertThat(retracted.path("status").asText()).isEqualTo("retracted");
    assertThat(jdbc.queryForObject("select status from announcement where id = ?", String.class, id))
        .isEqualTo("retracted");
    assertThat(jdbc.queryForObject("select publish_at from announcement where id = ?", Timestamp.class, id))
        .isEqualTo(publishedAt);
    assertThat(jdbc.queryForObject("select read_by from announcement where id = ?", Integer.class, id)).isZero();
    assertThat(jdbc.queryForList("select action from audit_log where target_type = 'announcement' and target_id = ?",
        String.class, id)).containsExactlyInAnyOrder(
            "ADMIN_ANNOUNCEMENT_CREATE", "ADMIN_ANNOUNCEMENT_UPDATE",
            "ADMIN_ANNOUNCEMENT_PUBLISH", "ADMIN_ANNOUNCEMENT_RETRACT");
  }

  @ParameterizedTest
  @ValueSource(strings = {"push", "publish", "retract"})
  void ordinaryTenantAdministratorCannotMutatePlatformAnnouncements(String action) throws Exception {
    Cookie tenantAdmin = login("jwc.demo", "Demo@2025");
    UUID id = fixture("draft", 19);
    String before = storedAnnouncement(id);

    mvc.perform(post(path(id, "/" + action)).cookie(tenantAdmin)).andExpect(status().isForbidden());

    assertThat(storedAnnouncement(id)).isEqualTo(before);
    assertThat(announcementAudits(id)).isZero();
  }

  @Test
  void missingAnnouncementKeepsNotFoundInsteadOfFabricatingStatsOrPushSuccess() throws Exception {
    Cookie operator = login("admin", "Super001!!!");
    UUID missing = UUID.randomUUID();
    mvc.perform(get(path(missing, "/read-stats")).cookie(operator)).andExpect(status().isNotFound());
    mvc.perform(post(path(missing, "/push")).cookie(operator)).andExpect(status().isNotFound());
    assertThat(announcementAudits(missing)).as("missing announcement must not receive a success audit").isZero();
  }

  private UUID fixture(String status, int historicalReadBy) {
    UUID id = UUID.randomUUID();
    jdbc.update("""
        insert into announcement (id, title, body, status, publish_at, read_by)
        values (?, ?, ?, ?, ?, ?)
        """, id, "合成公告-" + id, "仅用于隔离环境的公告正文", status,
        "draft".equals(status) ? null : Timestamp.from(Instant.parse("2020-01-02T03:04:05Z")), historicalReadBy);
    ownedAnnouncements.add(id);
    return id;
  }

  private String storedAnnouncement(UUID id) {
    return jdbc.queryForObject("select to_jsonb(a)::text from announcement a where id = ?", String.class, id);
  }

  private long announcementAudits(UUID id) {
    return jdbc.queryForObject("select count(*) from audit_log where target_type = 'announcement' and target_id = ?",
        Long.class, id);
  }

  private static String path(UUID id, String suffix) {
    return "/api/v1/admin/announcements/" + id + suffix;
  }

  private static void assertExplicitNull(JsonNode value, String field) {
    assertThat(value.has(field)).as("preserve API key %s", field).isTrue();
    assertThat(value.get(field).isNull()).as("%s is unknown, not a fabricated zero or count", field).isTrue();
  }

  private static JsonNode findAnnouncement(JsonNode list, UUID id) {
    for (JsonNode row : list) {
      if (id.toString().equals(row.path("uuid").asText())) return row;
    }
    throw new AssertionError("Expected own synthetic announcement in real list: " + id);
  }

  private Cookie login(String username, String password) throws Exception {
    CaptchaResponse captcha = jsonMapper.readValue(mvc.perform(post("/api/v1/auth/captcha"))
        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8),
        CaptchaResponse.class);
    MvcResult result = mvc.perform(post(com.jkxt.support.TestPortal.loginPath(username)).contentType(MediaType.APPLICATION_JSON)
            .content(jsonMapper.writeValueAsString(Map.of("username", username, "password", password,
                "captchaId", captcha.captchaId(), "captcha", captcha.debugAnswer()))))
        .andExpect(status().isOk()).andReturn();
    Cookie cookie = com.jkxt.support.TestPortal.sessionCookie(result.getResponse());
    assertThat(cookie).as("fresh isolated login session").isNotNull();
    return cookie;
  }

  private JsonNode json(MvcResult result) throws Exception {
    return jsonMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
  }
}
