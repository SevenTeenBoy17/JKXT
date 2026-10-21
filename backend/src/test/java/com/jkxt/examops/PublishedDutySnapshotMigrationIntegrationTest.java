package com.jkxt.examops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jkxt.common.tenant.TenantContext;
import com.jkxt.examops.service.PlanRevisionResourceService;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Real PostgreSQL V35-to-V36 migration and duty-reading contracts. Every test owns a fresh
 * jkxt_test_* database in this class's disposable container; no external JDBC overrides or
 * disabled triggers. Direct fixture publication does not claim approval/HTTP acceptance.
 * A transactional migration rollback is not a backup restore or an old-binary rollback drill.
 */
@EnabledIfSystemProperty(named = "jkxt.testcontainers.enabled", matches = "true")
class PublishedDutySnapshotMigrationIntegrationTest {
  private static PostgreSQLContainer<?> postgres;
  private static final Instant START = Instant.parse("2058-05-10T08:00:00Z");
  private static final String[] BUSINESS_TABLES = {
      "exam_plan", "exam_plan_revision", "exam_plan_session", "exam_plan_duty_requirement",
      "exam_plan_duty_assignment", "exam_plan_duty_response",
      "exam_plan_substitution_case", "exam_plan_duty_engagement"
  };

  @BeforeAll
  static void startOwnedContainer() {
    postgres = new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
        .withDatabaseName("jkxt_test_published_snapshot_migration")
        .withUsername("jkxt_test")
        .withPassword("synthetic-isolated-migration-only");
    postgres.start();
  }

  @AfterAll
  static void stopOwnedContainer() {
    if (postgres != null) postgres.stop();
  }

  @Test
  void version35BackfillsPublishedHistoryAndExistingSubstitutesButLeavesDraftsLive() throws Exception {
    String url = newIsolatedDatabase();
    migrate(url, "35");
    try (Connection c = connect(url)) {
      List<Fixture> published = new ArrayList<>();
      for (String status : List.of("PUBLISHED", "EXECUTING", "CLOSED", "ARCHIVED")) {
        Fixture f = fixture(c, status);
        handover(c, f, f.original(), f.replacement(), 0);
        published.add(f);
      }
      Fixture draft = fixture(c, "DRAFT");
      Map<UUID, Map<String, String>> histories = new LinkedHashMap<>();
      for (Fixture f : published) histories.put(f.org(), businessState(c, f));
      histories.put(draft.org(), businessState(c, draft));
      String oldHistory = migrationHistory(c);
      assertThat(string(c, "select to_regclass('public.exam_plan_teacher_snapshot')::text")).isNull();

      assertThat(migrate(url, "36")).isOne();
      assertThat(integer(c, "select max(version::int) from flyway_schema_history where success")).isEqualTo(36);
      assertThat(migrationHistory(c)).as("V1..V35 history and checksums are unchanged").isEqualTo(oldHistory);
      for (Fixture f : published) {
        assertThat(businessState(c, f)).as("migration preserves IDs, links, responses and timestamps").isEqualTo(histories.get(f.org()));
        assertThat(integer(c, "select count(*) from exam_plan_teacher_snapshot where org_id=?", f.org())).isEqualTo(2);
        assertSnapshot(c, f, f.original(), "SNAP-A", "原任教师");
        assertSnapshot(c, f, f.replacement(), "SNAP-B", "替补教师");
      }
      assertThat(businessState(c, draft)).isEqualTo(histories.get(draft.org()));
      assertThat(integer(c, "select count(*) from exam_plan_teacher_snapshot where org_id=?", draft.org())).isZero();
      String snapshots = string(c, "select jsonb_agg(to_jsonb(s) order by org_id,revision_id,teacher_id)::text from exam_plan_teacher_snapshot s");
      assertThat(migrate(url, "36")).as("repeat migrate neither recaptures names nor adds snapshots").isZero();
      assertThat(string(c, "select jsonb_agg(to_jsonb(s) order by org_id,revision_id,teacher_id)::text from exam_plan_teacher_snapshot s")).isEqualTo(snapshots);

      for (Fixture f : published) {
        rename(c, f.original(), "SNAP-A-NEW", "原任改名");
        rename(c, f.replacement(), "SNAP-B-NEW", "替补改名");
        Map<String, Object> duty = duty(url, f);
        assertThat(duty.get("teacher_id")).isEqualTo(f.original());
        assertThat(duty.get("teacher_name")).isEqualTo("原任教师");
        assertThat(duty.get("teacher_no")).isEqualTo("SNAP-A");
        assertThat(duty.get("current_teacher_id")).isEqualTo(f.replacement());
        assertThat(duty.get("current_teacher_name")).isEqualTo("替补教师");
        assertThat(duty.get("current_teacher_no")).isEqualTo("SNAP-B");
        assertThat(((Number) duty.get("current_engagement_no")).longValue()).isEqualTo(1);
        assertThat(businessState(c, f)).as("renaming master data does not rewrite published business history").isEqualTo(histories.get(f.org()));
      }
      rename(c, draft.original(), "SNAP-A-DRAFT", "草稿跟随现名");
      assertThat(duty(url, draft)).containsEntry("teacher_name", "草稿跟随现名")
          .containsEntry("teacher_no", "SNAP-A-DRAFT").containsEntry("current_teacher_name", "草稿跟随现名");
      assertThat(integer(c, "select count(*) from exam_plan_teacher_snapshot where org_id=?", draft.org())).isZero();
    }
  }

  @Test
  void newSubstitutionCapturesReplacementAtHandoverAndKeepsTheFirstSnapshotOnReturn() throws Exception {
    String url = newIsolatedDatabase();
    migrate(url, "35");
    try (Connection c = connect(url)) {
      Fixture f = fixture(c, "DRAFT");
      migrate(url, "36");
      assertThat(integer(c, "select count(*) from exam_plan_teacher_snapshot where org_id=?", f.org())).isZero();
      publish(c, f, "PUBLISHED");
      String originalAssignment = json(c, "exam_plan_duty_assignment", f.assignment());
      String originalRevision = json(c, "exam_plan_revision", f.revision());
      assertSnapshot(c, f, f.original(), "SNAP-A", "原任教师");
      rename(c, f.original(), "SNAP-A-AFTER", "原任发布后改名");
      rename(c, f.replacement(), "SNAP-B-HANDOVER", "替补交接时姓名");

      UUID firstCase = handover(c, f, f.original(), f.replacement(), 0);
      assertSnapshot(c, f, f.replacement(), "SNAP-B-HANDOVER", "替补交接时姓名");
      String firstCaseHistory = json(c, "exam_plan_substitution_case", firstCase);
      String firstResponseHistory = string(c, "select to_jsonb(r)::text from exam_plan_duty_response r where assignment_id=? and engagement_no=0", f.assignment());
      String firstSnapshot = string(c, "select to_jsonb(s)::text from exam_plan_teacher_snapshot s where org_id=? and revision_id=? and teacher_id=?", f.org(), f.revision(), f.original());
      rename(c, f.replacement(), "SNAP-B-AFTER", "替补交接后改名");
      assertThat(duty(url, f)).containsEntry("teacher_name", "原任教师")
          .containsEntry("teacher_no", "SNAP-A").containsEntry("current_teacher_name", "替补交接时姓名")
          .containsEntry("current_teacher_no", "SNAP-B-HANDOVER").containsEntry("current_teacher_id", f.replacement());

      handover(c, f, f.replacement(), f.original(), 1);
      assertThat(duty(url, f)).containsEntry("current_teacher_id", f.original())
          .containsEntry("current_teacher_name", "原任教师").containsEntry("current_teacher_no", "SNAP-A");
      assertThat(string(c, "select to_jsonb(s)::text from exam_plan_teacher_snapshot s where org_id=? and revision_id=? and teacher_id=?", f.org(), f.revision(), f.original()))
          .as("returning to the original teacher never recaptures their changed master name").isEqualTo(firstSnapshot);
      assertThat(integer(c, "select count(*) from exam_plan_teacher_snapshot where org_id=?", f.org())).isEqualTo(2);
      assertThat(integer(c, "select count(*) from exam_plan_duty_engagement where assignment_id=?", f.assignment())).isEqualTo(2);
      assertThat(json(c, "exam_plan_duty_assignment", f.assignment())).isEqualTo(originalAssignment);
      assertThat(json(c, "exam_plan_revision", f.revision())).isEqualTo(originalRevision);
      assertThat(json(c, "exam_plan_substitution_case", firstCase)).isEqualTo(firstCaseHistory);
      assertThat(string(c, "select to_jsonb(r)::text from exam_plan_duty_response r where assignment_id=? and engagement_no=0", f.assignment())).isEqualTo(firstResponseHistory);
      assertThatThrownBy(() -> execute(c, "update exam_plan_teacher_snapshot set teacher_name='rewrite' where org_id=?", f.org()))
          .isInstanceOf(SQLException.class).hasMessageContaining("immutable");
      assertSnapshot(c, f, f.original(), "SNAP-A", "原任教师");
      assertSnapshot(c, f, f.replacement(), "SNAP-B-HANDOVER", "替补交接时姓名");
    }
  }

  @Test
  void midMigrationFunctionConflictRollsBackDdlAndPreservesV35BusinessHistory() throws Exception {
    String url = newIsolatedDatabase();
    migrate(url, "35");
    try (Connection c = connect(url)) {
      Fixture f = fixture(c, "PUBLISHED");
      handover(c, f, f.original(), f.replacement(), 0);
      // V36 reaches this CREATE FUNCTION only after creating its table, immutable
      // trigger, revision function and revision trigger. No trigger is disabled.
      // This is a DDL-transaction failure, not a claim of failure after data backfill.
      execute(c, """
          create function v36_snapshot_engagement_teacher() returns trigger language plpgsql as $$
          begin raise exception 'synthetic-preexisting-conflict-must-survive'; end $$;
          """);
      String oldFunction = string(c, "select pg_get_functiondef('v36_snapshot_engagement_teacher()'::regprocedure)");
      String oldSchema = schemaSnapshot(c);
      String oldHistory = migrationHistory(c);
      Map<String, String> oldBusiness = businessState(c, f);

      assertThatThrownBy(() -> migrate(url, "36")).isInstanceOf(FlywayException.class)
          .hasStackTraceContaining("42723").hasStackTraceContaining("v36_snapshot_engagement_teacher");

      assertThat(integer(c, "select max(version::int) from flyway_schema_history where success")).isEqualTo(35);
      assertThat(integer(c, "select count(*) from flyway_schema_history where version='36'")).isZero();
      assertThat(migrationHistory(c)).isEqualTo(oldHistory);
      assertThat(schemaSnapshot(c)).as("columns, constraints, triggers and indexes are rolled back together").isEqualTo(oldSchema);
      assertThat(string(c, "select to_regclass('public.exam_plan_teacher_snapshot')::text")).isNull();
      assertThat(string(c, "select to_regprocedure('public.guard_v36_teacher_snapshot()')::text")).isNull();
      assertThat(string(c, "select to_regprocedure('public.v36_snapshot_revision_teachers()')::text")).isNull();
      assertThat(string(c, "select pg_get_functiondef('v36_snapshot_engagement_teacher()'::regprocedure)")).isEqualTo(oldFunction);
      assertThat(businessState(c, f)).isEqualTo(oldBusiness);
      assertThat(string(c, "select teacher_id::text from exam_plan_effective_duty where org_id=? and id=?", f.org(), f.assignment())).isEqualTo(f.replacement().toString());

      // Remove only the deliberately injected object in this disposable test DB.
      // No Flyway repair or checksum update is needed to retry the forward migration.
      execute(c, "drop function v36_snapshot_engagement_teacher()");
      assertThat(migrate(url, "36")).isOne();
      assertThat(businessState(c, f)).isEqualTo(oldBusiness);
      assertSnapshot(c, f, f.original(), "SNAP-A", "原任教师");
      assertSnapshot(c, f, f.replacement(), "SNAP-B", "替补教师");
      assertThat(duty(url, f)).containsEntry("current_teacher_name", "替补教师");
    }
  }

  private static Fixture fixture(Connection c, String status) throws SQLException {
    UUID org = UUID.randomUUID(), plan = UUID.randomUUID(), revision = UUID.randomUUID();
    UUID original = UUID.randomUUID(), replacement = UUID.randomUUID(), assignment = UUID.randomUUID();
    UUID session = UUID.randomUUID(), requirement = UUID.randomUUID();
    execute(c, "insert into organization(id,name,slug) values (?,'Synthetic V36 migration tenant',?)", org, "snapshot-" + org);
    execute(c, """
        insert into teacher(id,org_id,teacher_no,name,gender,status,qualifications,max_daily_sessions)
        values (?,?,'SNAP-A','原任教师','unknown','active','["INVIGILATOR"]'::jsonb,4),
               (?,?,'SNAP-B','替补教师','unknown','active','["INVIGILATOR"]'::jsonb,4)
        """, original, org, replacement, org);
    execute(c, "insert into exam_plan(id,org_id,plan_no,name) values (?,?,?,'Synthetic V36 plan')", plan, org, "SNAP-" + plan);
    execute(c, "insert into exam_plan_revision(id,org_id,plan_id,revision_no,status) values (?,?,?,1,'DRAFT')", revision, org, plan);
    execute(c, """
        insert into exam_plan_session(id,org_id,revision_id,session_code,name,starts_at,ends_at)
        values (?,?,?,?,'Synthetic V36 session',?,?)
        """, session, org, revision, "SNAP-" + session, Timestamp.from(START), Timestamp.from(START.plusSeconds(7200)));
    execute(c, """
        insert into exam_plan_duty_requirement(id,org_id,revision_id,session_id,duty_role,required_count)
        values (?,?,?,?,'INVIGILATOR',1)
        """, requirement, org, revision, session);
    execute(c, """
        insert into exam_plan_duty_assignment(id,org_id,revision_id,requirement_id,teacher_id,starts_at,ends_at,duty_role,status)
        values (?,?,?,?,?,?,?,'INVIGILATOR','PENDING_CONFIRMATION')
        """, assignment, org, revision, requirement, original, Timestamp.from(START), Timestamp.from(START.plusSeconds(7200)));
    Fixture f = new Fixture(org, plan, revision, assignment, original, replacement);
    execute(c, "update exam_plan set active_revision_id=? where id=?", revision, plan);
    if (!"DRAFT".equals(status)) publish(c, f, status);
    return f;
  }

  private static void publish(Connection c, Fixture f, String status) throws SQLException {
    execute(c, "update exam_plan_revision set status=?,published_at=? where id=?", status, Timestamp.from(START.minusSeconds(86400)), f.revision());
    execute(c, "update exam_plan set status='PUBLISHED',active_revision_id=? where id=?", f.revision(), f.plan());
  }

  private static UUID handover(Connection c, Fixture f, UUID oldTeacher, UUID replacement, int currentNo) throws SQLException {
    UUID response = UUID.randomUUID(), substitution = UUID.randomUUID();
    execute(c, """
        insert into exam_plan_duty_response(id,org_id,assignment_id,teacher_id,status,reason,responded_at,engagement_no)
        values (?,?,?,?,'REJECTED','Synthetic recorded rejection',?,?)
        """, response, f.org(), f.assignment(), oldTeacher, Timestamp.from(START.minusSeconds(3600 - currentNo * 60L)), currentNo);
    execute(c, """
        insert into exam_plan_substitution_case
          (id,org_id,revision_id,duty_assignment_id,teacher_id,replacement_teacher_id,status,reason,resolved_at,resolution_reason,engagement_no)
        values (?,?,?,?,?,?,'RESOLVED','Synthetic absence',?,'Synthetic validated handover',?)
        """, substitution, f.org(), f.revision(), f.assignment(), oldTeacher, replacement,
        Timestamp.from(START.minusSeconds(3500 - currentNo * 60L)), currentNo);
    execute(c, """
        insert into exam_plan_duty_engagement(org_id,assignment_id,engagement_no,teacher_id,substitution_case_id,reason)
        values (?,?,?,?,?,'Synthetic validated handover')
        """, f.org(), f.assignment(), currentNo + 1, replacement, substitution);
    return substitution;
  }

  private static void rename(Connection c, UUID teacher, String no, String name) throws SQLException {
    execute(c, "update teacher set teacher_no=?,name=? where id=?", no, name, teacher);
  }

  private static void assertSnapshot(Connection c, Fixture f, UUID teacher, String no, String name) throws SQLException {
    assertThat(integer(c, """
        select count(*) from exam_plan_teacher_snapshot
        where org_id=? and revision_id=? and teacher_id=? and teacher_no=? and teacher_name=? and captured_at is not null
        """, f.org(), f.revision(), teacher, no, name)).isOne();
  }

  private static Map<String, Object> duty(String url, Fixture f) {
    DriverManagerDataSource dataSource = new DriverManagerDataSource(url, postgres.getUsername(), postgres.getPassword());
    PlanRevisionResourceService service = new PlanRevisionResourceService(new JdbcTemplate(dataSource), new ObjectMapper());
    UUID previous = TenantContext.get().orElse(null);
    TenantContext.set(f.org());
    try {
      List<Map<String, Object>> rows = service.resources(f.revision(), "duties");
      assertThat(rows).hasSize(1);
      assertThat(rows.get(0).get("id")).isEqualTo(f.assignment());
      return rows.get(0);
    } finally {
      if (previous == null) TenantContext.clear(); else TenantContext.set(previous);
    }
  }

  private static Map<String, String> businessState(Connection c, Fixture f) throws SQLException {
    Map<String, String> result = new LinkedHashMap<>();
    for (String table : BUSINESS_TABLES) result.put(table, string(c,
        "select coalesce(jsonb_agg(to_jsonb(t) order by to_jsonb(t)::text),'[]'::jsonb)::text from " + table + " t where org_id=?", f.org()));
    return result;
  }

  private static String schemaSnapshot(Connection c) throws SQLException {
    return string(c, """
        select jsonb_build_object(
          'columns',(select jsonb_agg(jsonb_build_array(table_name,column_name,data_type,is_nullable,column_default)
            order by table_name,ordinal_position) from information_schema.columns where table_schema='public'),
          'constraints',(select jsonb_agg(jsonb_build_array(t.relname,k.conname,k.contype,pg_get_constraintdef(k.oid))
            order by t.relname,k.conname) from pg_constraint k join pg_class t on t.oid=k.conrelid
            join pg_namespace n on n.oid=t.relnamespace where n.nspname='public'),
          'triggers',(select jsonb_agg(jsonb_build_array(t.relname,g.tgname,g.tgenabled,pg_get_triggerdef(g.oid))
            order by t.relname,g.tgname) from pg_trigger g join pg_class t on t.oid=g.tgrelid
            join pg_namespace n on n.oid=t.relnamespace where n.nspname='public' and not g.tgisinternal),
          'indexes',(select jsonb_agg(to_jsonb(i) order by tablename,indexname) from pg_indexes i where schemaname='public')
        )::text
        """);
  }

  private static String newIsolatedDatabase() throws SQLException {
    String name = "jkxt_test_snapshot_" + UUID.randomUUID().toString().replace("-", "");
    try (Connection c = connect(postgres.getJdbcUrl()); var statement = c.createStatement()) {
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
    return string(c, "select jsonb_agg(to_jsonb(h) order by installed_rank)::text from flyway_schema_history h where version::int<=35");
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

  private static void bind(PreparedStatement ps, Object... values) throws SQLException {
    for (int i = 0; i < values.length; i++) ps.setObject(i + 1, values[i]);
  }

  private record Fixture(UUID org, UUID plan, UUID revision, UUID assignment, UUID original, UUID replacement) {}
}
