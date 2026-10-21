package com.jkxt.examops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jkxt.examops.service.PublishedResourceBaseline;
import com.jkxt.security.api.AuthDtos.CaptchaResponse;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Real HTTP/authentication, independent PostgreSQL transactions, and genuine competing cases.
 * No outer rollback transaction: workers must see committed fixtures. Every test owns a random
 * synthetic organization in the guard-protected disposable database. Published resources remain
 * immutable; cleanup archives only this test's plans, never disables triggers or deletes history.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SubstitutionConcurrencyIntegrationTest {
  private static final UUID SEED_ORG = UUID.fromString("00000000-0000-0000-0000-000000000001");
  private static final Instant START = Instant.parse("2042-07-01T00:00:00Z");
  private static final Instant END = START.plusSeconds(7200);
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper mapper;
  @Autowired JdbcTemplate jdbc;
  @Autowired DataSource dataSource;
  @Autowired PublishedResourceBaseline baseline;
  private final UUID org = UUID.randomUUID();
  private final List<Duty> duties = new ArrayList<>();
  private Account manager;
  private Account substitute;
  private Account firstOwner;
  private Account secondOwner;

  @BeforeEach
  void committedIsolatedFixtures() {
    jdbc.update("insert into organization(id,name,slug) values (?,'Synthetic concurrent substitution school',?)",
        org, "sub-race-" + org);
    for (String code : List.of("ADMIN", "TEACHER_QUERY")) {
      assertThat(jdbc.update("""
          insert into role(id,org_id,code,name,permissions,system_role)
          select ?,?,code,name,permissions,true from role where org_id=? and code=?
          """, UUID.randomUUID(), org, SEED_ORG, code)).isOne();
    }
    manager = account("manager", "ADMIN", false);
    substitute = account("substitute", "TEACHER_QUERY", true);
    firstOwner = account("first", "TEACHER_QUERY", true);
    secondOwner = account("second", "TEACHER_QUERY", true);
  }

  @AfterEach
  void archiveOnlyOwnPlans() {
    for (Duty duty : duties) {
      jdbc.update("update exam_plan_revision set status='ARCHIVED' where org_id=? and id=?", org, duty.revision());
      jdbc.update("update exam_plan set status='ARCHIVED' where org_id=? and id=?", org, duty.plan());
    }
    // This isolated organization and immutable graph are removed when the owning tmpfs DB stops.
    // They are not seed-school teachers and cannot affect later tenants' availability calculations.
  }

  @ParameterizedTest
  @ValueSource(strings = {"OVERLAP", "DAILY_LIMIT"})
  void competingCasesCannotDoubleBookOrExceedDailyLimit(String conflict) throws Exception {
    if (conflict.equals("DAILY_LIMIT")) jdbc.update("update teacher set max_daily_sessions=1 where org_id=? and id=?", org, substitute.teacher());
    Duty first = duty(firstOwner, START, END);
    Duty second = duty(secondOwner, conflict.equals("OVERLAP") ? START : END,
        conflict.equals("OVERLAP") ? END : END.plusSeconds(7200));
    UUID firstCase = reject(firstOwner, first.assignment());
    UUID secondCase = reject(secondOwner, second.assignment());
    Cookie managerOne = login(manager.username());
    Cookie managerTwo = login(manager.username());
    Cookie substituteCookie = login(substitute.username());
    var pool = Executors.newFixedThreadPool(2);
    CountDownLatch started = new CountDownLatch(2);
    List<Future<HttpResult>> calls = new ArrayList<>();
    try (var heldTeacher = dataSource.getConnection()) {
      heldTeacher.setAutoCommit(false);
      try {
        try (var lock = heldTeacher.prepareStatement("select id from teacher where org_id=? and id=? for update")) {
          lock.setObject(1, org);
          lock.setObject(2, substitute.teacher());
          try (var rows = lock.executeQuery()) { assertThat(rows.next()).isTrue(); }
        }
        calls.add(pool.submit(() -> { started.countDown(); return resolve(managerOne, firstCase); }));
        calls.add(pool.submit(() -> { started.countDown(); return resolve(managerTwo, secondCase); }));
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        // Hold the shared teacher until both actual transactions contend in PostgreSQL. Old code
        // reads availability without a lock and only blocks later on its FK write; corrected code
        // must serialize before reading occupancy. No mock owns the critical check or write.
        boolean bothBlocked = false;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline && calls.stream().noneMatch(Future::isDone)) {
          int waiting = jdbc.queryForObject("""
              select count(*) from pg_stat_activity
              where datname=current_database() and wait_event_type='Lock'
                and cardinality(pg_blocking_pids(pid)) > 0 and pid<>pg_backend_pid()
              """, Integer.class);
          if (waiting >= 2) { bothBlocked = true; break; }
          TimeUnit.MILLISECONDS.sleep(5);
        }
        assertThat(bothBlocked).as("both requests must truly overlap in the isolated database").isTrue();
        assertThat(jdbc.queryForObject("select count(*) from exam_plan_substitution_case where org_id=? and status='RESOLVED'", Integer.class, org)).isZero();
      } finally {
        heldTeacher.rollback();
      }
      HttpResult firstResult = calls.get(0).get(10, TimeUnit.SECONDS);
      HttpResult secondResult = calls.get(1).get(10, TimeUnit.SECONDS);
      assertThat(List.of(firstResult.status(), secondResult.status()))
          .as("exactly one case wins the same teacher reservation")
          .containsExactlyInAnyOrder(200, 409);
      HttpResult denied = firstResult.status() == 409 ? firstResult : secondResult;
      assertThat(denied.body().path("code").asText())
          .isEqualTo(conflict.equals("OVERLAP") ? "SUBSTITUTION_TEACHER_CONFLICT" : "SUBSTITUTION_DAILY_LIMIT");
      assertThat(jdbc.queryForObject("select count(*) from exam_plan_substitution_case where org_id=? and status='RESOLVED' and replacement_teacher_id=?", Integer.class, org, substitute.teacher())).isOne();
      assertThat(jdbc.queryForObject("select count(*) from exam_plan_substitution_case where org_id=? and status='OPEN' and replacement_teacher_id is null", Integer.class, org)).isOne();
      assertThat(jdbc.queryForObject("select count(*) from exam_plan_audit_event where org_id=? and action='SUBSTITUTION_RESOLVED'", Integer.class, org)).isOne();
      assertThat(jdbc.queryForObject("select count(*) from exam_plan_outbox where org_id=? and event_type='SUBSTITUTION_RESOLVED'", Integer.class, org)).isOne();
      JsonNode current = mapper.readTree(mvc.perform(get("/api/v1/auth/me/duty-assignments").cookie(substituteCookie))
          .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
      assertThat(current.size()).isOne();
      assertThat(current.get(0).path("teacher_id").asText()).isEqualTo(substitute.teacher().toString());
      assertThat(current.get(0).path("status").asText()).isEqualTo("PENDING_CONFIRMATION");
      var reserved = baseline.load(org, null, null).teacherDuties().stream()
          .filter(value -> substitute.teacher().equals(value.teacherId())).toList();
      assertThat(reserved).hasSize(1);
      for (Duty value : duties) {
        assertThat(stored("exam_plan_duty_assignment", value.assignment())).isEqualTo(value.immutableAssignment());
        assertThat(stored("exam_plan_revision", value.revision())).isEqualTo(value.immutableRevision());
      }
    } finally {
      pool.shutdownNow();
      assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).as("no worker leaks into fixture archival").isTrue();
    }
  }

  private Account account(String label, String role, boolean teacher) {
    UUID user = UUID.randomUUID();
    String name = "sub.race." + label + "." + user.toString().substring(0, 8);
    assertThat(jdbc.update("""
        insert into app_user(id,org_id,username,display_name,email,password_hash,role,status,must_change_password,onboarded)
        select ?,?,?,'Synthetic concurrent operator',?,password_hash,?,'active',false,true
        from app_user where org_id=? and username='jwc.demo'
        """, user, org, name, name + "@example.test", role, SEED_ORG)).isOne();
    assertThat(jdbc.update("insert into user_role(user_id,role_id,org_id) select ?,id,org_id from role where org_id=? and code=?", user, org, role)).isOne();
    UUID teacherId = teacher ? UUID.randomUUID() : null;
    if (teacher) jdbc.update("""
        insert into teacher(id,org_id,teacher_no,name,gender,email,status,qualifications,max_daily_sessions,app_user_id)
        values (?,?,?,'Synthetic concurrent teacher','unknown',?,'active','["INVIGILATOR"]'::jsonb,4,?)
        """, teacherId, org, "RACE-" + teacherId.toString().substring(0, 12), name + "@example.test", user);
    return new Account(user, teacherId, name);
  }

  private Duty duty(Account owner, Instant startsAt, Instant endsAt) {
    UUID plan = UUID.randomUUID(), revision = UUID.randomUUID(), session = UUID.randomUUID(), requirement = UUID.randomUUID(), assignment = UUID.randomUUID();
    jdbc.update("insert into exam_plan(id,org_id,plan_no,name,status) values (?,?,?,'Synthetic concurrent duty','DRAFT')", plan, org, "RACE-" + plan);
    jdbc.update("insert into exam_plan_revision(id,org_id,plan_id,revision_no,status,source_type) values (?,?,?,1,'DRAFT','API')", revision, org, plan);
    jdbc.update("insert into exam_plan_session(id,org_id,revision_id,session_code,name,starts_at,ends_at,candidate_count) values (?,?,?,?,'Synthetic concurrent session',?,?,0)", session, org, revision, "RACE-" + session, Timestamp.from(startsAt), Timestamp.from(endsAt));
    jdbc.update("insert into exam_plan_duty_requirement(id,org_id,revision_id,session_id,duty_role,required_count,qualification) values (?,?,?,?,'INVIGILATOR',1,'{\"requiredQualifications\":[\"INVIGILATOR\"]}'::jsonb)", requirement, org, revision, session);
    jdbc.update("insert into exam_plan_duty_assignment(id,org_id,revision_id,requirement_id,teacher_id,starts_at,ends_at,duty_role,status) values (?,?,?,?,?,?,?,'INVIGILATOR','PENDING_CONFIRMATION')", assignment, org, revision, requirement, owner.teacher(), Timestamp.from(startsAt), Timestamp.from(endsAt));
    // Seed only: this test does not claim that an approval/publication workflow was executed.
    jdbc.update("update exam_plan_revision set status='PUBLISHED',published_at=now() where org_id=? and id=?", org, revision);
    jdbc.update("update exam_plan set status='PUBLISHED',active_revision_id=? where org_id=? and id=?", revision, org, plan);
    Duty result = new Duty(plan, revision, assignment, stored("exam_plan_duty_assignment", assignment), stored("exam_plan_revision", revision));
    duties.add(result);
    return result;
  }

  private UUID reject(Account account, UUID assignment) throws Exception {
    JsonNode result = mapper.readTree(mvc.perform(post("/api/v1/auth/me/duty-assignments/{id}/reject", assignment)
        .cookie(login(account.username())).contentType(MediaType.APPLICATION_JSON)
        .content("{\"reason\":\"Synthetic absence requiring one real substitute\"}"))
        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    assertThat(result.path("status").asText()).isEqualTo("REJECTED");
    return UUID.fromString(result.path("substitutionCaseId").asText());
  }

  private HttpResult resolve(Cookie cookie, UUID caseId) throws Exception {
    var result = mvc.perform(post("/api/v1/substitution-cases/{id}/resolve", caseId).cookie(cookie)
        .contentType(MediaType.APPLICATION_JSON)
        .content(mapper.writeValueAsString(Map.of("replacementTeacherId", substitute.teacher(), "reason", "Synthetic competing claim"))))
        .andReturn().getResponse();
    return new HttpResult(result.getStatus(), mapper.readTree(result.getContentAsString(StandardCharsets.UTF_8)));
  }

  private Cookie login(String username) throws Exception {
    CaptchaResponse captcha = mapper.readValue(mvc.perform(post("/api/v1/auth/captcha")).andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8), CaptchaResponse.class);
    Cookie cookie = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
        .content(mapper.writeValueAsString(Map.of("username", username, "password", "Demo@2025", "captchaId", captcha.captchaId(), "captcha", captcha.debugAnswer()))))
        .andExpect(status().isOk()).andReturn().getResponse().getCookie("JKXT_SCHOOL_SESSION");
    assertThat(cookie).as("fresh synthetic session from the real login endpoint").isNotNull();
    return cookie;
  }

  private String stored(String table, UUID id) {
    return jdbc.queryForObject("select to_jsonb(v)::text from " + table + " v where org_id=? and id=?", String.class, org, id);
  }

  private record Account(UUID user, UUID teacher, String username) {}
  private record Duty(UUID plan, UUID revision, UUID assignment, String immutableAssignment, String immutableRevision) {}
  private record HttpResult(int status, JsonNode body) {}
}
