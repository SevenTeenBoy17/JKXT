package com.jkxt.examops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Real PostgreSQL/Flyway upgrade contracts, not an approval-flow or HTTP acceptance test.
 * Never accepts an external JDBC URL, username or password override. Every test gets a new
 * synthetic database inside this class's own disposable container; no trigger is disabled.
 */
@EnabledIfSystemProperty(named = "jkxt.testcontainers.enabled", matches = "true")
class SubstitutionMigrationIntegrationTest {
  private static PostgreSQLContainer<?> postgres;
  private static final Instant START = Instant.parse("2042-07-01T00:00:00Z");
  private static final Instant RESOLVED = Instant.parse("2042-06-30T08:03:04.123456Z");

  @BeforeAll
  static void startOwnedContainer() {
    postgres = new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
        .withDatabaseName("jkxt_test_substitution_migration")
        .withUsername("jkxt_test")
        .withPassword("synthetic-isolated-migration-only");
    postgres.start();
  }

  @AfterAll
  static void stopOwnedContainer() {
    if (postgres != null) postgres.stop();
  }

  @Test
  void version32ResolutionBackfillsPendingSubstituteWithoutRewritingHistory() throws Exception {
    String url = newIsolatedDatabase();
    migrate(url, "32");
    try (Connection c = connect(url)) {
      Fixture f = legacyFixture(c);
      String assignment = json(c, "exam_plan_duty_assignment", f.assignment());
      String revision = json(c, "exam_plan_revision", f.revision());
      String response = json(c, "exam_plan_duty_response", f.response());
      String substitution = json(c, "exam_plan_substitution_case", f.substitution());
      String oldChecksums = migrationHistory(c);

      assertThat(migrate(url, "33")).isOne();
      assertThat(integer(c, "select max(version::int) from flyway_schema_history where success")).isEqualTo(33);
      assertThat(migrationHistory(c)).as("V1..V32 applied history is not repaired or altered").isEqualTo(oldChecksums);
      assertThat(json(c, "exam_plan_duty_assignment", f.assignment())).isEqualTo(assignment);
      assertThat(json(c, "exam_plan_revision", f.revision())).isEqualTo(revision);
      assertThat(string(c, "select (to_jsonb(x)-'engagement_no')::text from exam_plan_duty_response x where id=?",
          f.response())).as("all legacy response fields, including timestamps and IDs, survive").isEqualTo(response);
      assertThat(string(c, "select (to_jsonb(x)-'engagement_no')::text from exam_plan_substitution_case x where id=?",
          f.substitution())).isEqualTo(substitution);
      assertThat(integer(c, "select engagement_no from exam_plan_duty_response where id=?", f.response())).isZero();
      assertThat(string(c, "select status from exam_plan_duty_response where id=?", f.response())).isEqualTo("REJECTED");
      assertThat(integer(c, "select count(*) from exam_plan_duty_response where assignment_id=?", f.assignment()))
          .as("resolution is not a fabricated acceptance or acknowledgement").isOne();
      assertThat(integer(c, "select count(*) from exam_plan_duty_engagement where assignment_id=?", f.assignment())).isOne();
      assertThat(string(c, "select teacher_id::text from exam_plan_duty_engagement where assignment_id=? and engagement_no=1",
          f.assignment())).isEqualTo(f.replacement().toString());
      assertThat(instant(c, "select created_at from exam_plan_duty_engagement where assignment_id=?", f.assignment()))
          .as("historical handover time comes from the recorded resolution, not migration time").isEqualTo(RESOLVED);
      assertThat(string(c, "select reason from exam_plan_duty_engagement where assignment_id=?", f.assignment()))
          .isEqualTo("Synthetic recorded replacement B");
      assertCurrentPending(c, f, f.replacement(), 1);
      String engagement = string(c, "select to_jsonb(e)::text from exam_plan_duty_engagement e where assignment_id=?", f.assignment());
      assertThat(migrate(url, "33")).as("repeat migrate is a no-op, not another handover").isZero();
      assertThat(string(c, "select to_jsonb(e)::text from exam_plan_duty_engagement e where assignment_id=?", f.assignment()))
          .isEqualTo(engagement);
    }
  }

  @Test
  void version33RejectsWrongTenantOwnerEpisodeAndHistoryRewritesButAllowsRealNextEpisode() throws Exception {
    String url = newIsolatedDatabase();
    migrate(url, "32");
    try (Connection c = connect(url)) {
      Fixture f = legacyFixture(c);
      migrate(url, "33");
      String immutable = json(c, "exam_plan_duty_assignment", f.assignment());
      String history = json(c, "exam_plan_duty_response", f.response());
      UUID otherOrg = UUID.randomUUID();
      execute(c, "insert into organization(id,name,slug) values (?,'Synthetic foreign migration tenant',?)",
          otherOrg, "migration-foreign-" + otherOrg);

      expectSqlState("23514", () -> response(c, otherOrg, f.assignment(), f.replacement(), 1));
      expectSqlState("23514", () -> response(c, f.org(), f.assignment(), f.original(), 1));
      expectSqlState("23514", () -> response(c, f.org(), f.assignment(), f.replacement(), 2));
      expectSqlState("23514", () -> response(c, f.org(), f.assignment(), f.replacement(), -1));
      UUID pending = response(c, f.org(), f.assignment(), f.replacement(), 1);
      assertThat(string(c, "select status from exam_plan_duty_response where id=?", pending)).isEqualTo("PENDING_CONFIRMATION");
      expectSqlState("23505", () -> response(c, f.org(), f.assignment(), f.replacement(), 1));
      expectSqlState("23514", () -> execute(c,
          "update exam_plan_duty_response set teacher_id=? where id=?", f.replacement(), f.response()));
      expectSqlState("23514", () -> execute(c,
          "update exam_plan_duty_response set status='ACCEPTED' where id=?", f.response()));
      expectSqlState("23514", () -> execute(c,
          "update exam_plan_duty_response set reason='Rewrite old engagement' where id=?", f.response()));
      expectSqlState("23514", () -> execute(c,
          "update exam_plan_duty_response set responded_at=? where id=?", Timestamp.from(RESOLVED.plusSeconds(3600)), f.response()));
      expectSqlState("23514", () -> execute(c,
          "delete from exam_plan_duty_response where id=?", f.response()));
      expectSqlState("23514", () -> execute(c,
          "update exam_plan_duty_engagement set reason='Rewrite history' where assignment_id=?", f.assignment()));
      expectSqlState("23514", () -> execute(c,
          "delete from exam_plan_duty_engagement where assignment_id=?", f.assignment()));
      expectSqlState("23514", () -> execute(c,
          "update exam_plan_substitution_case set replacement_teacher_id=? where id=?", f.original(), f.substitution()));
      expectSqlState("23514", () -> execute(c,
          "update exam_plan_duty_assignment set teacher_id=? where id=?", f.replacement(), f.assignment()));

      UUID secondCase = UUID.randomUUID();
      expectSqlState("23514", () -> insertCase(c, secondCase, f, f.original(), f.original(), 1));
      insertCase(c, secondCase, f, f.replacement(), f.original(), 1);
      expectSqlState("23514", () -> engagement(c, otherOrg, f.assignment(), f.original(), secondCase, 2));
      expectSqlState("23514", () -> engagement(c, f.org(), f.assignment(), f.replacement(), secondCase, 2));
      expectSqlState("23514", () -> engagement(c, f.org(), f.assignment(), f.original(), secondCase, 3));
      expectSqlState("23514", () -> engagement(c, f.org(), f.assignment(), f.original(), f.substitution(), 2));
      engagement(c, f.org(), f.assignment(), f.original(), secondCase, 2);
      assertCurrentPending(c, f, f.original(), 2);
      assertThat(json(c, "exam_plan_duty_assignment", f.assignment())).isEqualTo(immutable);
      assertThat(json(c, "exam_plan_duty_response", f.response())).isEqualTo(history);
      assertThat(integer(c, "select count(*) from exam_plan_duty_engagement where assignment_id=?", f.assignment())).isEqualTo(2);
    }
  }

  @Test
  void legitimateActorAndTenantDeletionPreserveForeignKeyLifecycleWithoutRewritingDutyHistory() throws Exception {
    String url = newIsolatedDatabase();
    migrate(url, "32");
    try (Connection c = connect(url)) {
      Fixture f = legacyFixture(c);
      UUID actor = UUID.randomUUID();
      execute(c, """
          insert into app_user(id,org_id,username,display_name,password_hash,role)
          values (?,?,?,'Synthetic migration audit actor','not-a-login-credential','ADMIN')
          """, actor, f.org(), "migration.actor." + actor);
      execute(c, "update exam_plan_duty_response set created_by=?,updated_by=? where id=?", actor, actor, f.response());
      execute(c, "update exam_plan_substitution_case set created_by=?,updated_by=?,resolved_by=? where id=?",
          actor, actor, actor, f.substitution());
      migrate(url, "33");
      assertThat(string(c, "select created_by::text from exam_plan_duty_engagement where assignment_id=?", f.assignment()))
          .isEqualTo(actor.toString());
      String response = string(c, "select (to_jsonb(x)-ARRAY['created_by','updated_by'])::text from exam_plan_duty_response x where id=?", f.response());
      String substitution = string(c, "select (to_jsonb(x)-ARRAY['created_by','updated_by','resolved_by'])::text from exam_plan_substitution_case x where id=?", f.substitution());
      String engagement = string(c, "select (to_jsonb(x)-'created_by')::text from exam_plan_duty_engagement x where assignment_id=?", f.assignment());
      String assignment = json(c, "exam_plan_duty_assignment", f.assignment());
      String revision = json(c, "exam_plan_revision", f.revision());
      expectSqlState("23514", () -> execute(c,
          "update exam_plan_duty_engagement set created_by=null where assignment_id=?", f.assignment()));
      expectSqlState("23514", () -> execute(c,
          "update exam_plan_duty_response set created_by=null where id=?", f.response()));

      execute(c, "delete from app_user where org_id=? and id=?", f.org(), actor);
      assertThat(integer(c, "select count(*) from app_user where id=?", actor)).isZero();
      assertThat(string(c, "select created_by::text from exam_plan_duty_engagement where assignment_id=?", f.assignment())).isNull();
      assertThat(integer(c, "select count(*) from exam_plan_duty_response where id=? and created_by is null and updated_by is null", f.response())).isOne();
      assertThat(integer(c, "select count(*) from exam_plan_substitution_case where id=? and created_by is null and updated_by is null and resolved_by is null", f.substitution())).isOne();
      assertThat(string(c, "select (to_jsonb(x)-ARRAY['created_by','updated_by'])::text from exam_plan_duty_response x where id=?", f.response())).isEqualTo(response);
      assertThat(string(c, "select (to_jsonb(x)-ARRAY['created_by','updated_by','resolved_by'])::text from exam_plan_substitution_case x where id=?", f.substitution())).isEqualTo(substitution);
      assertThat(string(c, "select (to_jsonb(x)-'created_by')::text from exam_plan_duty_engagement x where assignment_id=?", f.assignment())).isEqualTo(engagement);
      assertThat(json(c, "exam_plan_duty_assignment", f.assignment())).isEqualTo(assignment);
      assertThat(json(c, "exam_plan_revision", f.revision())).isEqualTo(revision);
      assertCurrentPending(c, f, f.replacement(), 1);

      // Authorized disposable fixture cleanup tests the established FK cascade, not a history-edit API.
      execute(c, "delete from organization where id=?", f.org());
      assertThat(integer(c, "select count(*) from organization where id=?", f.org())).isZero();
      for (String table : new String[] {"exam_plan", "exam_plan_revision", "exam_plan_duty_assignment",
          "exam_plan_duty_response", "exam_plan_substitution_case", "exam_plan_duty_engagement"}) {
        assertThat(integer(c, "select count(*) from " + table + " where org_id=?", f.org())).as(table).isZero();
      }
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"MISSING_REPLACEMENT", "SAME_REPLACEMENT", "WRONG_RESPONSE_TEACHER",
      "WRONG_CASE_TEACHER", "WRONG_REVISION", "MISSING_RESOLVED_AT"})
  void ambiguousLegacyOwnershipFailsBeforeAnyPartialUpgrade(String defect) throws Exception {
    String url = newIsolatedDatabase();
    migrate(url, "32");
    try (Connection c = connect(url)) {
      Fixture f = legacyFixture(c);
      switch (defect) {
        case "MISSING_REPLACEMENT" -> execute(c,
            "update exam_plan_substitution_case set replacement_teacher_id=null where id=?", f.substitution());
        case "SAME_REPLACEMENT" -> execute(c,
            "update exam_plan_substitution_case set replacement_teacher_id=? where id=?", f.original(), f.substitution());
        case "WRONG_RESPONSE_TEACHER" -> execute(c,
            "update exam_plan_duty_response set teacher_id=? where id=?", f.replacement(), f.response());
        case "WRONG_CASE_TEACHER" -> execute(c,
            "update exam_plan_substitution_case set teacher_id=? where id=?", f.replacement(), f.substitution());
        case "WRONG_REVISION" -> {
          UUID otherRevision = UUID.randomUUID();
          execute(c, "insert into exam_plan_revision(id,org_id,plan_id,revision_no,status) values (?,?,?,2,'DRAFT')",
              otherRevision, f.org(), f.plan());
          execute(c, "update exam_plan_substitution_case set revision_id=? where id=?", otherRevision, f.substitution());
        }
        case "MISSING_RESOLVED_AT" -> execute(c,
            "update exam_plan_substitution_case set resolved_at=null where id=?", f.substitution());
        default -> throw new IllegalArgumentException(defect);
      }
      String oldSchema = columnSnapshot(c);
      String oldHistory = migrationHistory(c);
      String assignment = json(c, "exam_plan_duty_assignment", f.assignment());
      String revision = json(c, "exam_plan_revision", f.revision());
      String response = json(c, "exam_plan_duty_response", f.response());
      String substitution = json(c, "exam_plan_substitution_case", f.substitution());

      assertThatThrownBy(() -> migrate(url, "33"))
          .isInstanceOf(FlywayException.class)
          .hasStackTraceContaining("23514")
          .hasStackTraceContaining("V33 cannot infer legacy duty ownership");

      assertThat(integer(c, "select max(version::int) from flyway_schema_history where success")).isEqualTo(32);
      assertThat(integer(c, "select count(*) from flyway_schema_history where version='33'")).isZero();
      assertThat(migrationHistory(c)).isEqualTo(oldHistory);
      assertThat(columnSnapshot(c)).as("no half-added episode columns or tables survive the rejected migration").isEqualTo(oldSchema);
      assertThat(string(c, "select to_regclass('public.exam_plan_duty_engagement')::text")).isNull();
      assertThat(string(c, "select to_regclass('public.exam_plan_effective_duty')::text")).isNull();
      assertThat(integer(c, "select count(*) from pg_proc where proname like '%v33%'")).isZero();
      assertThat(json(c, "exam_plan_duty_assignment", f.assignment())).isEqualTo(assignment);
      assertThat(json(c, "exam_plan_revision", f.revision())).isEqualTo(revision);
      assertThat(json(c, "exam_plan_duty_response", f.response())).isEqualTo(response);
      assertThat(json(c, "exam_plan_substitution_case", f.substitution())).isEqualTo(substitution);
    }
  }

  private static Fixture legacyFixture(Connection c) throws SQLException {
    UUID org = UUID.randomUUID(), plan = UUID.randomUUID(), revision = UUID.randomUUID();
    UUID original = UUID.randomUUID(), replacement = UUID.randomUUID(), assignment = UUID.randomUUID();
    UUID session = UUID.randomUUID(), requirement = UUID.randomUUID(), response = UUID.randomUUID(), substitution = UUID.randomUUID();
    execute(c, "insert into organization(id,name,slug) values (?,'Synthetic migration tenant',?)", org, "migration-" + org);
    for (UUID teacher : new UUID[] {original, replacement}) execute(c, """
        insert into teacher(id,org_id,teacher_no,name,gender,status,qualifications,max_daily_sessions)
        values (?,?,?,'Synthetic migration teacher','unknown','active','["INVIGILATOR"]'::jsonb,4)
        """, teacher, org, "MIG-" + teacher.toString().substring(0, 20));
    execute(c, "insert into exam_plan(id,org_id,plan_no,name) values (?,?,?,'Synthetic legacy substitution')", plan, org, "MIG-" + plan);
    execute(c, "insert into exam_plan_revision(id,org_id,plan_id,revision_no,status) values (?,?,?,1,'DRAFT')", revision, org, plan);
    execute(c, """
        insert into exam_plan_session(id,org_id,revision_id,session_code,name,starts_at,ends_at)
        values (?,?,?,?,'Synthetic migration session',?,?)
        """, session, org, revision, "MIG-" + session, Timestamp.from(START), Timestamp.from(START.plusSeconds(7200)));
    execute(c, """
        insert into exam_plan_duty_requirement(id,org_id,revision_id,session_id,duty_role,required_count)
        values (?,?,?,?,'INVIGILATOR',1)
        """, requirement, org, revision, session);
    execute(c, """
        insert into exam_plan_duty_assignment(id,org_id,revision_id,requirement_id,teacher_id,starts_at,ends_at,duty_role,status)
        values (?,?,?,?,?,?,?,'INVIGILATOR','PENDING_CONFIRMATION')
        """, assignment, org, revision, requirement, original, Timestamp.from(START), Timestamp.from(START.plusSeconds(7200)));
    // Direct PUBLISHED fixture construction is not a claim of running approval or release APIs.
    execute(c, "update exam_plan_revision set status='PUBLISHED',published_at=? where id=?", Timestamp.from(RESOLVED.minusSeconds(600)), revision);
    execute(c, "update exam_plan set status='PUBLISHED',active_revision_id=? where id=?", revision, plan);
    execute(c, """
        insert into exam_plan_duty_response(id,org_id,assignment_id,teacher_id,status,reason,responded_at,version)
        values (?,?,?,?,'REJECTED','Synthetic recorded original rejection',?,3)
        """, response, org, assignment, original, Timestamp.from(RESOLVED.minusSeconds(60)));
    execute(c, """
        insert into exam_plan_substitution_case
          (id,org_id,revision_id,duty_assignment_id,teacher_id,replacement_teacher_id,status,reason,resolved_at,resolution_reason,version)
        values (?,?,?,?,?,?,'RESOLVED','Synthetic recorded original rejection',?,'Synthetic recorded replacement B',4)
        """, substitution, org, revision, assignment, original, replacement, Timestamp.from(RESOLVED));
    return new Fixture(org, plan, revision, assignment, original, replacement, response, substitution);
  }

  @Test
  void failureAtFinalViewCreationRollsBackAlreadyAppliedDdlAndBackfillAtomically() throws Exception {
    String url = newIsolatedDatabase();
    migrate(url, "32");
    try (Connection c = connect(url)) {
      Fixture f = legacyFixture(c);
      // Deliberate test-only schema conflict: CREATE VIEW is the final statement in V33,
      // after response/case key changes, backfill, functions and triggers have executed.
      execute(c, "create table exam_plan_effective_duty (marker text not null)");
      execute(c, "insert into exam_plan_effective_duty(marker) values ('preexisting-conflict-must-survive')");
      String oldSchema = columnSnapshot(c);
      String oldHistory = migrationHistory(c);
      String oldConstraints = string(c, """
          select jsonb_agg(jsonb_build_array(t.relname,k.conname,k.contype,pg_get_constraintdef(k.oid))
            order by t.relname,k.conname)::text
          from pg_constraint k join pg_class t on t.oid=k.conrelid
          join pg_namespace n on n.oid=t.relnamespace where n.nspname='public'
          """);
      String oldTriggers = string(c, """
          select jsonb_agg(jsonb_build_array(t.relname,g.tgname,g.tgenabled,pg_get_triggerdef(g.oid))
            order by t.relname,g.tgname)::text
          from pg_trigger g join pg_class t on t.oid=g.tgrelid
          join pg_namespace n on n.oid=t.relnamespace where n.nspname='public' and not g.tgisinternal
          """);
      String oldIndexes = string(c, "select jsonb_agg(to_jsonb(i) order by tablename,indexname)::text from pg_indexes i where schemaname='public'");
      String assignment = json(c, "exam_plan_duty_assignment", f.assignment());
      String revision = json(c, "exam_plan_revision", f.revision());
      String response = json(c, "exam_plan_duty_response", f.response());
      String substitution = json(c, "exam_plan_substitution_case", f.substitution());

      assertThatThrownBy(() -> migrate(url, "33"))
          .isInstanceOf(FlywayException.class)
          .hasStackTraceContaining("42P07")
          .hasStackTraceContaining("exam_plan_effective_duty");

      assertThat(integer(c, "select max(version::int) from flyway_schema_history where success")).isEqualTo(32);
      assertThat(integer(c, "select count(*) from flyway_schema_history where version='33'")).isZero();
      assertThat(migrationHistory(c)).isEqualTo(oldHistory);
      assertThat(columnSnapshot(c)).as("all added episode columns and backfill table rolled back").isEqualTo(oldSchema);
      assertThat(string(c, """
          select jsonb_agg(jsonb_build_array(t.relname,k.conname,k.contype,pg_get_constraintdef(k.oid))
            order by t.relname,k.conname)::text
          from pg_constraint k join pg_class t on t.oid=k.conrelid
          join pg_namespace n on n.oid=t.relnamespace where n.nspname='public'
          """)).as("legacy unique/FK/check constraints restored, not just columns").isEqualTo(oldConstraints);
      assertThat(string(c, """
          select jsonb_agg(jsonb_build_array(t.relname,g.tgname,g.tgenabled,pg_get_triggerdef(g.oid))
            order by t.relname,g.tgname)::text
          from pg_trigger g join pg_class t on t.oid=g.tgrelid
          join pg_namespace n on n.oid=t.relnamespace where n.nspname='public' and not g.tgisinternal
          """)).as("existing triggers remain enabled and new triggers disappear").isEqualTo(oldTriggers);
      assertThat(string(c, "select jsonb_agg(to_jsonb(i) order by tablename,indexname)::text from pg_indexes i where schemaname='public'"))
          .isEqualTo(oldIndexes);
      assertThat(string(c, "select to_regclass('public.exam_plan_duty_engagement')::text")).isNull();
      assertThat(integer(c, "select count(*) from pg_proc where proname like '%v33%'")).isZero();
      assertThat(string(c, "select relkind::text from pg_class where oid='public.exam_plan_effective_duty'::regclass")).isEqualTo("r");
      assertThat(string(c, "select marker from exam_plan_effective_duty")).isEqualTo("preexisting-conflict-must-survive");
      assertThat(json(c, "exam_plan_duty_assignment", f.assignment())).isEqualTo(assignment);
      assertThat(json(c, "exam_plan_revision", f.revision())).isEqualTo(revision);
      assertThat(json(c, "exam_plan_duty_response", f.response())).isEqualTo(response);
      assertThat(json(c, "exam_plan_substitution_case", f.substitution())).isEqualTo(substitution);
    }
  }

  private static UUID response(Connection c, UUID org, UUID assignment, UUID teacher, int number) throws SQLException {
    UUID id = UUID.randomUUID();
    execute(c, """
        insert into exam_plan_duty_response(id,org_id,assignment_id,teacher_id,status,engagement_no)
        values (?,?,?,?,'PENDING_CONFIRMATION',?)
        """, id, org, assignment, teacher, number);
    return id;
  }

  private static void insertCase(Connection c, UUID id, Fixture f, UUID teacher, UUID replacement, int number) throws SQLException {
    execute(c, """
        insert into exam_plan_substitution_case
          (id,org_id,revision_id,duty_assignment_id,teacher_id,replacement_teacher_id,status,reason,resolved_at,resolution_reason,engagement_no)
        values (?,?,?,?,?,?,'RESOLVED','Synthetic second absence',?,'Synthetic returning original',?)
        """, id, f.org(), f.revision(), f.assignment(), teacher, replacement, Timestamp.from(RESOLVED.plusSeconds(120)), number);
  }

  private static void engagement(Connection c, UUID org, UUID assignment, UUID teacher, UUID sourceCase, int number) throws SQLException {
    execute(c, """
        insert into exam_plan_duty_engagement(org_id,assignment_id,engagement_no,teacher_id,substitution_case_id,reason)
        values (?,?,?,?,?,'Synthetic validated next owner')
        """, org, assignment, number, teacher, sourceCase);
  }

  private static void assertCurrentPending(Connection c, Fixture f, UUID teacher, int number) throws SQLException {
    assertThat(string(c, "select teacher_id::text from exam_plan_effective_duty where org_id=? and id=?", f.org(), f.assignment()))
        .isEqualTo(teacher.toString());
    assertThat(integer(c, "select engagement_no from exam_plan_effective_duty where org_id=? and id=?", f.org(), f.assignment())).isEqualTo(number);
    assertThat(string(c, "select status from exam_plan_effective_duty where org_id=? and id=?", f.org(), f.assignment())).isEqualTo("PENDING_CONFIRMATION");
    assertThat(string(c, "select reason from exam_plan_effective_duty where org_id=? and id=?", f.org(), f.assignment())).isNull();
    assertThat(instant(c, "select responded_at from exam_plan_effective_duty where org_id=? and id=?", f.org(), f.assignment())).isNull();
    assertThat(instant(c, "select checked_in_at from exam_plan_effective_duty where org_id=? and id=?", f.org(), f.assignment())).isNull();
  }

  private static String newIsolatedDatabase() throws SQLException {
    String name = "jkxt_test_substitution_" + UUID.randomUUID().toString().replace("-", "");
    try (Connection c = connect(postgres.getJdbcUrl()); var statement = c.createStatement()) {
      // Name is generated here from a fixed prefix plus hexadecimal UUID, never supplied by a caller.
      statement.execute("CREATE DATABASE " + name);
    }
    return "jdbc:postgresql://" + postgres.getHost() + ":" + postgres.getMappedPort(5432) + "/" + name;
  }

  private static Connection connect(String url) throws SQLException {
    return DriverManager.getConnection(url, postgres.getUsername(), postgres.getPassword());
  }

  private static int migrate(String url, String target) {
    return Flyway.configure().dataSource(url, postgres.getUsername(), postgres.getPassword())
        .locations("classpath:db/migration").target(target).load().migrate().migrationsExecuted;
  }

  private static String migrationHistory(Connection c) throws SQLException {
    return string(c, "select jsonb_agg(to_jsonb(h) order by installed_rank)::text from flyway_schema_history h where version::int<=32");
  }

  private static String columnSnapshot(Connection c) throws SQLException {
    return string(c, """
        select jsonb_agg(jsonb_build_array(table_name,column_name,data_type,is_nullable,column_default)
          order by table_name,ordinal_position)::text from information_schema.columns where table_schema='public'
        """);
  }

  private static String json(Connection c, String table, UUID id) throws SQLException {
    return string(c, "select to_jsonb(x)::text from " + table + " x where id=?", id);
  }

  private static void execute(Connection c, String sql, Object... values) throws SQLException {
    try (PreparedStatement ps = c.prepareStatement(sql)) { bind(ps, values); ps.executeUpdate(); }
  }

  private static String string(Connection c, String sql, Object... values) throws SQLException {
    try (PreparedStatement ps = c.prepareStatement(sql)) {
      bind(ps, values);
      try (ResultSet rs = ps.executeQuery()) { assertThat(rs.next()).isTrue(); return rs.getString(1); }
    }
  }

  private static int integer(Connection c, String sql, Object... values) throws SQLException {
    try (PreparedStatement ps = c.prepareStatement(sql)) {
      bind(ps, values);
      try (ResultSet rs = ps.executeQuery()) { assertThat(rs.next()).isTrue(); return rs.getInt(1); }
    }
  }

  private static Instant instant(Connection c, String sql, Object... values) throws SQLException {
    try (PreparedStatement ps = c.prepareStatement(sql)) {
      bind(ps, values);
      try (ResultSet rs = ps.executeQuery()) {
        assertThat(rs.next()).isTrue(); Timestamp value = rs.getTimestamp(1); return value == null ? null : value.toInstant();
      }
    }
  }

  private static void bind(PreparedStatement ps, Object... values) throws SQLException {
    for (int i = 0; i < values.length; i++) ps.setObject(i + 1, values[i]);
  }

  private static void expectSqlState(String state, SqlWork work) throws SQLException {
    try { work.run(); fail("Expected SQLSTATE " + state); }
    catch (SQLException failure) { assertThat(failure.getSQLState()).isEqualTo(state); }
  }

  private record Fixture(UUID org, UUID plan, UUID revision, UUID assignment, UUID original,
      UUID replacement, UUID response, UUID substitution) {}
  @FunctionalInterface private interface SqlWork { void run() throws SQLException; }
}
