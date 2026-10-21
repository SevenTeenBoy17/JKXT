package com.jkxt.examops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jkxt.common.tenant.TenantContext;
import com.jkxt.common.web.RestExceptionHandler;
import com.jkxt.examops.ExamPlanAuditStorageBoundary.Row;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authorization.method.AuthorizationManagerBeforeMethodInterceptor;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** Real MVC/controller/service/cursor and method security, only JDBC storage replaced.
 * This does NOT prove SQL execution, session-filter authorization, or database tenant isolation;
 * those require the isolated PostgreSQL integration suite after source freeze. */
class ExamPlanAuditReadUnitTest {
  private static final UUID ORG = new UUID(0, 501), PLAN = new UUID(0, 502), REV = new UUID(0, 503);
  private static final Instant AT = Instant.parse("2026-10-21T00:00:00.123456Z");
  private final ObjectMapper json = new ObjectMapper();
  private final ExamPlanAuditStorageBoundary jdbc = new ExamPlanAuditStorageBoundary(AT);
  private MockMvc mvc;

  @BeforeEach
  void setup() throws Exception {
    TenantContext.set(ORG);
    role("ADMIN");
    // Feature-missing baseline registers no endpoint, so RED exercises actual MVC routing,
    // not a missing-import/compiler failure. The completed test uses the real classes.
    Object[] controllers = {};
    try {
      Class<?> serviceType = Class.forName("com.jkxt.examops.service.ExamPlanAuditQueryService");
      Object service = serviceType.getConstructor(JdbcTemplate.class).newInstance(jdbc);
      Object controller = Class.forName("com.jkxt.examops.api.ExamPlanAuditController")
          .getConstructor(serviceType).newInstance(service);
      ProxyFactory proxy = new ProxyFactory(controller);
      proxy.setProxyTargetClass(true);
      proxy.addAdvisor(AuthorizationManagerBeforeMethodInterceptor.preAuthorize());
      controllers = new Object[] {proxy.getProxy()};
    } catch (ClassNotFoundException absentFeature) {
      // Intentionally leave the baseline route absent. No alternative implementation.
    }
    mvc = MockMvcBuilders.standaloneSetup(controllers).addPlaceholderValue("jkxt.api-base", "/api/v1")
        .setControllerAdvice(new RestExceptionHandler()).build();
  }

  @AfterEach void cleanup() { TenantContext.clear(); SecurityContextHolder.clearContext(); }
  private String endpoint() { return "/api/v1/exam-plans/" + PLAN + "/revisions/" + REV + "/audit-events"; }
  private void role(String role) {
    SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("synthetic", "N/A",
        List.of(new SimpleGrantedAuthority("ROLE_" + role), new SimpleGrantedAuthority("tenant:read"))));
  }

  @Test void emitsOnlySafeMetadataWithScopeAndPreciseTime() throws Exception {
    jdbc.rows = List.of(new Row(new UUID(0, 4), "APPROVAL_SUBMITTED", "合成操作者"));
    String body = mvc.perform(get(endpoint())).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    JsonNode page = json.readTree(body);
    assertThat(page.path("planId").asText()).isEqualTo(PLAN.toString());
    assertThat(page.path("revisionId").asText()).isEqualTo(REV.toString());
    assertThat(page.path("source").asText()).isEqualTo("EXAM_PLAN_AUDIT_EVENT");
    JsonNode row = page.path("items").get(0);
    assertThat(row.size()).isEqualTo(4);
    assertThat(row.path("id").asText()).isEqualTo(new UUID(0, 4).toString());
    assertThat(row.path("action").asText()).isEqualTo("APPROVAL_SUBMITTED");
    assertThat(Instant.parse(row.path("occurredAt").asText())).isEqualTo(AT);
    assertThat(row.path("actorDisplayName").asText()).isEqualTo("合成操作者");
    assertThat(body).doesNotContain("reason", "beforeState", "afterState", "SECRET", "actorId");
    assertThat(jdbc.scopeArgs).containsExactly(ORG, PLAN, REV);
    assertThat(jdbc.eventArgs).containsExactly(ORG, PLAN, REV, 21);
    assertThat(jdbc.eventSql).contains("e.org_id=?", "e.plan_id=?", "e.revision_id=?", "u.org_id=e.org_id",
        "u.deleted_at is null", "order by e.created_at desc, e.id desc", "limit ?");
  }

  @Test void emptyAndUnavailableActorRemainHonest() throws Exception {
    JsonNode empty = json.readTree(mvc.perform(get(endpoint())).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    assertThat(empty.path("items").size()).isZero(); assertThat(empty.path("hasMore").asBoolean()).isFalse();
    assertThat(empty.path("nextCursor").isNull()).isTrue();
    jdbc.rows = List.of(new Row(new UUID(0, 4), "DUTY_REJECTED", null));
    JsonNode row = json.readTree(mvc.perform(get(endpoint())).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("items").get(0);
    assertThat(row.path("actorDisplayName").isNull()).isTrue();
  }

  @Test void cursorUsesLastReturnedTimestampAndIdAndEveryPageRemainsScoped() throws Exception {
    jdbc.rows = List.of(new Row(new UUID(0, 3), "A", null), new Row(new UUID(0, 2), "B", null), new Row(new UUID(0, 1), "C", null));
    JsonNode first = json.readTree(mvc.perform(get(endpoint()).param("limit", "2")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    assertThat(first.path("items").size()).isEqualTo(2); assertThat(first.path("hasMore").asBoolean()).isTrue();
    String cursor = first.path("nextCursor").asText(); assertThat(cursor).isNotBlank();
    jdbc.rows = List.of(new Row(new UUID(0, 1), "C", null));
    JsonNode second = json.readTree(mvc.perform(get(endpoint()).param("limit", "2").param("cursor", cursor))
        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    assertThat(second.path("items").size()).isEqualTo(1); assertThat(second.path("hasMore").asBoolean()).isFalse();
    assertThat(jdbc.scopeArgs).containsExactly(ORG, PLAN, REV);
    assertThat(jdbc.eventArgs).containsExactly(ORG, PLAN, REV, Timestamp.from(AT), new UUID(0, 2), 3);
    assertThat(jdbc.eventSql).contains("(e.created_at, e.id) < (?, ?)");
    TenantContext.set(new UUID(0, 999));
    mvc.perform(get(endpoint()).param("cursor", cursor)).andExpect(status().isBadRequest());
  }

  @Test void invalidLimitsAndCursorsAreRejectedInsteadOfBroadQueries() throws Exception {
    for (String limit : List.of("0", "-1", "101", "1.5", "text"))
      mvc.perform(get(endpoint()).param("limit", limit)).andExpect(status().isBadRequest());
    for (String cursor : List.of("bad", "!", "x".repeat(1025)))
      mvc.perform(get(endpoint()).param("cursor", cursor)).andExpect(status().isBadRequest());
    assertThat(jdbc.eventQueries).isZero();
  }

  @Test void absentScopedRevisionIsNotAnEmptyAuditHistory() throws Exception {
    jdbc.exists = 0;
    mvc.perform(get(endpoint())).andExpect(status().isNotFound());
    assertThat(jdbc.eventQueries).isZero();
    assertThat(jdbc.scopeArgs).containsExactly(ORG, PLAN, REV);
  }

  @Test void adminAndSuperAdminCanReadButViewerAndTeacherCannot() throws Exception {
    for (String allowed : List.of("ADMIN", "SUPER_ADMIN")) { role(allowed); mvc.perform(get(endpoint())).andExpect(status().isOk()); }
    for (String denied : List.of("VIEWER", "TEACHER_QUERY", "TEACHER")) { role(denied); mvc.perform(get(endpoint())).andExpect(status().isForbidden()); }
  }

}
