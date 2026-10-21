package com.jkxt.examops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jkxt.security.api.AuthDtos.CaptchaResponse;
import com.jkxt.support.TestPortal;
import jakarta.servlet.http.Cookie;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

/** Runs only under the repository's mandatory isolated PostgreSQL/Redis test guard.
 * Synthetic rows and permission changes roll back; never run against a business database. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class ExamPlanAuditReadIntegrationTest {
  private static final UUID ORG = UUID.fromString("00000000-0000-0000-0000-000000000001");
  private static final Instant AT = Instant.parse("2031-04-05T06:07:08.123456Z");
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired JdbcTemplate jdbc;

  @Test void exposesPersistedBusinessEventsWithoutInventingMissingStates() throws Exception {
    Cookie admin = login("tenant.test", "Tenant001!!!");
    JsonNode created = body(mvc.perform(post("/api/v1/exam-plans").cookie(admin)
        .header("Idempotency-Key", "audit-create-" + UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON)
        .content("{\"planNo\":\"AUD-" + UUID.randomUUID() + "\",\"name\":\"Synthetic audit plan\"}"))
        .andExpect(status().isCreated()).andReturn());
    Ref ref = new Ref(ORG, UUID.fromString(created.path("id").asText()), UUID.fromString(created.path("activeRevisionId").asText()));
    JsonNode first = page(admin, ref, null, 20);
    assertThat(first.path("items")).hasSize(1);
    assertThat(first.path("items").get(0).path("action").asText()).isEqualTo("PLAN_CREATED");
    UUID persisted = jdbc.queryForObject("select id from exam_plan_audit_event where org_id=? and plan_id=? and revision_id=? and action='PLAN_CREATED'",
        UUID.class, ORG, ref.plan(), ref.revision());
    assertThat(first.path("items").get(0).path("id").asText()).isEqualTo(persisted.toString());
    mvc.perform(post(path(ref).replace("/audit-events", "/validate")).cookie(admin).header("If-Match", "0"))
        .andExpect(status().isUnprocessableEntity());
    JsonNode after = page(admin, ref, null, 20);
    assertThat(after.path("items").findValuesAsText("action")).containsExactlyInAnyOrder("PLAN_CREATED", "REVISION_VALIDATED");
  }

  @Test void tuplePaginationPreservesMicrosecondsAndNeverDuplicatesEqualTimestampRows() throws Exception {
    Cookie admin = login("tenant.test", "Tenant001!!!"); Ref ref = seed(ORG, null);
    for (int id = 1; id <= 3; id++) event(ref, new UUID(0, id), AT, null);
    event(ref, new UUID(0, 4), AT.plusNanos(1000), null);
    JsonNode first = page(admin, ref, null, 2);
    assertThat(first.path("items").findValuesAsText("id")).containsExactly(new UUID(0, 4).toString(), new UUID(0, 3).toString());
    assertThat(Instant.parse(first.path("items").get(0).path("occurredAt").asText())).isEqualTo(AT.plusNanos(1000));
    String cursor = first.path("nextCursor").asText();
    event(ref, new UUID(0, 5), AT.plusSeconds(1), null); // a newer insertion is seen on refresh, not mixed into later pages
    JsonNode second = page(admin, ref, cursor, 2);
    assertThat(second.path("items").findValuesAsText("id")).containsExactly(new UUID(0, 2).toString(), new UUID(0, 1).toString());
    assertThat(second.path("hasMore").asBoolean()).isFalse(); assertThat(second.path("nextCursor").isNull()).isTrue();
    assertThat(page(admin, ref, null, 2).path("items").get(0).path("id").asText()).isEqualTo(new UUID(0, 5).toString());
    Ref otherPlan = seed(ORG, null), otherRevision = seed(ORG, ref.plan());
    mvc.perform(get(path(otherPlan)).cookie(admin).param("cursor", cursor)).andExpect(status().isBadRequest());
    mvc.perform(get(path(otherRevision)).cookie(admin).param("cursor", cursor)).andExpect(status().isBadRequest());
  }

  @Test void queryIsTenantPlanAndRevisionScopedAndDoesNotMergeOtherAuditSources() throws Exception {
    Cookie admin = login("tenant.test", "Tenant001!!!"); Ref ref = seed(ORG, null), otherPlan = seed(ORG, null), otherRevision = seed(ORG, ref.plan());
    UUID foreignOrg = organization(); Ref foreign = seed(foreignOrg, null);
    UUID visible = UUID.randomUUID(); event(ref, visible, AT, null); event(otherPlan, UUID.randomUUID(), AT, null);
    event(otherRevision, UUID.randomUUID(), AT, null); event(foreign, UUID.randomUUID(), AT, null);
    event(new Ref(ORG, otherPlan.plan(), ref.revision()), UUID.randomUUID(), AT, null); // both FKs valid, plan/revision pair deliberately wrong
    event(new Ref(ORG, null, null), UUID.randomUUID(), AT, null); // unlinked leave-style event must not be guessed into a version
    jdbc.update("insert into audit_log(org_id,action,target_type,target_id,payload) values (?,'JOINT_INPUT_REPLACED','EXAM_PLAN_REVISION',?,'{\"secret\":\"OTHER_SOURCE\"}'::jsonb)", ORG, ref.revision());
    assertThat(page(admin, ref, null, 100).path("items").findValuesAsText("id")).containsExactly(visible.toString());
    mvc.perform(get(path(foreign)).cookie(admin)).andExpect(status().isNotFound());
    mvc.perform(get(path(new Ref(ORG, otherPlan.plan(), ref.revision()))).cookie(admin)).andExpect(status().isNotFound());
    mvc.perform(get(path(ref)).cookie(admin).header("X-Tenant-Id", foreignOrg.toString())).andExpect(status().isForbidden());
  }

  @Test void missingDeletedAndCrossTenantActorsNeverDropRowsOrLeakNamesAndRawDetails() throws Exception {
    Cookie admin = login("tenant.test", "Tenant001!!!"); Ref ref = seed(ORG, null);
    UUID current = user(ORG, "ADMIN", "Current synthetic name"), deleted = user(ORG, "ADMIN", "SOFT_DELETED_SECRET");
    UUID removed = user(ORG, "ADMIN", "HARD_DELETED_SECRET"), foreign = user(organization(), "ADMIN", "FOREIGN_NAME_SECRET");
    event(ref, new UUID(0, 11), AT, current); event(ref, new UUID(0, 12), AT, deleted);
    event(ref, new UUID(0, 13), AT, removed); event(ref, new UUID(0, 14), AT, foreign); event(ref, new UUID(0, 15), AT, null);
    jdbc.update("update app_user set deleted_at=now() where id=?", deleted);
    jdbc.update("delete from app_user where id=?", removed);
    String before = snapshot(ref);
    JsonNode result = page(admin, ref, null, 20);
    assertThat(result.path("items")).hasSize(5);
    for (JsonNode item : result.path("items")) {
      assertThat(item.size()).isEqualTo(4);
      if (item.path("id").asText().equals(new UUID(0, 11).toString())) assertThat(item.path("actorDisplayName").asText()).isEqualTo("Current synthetic name");
      else assertThat(item.path("actorDisplayName").isNull()).isTrue();
    }
    assertThat(result.toString()).doesNotContain("SECRET", "reason", "beforeState", "afterState", "actorId");
    assertThat(snapshot(ref)).isEqualTo(before);
  }

  @Test void realSessionFiltersDenyAnonymousViewerTeacherAndAdminWithoutTenantRead() throws Exception {
    Ref ref = seed(ORG, null);
    mvc.perform(get(path(ref))).andExpect(status().isUnauthorized());
    for (String role : List.of("VIEWER", "TEACHER_QUERY")) {
      UUID id = user(ORG, role, "Synthetic read restriction");
      mvc.perform(get(path(ref)).cookie(login("audit." + id, "Demo@2025"))).andExpect(status().isForbidden());
    }
    mvc.perform(get(path(ref)).cookie(login("admin", "Super001!!!"))).andExpect(status().isOk());
    mvc.perform(get(path(ref)).cookie(login("tenant.test", "Tenant001!!!"))).andExpect(status().isOk());
    // Configure a fresh synthetic account before its first login. Mutating the already-loaded
    // tenant.test role here increments auth_version (V31) but leaves its JPA snapshot stale.
    UUID restrictedOrg = organization();
    jdbc.update("insert into role(org_id,code,name,permissions) values (?,'ADMIN','Synthetic restricted admin','[\"schedule:manage\"]'::jsonb)", restrictedOrg);
    UUID restrictedUser = user(restrictedOrg, "ADMIN", "Synthetic admin without tenant read");
    Ref restrictedRef = seed(restrictedOrg, null);
    Cookie restrictedSession = login("audit." + restrictedUser, "Demo@2025");
    JsonNode me = body(mvc.perform(get("/api/v1/auth/me").cookie(restrictedSession)).andExpect(status().isOk()).andReturn());
    assertThat(me.path("user").path("role").asText()).isEqualTo("ADMIN");
    assertThat(me.path("permissions").toString()).contains("schedule:manage").doesNotContain("tenant:read");
    mvc.perform(get(path(restrictedRef)).cookie(restrictedSession)).andExpect(status().isForbidden());
  }

  @Test void emptyHistoryIsDifferentFromMissingVersionAndInvalidPagination() throws Exception {
    Cookie admin = login("tenant.test", "Tenant001!!!"); Ref ref = seed(ORG, null);
    JsonNode result = page(admin, ref, null, 1);
    assertThat(result.path("items")).isEmpty(); assertThat(result.path("hasMore").asBoolean()).isFalse(); assertThat(result.path("nextCursor").isNull()).isTrue();
    for (String limit : List.of("0", "101", "-1", "bad")) mvc.perform(get(path(ref)).cookie(admin).param("limit", limit)).andExpect(status().isBadRequest());
    for (String cursor : List.of("!", "x".repeat(1025), "")) mvc.perform(get(path(ref)).cookie(admin).param("cursor", cursor)).andExpect(status().isBadRequest());
    mvc.perform(get(path(new Ref(ORG, ref.plan(), UUID.randomUUID()))).cookie(admin)).andExpect(status().isNotFound());
  }

  private Ref seed(UUID org, UUID plan) {
    if (plan == null) { plan = UUID.randomUUID(); jdbc.update("insert into exam_plan(id,org_id,plan_no,name) values (?,?,?,'Synthetic audit fixture')", plan, org, "AUD-" + plan); }
    UUID revision = UUID.randomUUID();
    jdbc.update("insert into exam_plan_revision(id,org_id,plan_id,revision_no) select ?,?,?,coalesce(max(revision_no),0)+1 from exam_plan_revision where org_id=? and plan_id=?",
        revision, org, plan, org, plan);
    return new Ref(org, plan, revision);
  }
  private UUID organization() {
    UUID id = UUID.randomUUID(); jdbc.update("insert into organization(id,name,slug) values (?,'Synthetic audit tenant',?)", id, "audit-" + id); return id;
  }
  private UUID user(UUID org, String role, String displayName) {
    UUID id = UUID.randomUUID();
    jdbc.update("insert into app_user(id,org_id,username,display_name,password_hash,role,status,must_change_password,onboarded) select ?,?,?,?,password_hash,?,'active',false,true from app_user where org_id=? and username='jwc.demo'",
        id, org, "audit." + id, displayName, role, ORG);
    return id;
  }
  private void event(Ref ref, UUID id, Instant at, UUID actor) {
    jdbc.update("insert into exam_plan_audit_event(id,org_id,plan_id,revision_id,action,actor_id,reason,before_state,after_state,created_at) values (?,?,?,?,'APPROVAL_SUBMITTED',?,'SECRET_REASON','{\"reason\":\"SECRET_BEFORE\"}'::jsonb,'{\"token\":\"SECRET_AFTER\"}'::jsonb,?)",
        id, ref.org(), ref.plan(), ref.revision(), actor, Timestamp.from(at));
  }
  private JsonNode page(Cookie cookie, Ref ref, String cursor, int limit) throws Exception {
    var request = get(path(ref)).cookie(cookie).param("limit", Integer.toString(limit)); if (cursor != null) request.param("cursor", cursor);
    return body(mvc.perform(request).andExpect(status().isOk()).andReturn());
  }
  private String snapshot(Ref ref) {
    return jdbc.queryForList("select to_jsonb(e)::text from exam_plan_audit_event e where org_id=? and revision_id=? order by id", String.class, ref.org(), ref.revision()).toString()
        + jdbc.queryForList("select to_jsonb(r)::text from exam_plan_revision r where org_id=? and id=?", String.class, ref.org(), ref.revision())
        + jdbc.queryForList("select to_jsonb(o)::text from exam_plan_outbox o where org_id=? and revision_id=? order by id", String.class, ref.org(), ref.revision());
  }
  private Cookie login(String username, String password) throws Exception {
    CaptchaResponse captcha = json.readValue(mvc.perform(post("/api/v1/auth/captcha")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), CaptchaResponse.class);
    MvcResult response = mvc.perform(post(TestPortal.loginPath(jdbc, username)).contentType(MediaType.APPLICATION_JSON)
        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\",\"captchaId\":\"" + captcha.captchaId() + "\",\"captcha\":\"" + captcha.debugAnswer() + "\"}"))
        .andExpect(status().isOk()).andReturn();
    return TestPortal.sessionCookie(response.getResponse());
  }
  private JsonNode body(MvcResult result) throws Exception { return json.readTree(result.getResponse().getContentAsString()); }
  private String path(Ref ref) { return "/api/v1/exam-plans/" + ref.plan() + "/revisions/" + ref.revision() + "/audit-events"; }
  private record Ref(UUID org, UUID plan, UUID revision) {}
}
