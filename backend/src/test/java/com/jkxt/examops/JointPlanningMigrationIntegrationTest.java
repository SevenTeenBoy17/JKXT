package com.jkxt.examops;

import static org.assertj.core.api.Assertions.assertThat;
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
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@EnabledIfSystemProperty(named = "jkxt.testcontainers.enabled", matches = "true")
class JointPlanningMigrationIntegrationTest {

  static PostgreSQLContainer<?> postgres;
  static String jdbcUrl;
  static String username;
  static String password;

  @BeforeAll
  static void migrate() {
    jdbcUrl = System.getProperty("jkxt.test.postgres.url");
    username = System.getProperty("jkxt.test.postgres.username", "jkxt");
    password = System.getProperty("jkxt.test.postgres.password", "jkxt");
    if (jdbcUrl == null || jdbcUrl.isBlank()) {
      postgres = new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
          .withDatabaseName("jkxt_joint_migration")
          .withUsername("jkxt")
          .withPassword("jkxt");
      postgres.start();
      jdbcUrl = postgres.getJdbcUrl();
      username = postgres.getUsername();
      password = postgres.getPassword();
    }
    Flyway.configure()
        .dataSource(jdbcUrl, username, password)
        .locations("classpath:db/migration")
        // This contract checks the historical V28 schema; HEAD is exercised separately
        // by the Spring integration suites. Do not weaken its exact-version oracle.
        .target("28")
        .load()
        .migrate();
  }

  @AfterAll
  static void stopContainer() {
    if (postgres != null) {
      postgres.stop();
    }
  }

  @Test
  void schema28EnforcesTenantVersionOutputAndBackwardCompatibility() throws Exception {
    try (Connection c = DriverManager.getConnection(
        jdbcUrl, username, password)) {
      assertThat(queryInt(c,
          "select max(version::int) from flyway_schema_history where success"))
          .isEqualTo(28);

      UUID orgA = insertId(c, "insert into organization(name,slug) values (?,?) returning id",
          "Joint A", "joint-a");
      UUID orgB = insertId(c, "insert into organization(name,slug) values (?,?) returning id",
          "Joint B", "joint-b");
      UUID userA = insertId(c, """
          insert into app_user(org_id,username,display_name,password_hash,role)
          values (?,?,?,?,?) returning id
          """, orgA, "joint.a", "Joint A", "not-used", "ADMIN");
      UUID userB = insertId(c, """
          insert into app_user(org_id,username,display_name,password_hash,role)
          values (?,?,?,?,?) returning id
          """, orgB, "joint.b", "Joint B", "not-used", "ADMIN");

      UUID subjectA = insertId(c,
          "insert into subject(org_id,code,name) values (?,?,?) returning id",
          orgA, "MATH-J", "Joint Mathematics");
      UUID classroomA = insertId(c, """
          insert into classroom(org_id,code,name,capacity,exam_capacity)
          values (?,?,?,?,?) returning id
          """, orgA, "J-A101", "A101", 30, 24);
      UUID classroomA2 = insertId(c, """
          insert into classroom(org_id,code,name,capacity,exam_capacity)
          values (?,?,?,?,?) returning id
          """, orgA, "J-A102", "A102", 30, 24);
      UUID classroomB = insertId(c, """
          insert into classroom(org_id,code,name,capacity,exam_capacity)
          values (?,?,?,?,?) returning id
          """, orgB, "J-B101", "B101", 30, 24);
      UUID classA = insertId(c, """
          insert into school_class(org_id,code,name,grade,student_count)
          values (?,?,?,?,?) returning id
          """, orgA, "J-C1", "Class 1", "G10", 20);
      UUID teacherA = insertId(c, """
          insert into teacher(org_id,teacher_no,name,gender,created_at)
          values (?,?,?,?,now()) returning id
          """, orgA, "J-T1", "Teacher One", "unknown");

      UUID planA = insertId(c, """
          insert into exam_plan(org_id,plan_no,name,created_by,updated_by)
          values (?,?,?,?,?) returning id
          """, orgA, "J-PLAN-A", "Joint Plan A", userA, userA);
      UUID revisionA = insertId(c, """
          insert into exam_plan_revision(org_id,plan_id,revision_no,created_by,updated_by)
          values (?,?,?,?,?) returning id
          """, orgA, planA, 1, userA, userA);
      execute(c, "update exam_plan set active_revision_id=? where id=?", revisionA, planA);

      UUID planB = insertId(c, """
          insert into exam_plan(org_id,plan_no,name,created_by,updated_by)
          values (?,?,?,?,?) returning id
          """, orgB, "J-PLAN-B", "Joint Plan B", userB, userB);
      UUID revisionB = insertId(c, """
          insert into exam_plan_revision(org_id,plan_id,revision_no,created_by,updated_by)
          values (?,?,?,?,?) returning id
          """, orgB, planB, 1, userB, userB);

      expectSqlState("23503", () -> insertId(c, """
          insert into exam_plan_exam_demand
            (org_id,revision_id,demand_code,name,duration_minutes,subject_id)
          values (?,?,?,?,?,?) returning id
          """, orgA, revisionB, "CROSS", "Cross tenant", 60, subjectA));
      expectSqlState("23503", () -> insertId(c, """
          insert into exam_plan_room_pool
            (org_id,revision_id,classroom_id,campus_code,exam_capacity)
          values (?,?,?,?,?) returning id
          """, orgA, revisionA, classroomB, "A", 20));

      UUID demand1 = insertId(c, """
          insert into exam_plan_exam_demand
            (org_id,revision_id,demand_code,name,subject_id,duration_minutes,candidate_count,
             invigilator_count,min_rooms,max_rooms,required_room_features,created_by,updated_by)
          values (?,?,?,?,?,?,?,?,?,?,cast(? as jsonb),?,?) returning id
          """, orgA, revisionA, "D-1", "Mathematics", subjectA, 60, 1, 2, 1, 2,
          "[\"ACCESSIBLE\"]", userA, userA);
      UUID demandClass1 = insertId(c, """
          insert into exam_plan_demand_class
            (org_id,revision_id,demand_id,class_id,expected_candidates,created_by,updated_by)
          values (?,?,?,?,?,?,?) returning id
          """, orgA, revisionA, demand1, classA, 1, userA, userA);
      insertId(c, """
          insert into exam_plan_demand_window
            (org_id,revision_id,demand_id,window_code,starts_at,ends_at,created_by,updated_by)
          values (?,?,?,?,?,?,?,?) returning id
          """, orgA, revisionA, demand1, "W-1", ts("2026-10-01T09:00:00Z"),
          ts("2026-10-01T10:00:00Z"), userA, userA);
      UUID candidate = insertId(c, """
          insert into exam_plan_candidate
            (org_id,revision_id,candidate_code,name,class_id,created_by,updated_by)
          values (?,?,?,?,?,?,?) returning id
          """, orgA, revisionA, "S-1", "Student One", classA, userA, userA);
      insertId(c, """
          insert into exam_plan_enrollment
            (org_id,revision_id,candidate_id,demand_id,created_by,updated_by)
          values (?,?,?,?,?,?) returning id
          """, orgA, revisionA, candidate, demand1, userA, userA);
      insertId(c, """
          insert into exam_plan_candidate_window
            (org_id,revision_id,candidate_id,window_type,starts_at,ends_at,created_by,updated_by)
          values (?,?,?,?,?,?,?,?) returning id
          """, orgA, revisionA, candidate, "AVAILABLE", ts("2026-10-01T08:00:00Z"),
          ts("2026-10-01T12:00:00Z"), userA, userA);
      insertId(c, """
          insert into exam_plan_special_requirement
            (org_id,revision_id,candidate_id,demand_id,requirement_code,requirement_type,
             required_features,extra_minutes,created_by,updated_by)
          values (?,?,?,?,?,?,cast(? as jsonb),?,?,?) returning id
          """, orgA, revisionA, candidate, demand1, "SR-1", "ACCESSIBILITY",
          "[\"ACCESSIBLE\"]", 0, userA, userA);

      UUID roomPool1 = insertId(c, """
          insert into exam_plan_room_pool
            (org_id,revision_id,classroom_id,campus_code,exam_capacity,features,created_by,updated_by)
          values (?,?,?,?,?,cast(? as jsonb),?,?) returning id
          """, orgA, revisionA, classroomA, "MAIN", 24, "[\"ACCESSIBLE\"]", userA, userA);
      UUID roomPool2 = insertId(c, """
          insert into exam_plan_room_pool
            (org_id,revision_id,classroom_id,campus_code,exam_capacity,created_by,updated_by)
          values (?,?,?,?,?,?,?) returning id
          """, orgA, revisionA, classroomA2, "EAST", 24, userA, userA);
      insertId(c, """
          insert into exam_plan_room_window
            (org_id,revision_id,room_pool_id,window_type,starts_at,ends_at,created_by,updated_by)
          values (?,?,?,?,?,?,?,?) returning id
          """, orgA, revisionA, roomPool1, "AVAILABLE", ts("2026-10-01T08:00:00Z"),
          ts("2026-10-01T12:00:00Z"), userA, userA);
      UUID seat1 = insertId(c, """
          insert into exam_plan_room_seat
            (org_id,revision_id,room_pool_id,seat_code,row_no,column_no,created_by,updated_by)
          values (?,?,?,?,?,?,?,?) returning id
          """, orgA, revisionA, roomPool1, "A1", 1, 1, userA, userA);
      UUID seat2 = insertId(c, """
          insert into exam_plan_room_seat
            (org_id,revision_id,room_pool_id,seat_code,row_no,column_no,created_by,updated_by)
          values (?,?,?,?,?,?,?,?) returning id
          """, orgA, revisionA, roomPool2, "B1", 1, 1, userA, userA);
      UUID teacherPool = insertId(c, """
          insert into exam_plan_teacher_pool
            (org_id,revision_id,teacher_id,eligible_roles,created_by,updated_by)
          values (?,?,?,cast(? as jsonb),?,?) returning id
          """, orgA, revisionA, teacherA, "[\"INVIGILATOR\"]", userA, userA);
      insertId(c, """
          insert into exam_plan_teacher_avoidance
            (org_id,revision_id,teacher_pool_id,target_type,class_id,severity,reason,created_by,updated_by)
          values (?,?,?,?,?,?,?,?,?) returning id
          """, orgA, revisionA, teacherPool, "CLASS", classA, "SOFT", "Homeroom fairness", userA, userA);
      insertId(c, """
          insert into exam_plan_campus_travel
            (org_id,revision_id,from_campus,to_campus,travel_minutes,buffer_minutes,created_by,updated_by)
          values (?,?,?,?,?,?,?,?) returning id
          """, orgA, revisionA, "MAIN", "EAST", 20, 10, userA, userA);

      expectSqlState("23505", () -> insertId(c, """
          insert into exam_plan_exam_demand
            (org_id,revision_id,demand_code,name,duration_minutes)
          values (?,?,?,?,?) returning id
          """, orgA, revisionA, "D-1", "Duplicate", 60));
      expectSqlState("23514", () -> insertId(c, """
          insert into exam_plan_exam_demand
            (org_id,revision_id,demand_code,name,duration_minutes)
          values (?,?,?,?,?) returning id
          """, orgA, revisionA, "BAD-DURATION", "Bad", 0));
      expectSqlState("23514", () -> insertId(c, """
          insert into exam_plan_demand_window
            (org_id,revision_id,demand_id,window_code,starts_at,ends_at)
          values (?,?,?,?,?,?) returning id
          """, orgA, revisionA, demand1, "BAD-WINDOW", ts("2026-10-01T10:00:00Z"),
          ts("2026-10-01T09:00:00Z")));

      UUID session1 = insertId(c, """
          insert into exam_plan_session
            (org_id,revision_id,session_code,name,starts_at,ends_at,candidate_count,demand_id,source_type)
          values (?,?,?,?,?,?,?,?,?) returning id
          """, orgA, revisionA, "SES-1", "Session 1", ts("2026-10-01T09:00:00Z"),
          ts("2026-10-01T10:00:00Z"), 1, demand1, "JOINT");
      UUID roomAssignment1 = insertId(c, """
          insert into exam_plan_room_assignment
            (org_id,revision_id,session_id,classroom_id,starts_at,ends_at,planned_capacity,allocated_candidates)
          values (?,?,?,?,?,?,?,?) returning id
          """, orgA, revisionA, session1, classroomA, ts("2026-10-01T09:00:00Z"),
          ts("2026-10-01T10:00:00Z"), 24, 1);
      insertId(c, """
          insert into exam_plan_seat_assignment
            (org_id,revision_id,session_id,room_assignment_id,candidate_external_id,seat_label,
             candidate_id,seat_id,starts_at,ends_at,source_type)
          values (?,?,?,?,?,?,?,?,?,?,?) returning id
          """, orgA, revisionA, session1, roomAssignment1, "S-1", "A1", candidate, seat1,
          ts("2026-10-01T09:00:00Z"), ts("2026-10-01T10:00:00Z"), "JOINT");
      insertId(c, """
          insert into exam_plan_class_session_assignment
            (org_id,revision_id,session_id,demand_id,demand_class_id,class_id,starts_at,ends_at,created_by,updated_by)
          values (?,?,?,?,?,?,?,?,?,?) returning id
          """, orgA, revisionA, session1, demand1, demandClass1, classA,
          ts("2026-10-01T09:00:00Z"), ts("2026-10-01T10:00:00Z"), userA, userA);

      UUID demand2 = insertId(c, """
          insert into exam_plan_exam_demand
            (org_id,revision_id,demand_code,name,subject_id,duration_minutes)
          values (?,?,?,?,?,?) returning id
          """, orgA, revisionA, "D-2", "Mathematics 2", subjectA, 90);
      UUID demandClass2 = insertId(c, """
          insert into exam_plan_demand_class(org_id,revision_id,demand_id,class_id,expected_candidates)
          values (?,?,?,?,?) returning id
          """, orgA, revisionA, demand2, classA, 1);
      insertId(c, """
          insert into exam_plan_enrollment(org_id,revision_id,candidate_id,demand_id)
          values (?,?,?,?) returning id
          """, orgA, revisionA, candidate, demand2);
      UUID session2 = insertId(c, """
          insert into exam_plan_session
            (org_id,revision_id,session_code,name,starts_at,ends_at,candidate_count,demand_id,source_type)
          values (?,?,?,?,?,?,?,?,?) returning id
          """, orgA, revisionA, "SES-2", "Session 2", ts("2026-10-01T09:30:00Z"),
          ts("2026-10-01T11:00:00Z"), 1, demand2, "JOINT");
      UUID roomAssignment2 = insertId(c, """
          insert into exam_plan_room_assignment
            (org_id,revision_id,session_id,classroom_id,starts_at,ends_at,planned_capacity,allocated_candidates)
          values (?,?,?,?,?,?,?,?) returning id
          """, orgA, revisionA, session2, classroomA2, ts("2026-10-01T09:30:00Z"),
          ts("2026-10-01T11:00:00Z"), 24, 1);

      expectSqlState("23P01", () -> insertId(c, """
          insert into exam_plan_seat_assignment
            (org_id,revision_id,session_id,room_assignment_id,candidate_external_id,seat_label,
             candidate_id,seat_id,starts_at,ends_at,source_type)
          values (?,?,?,?,?,?,?,?,?,?,?) returning id
          """, orgA, revisionA, session2, roomAssignment2, "S-1", "B1", candidate, seat2,
          ts("2026-10-01T09:30:00Z"), ts("2026-10-01T11:00:00Z"), "JOINT"));
      expectSqlState("23P01", () -> insertId(c, """
          insert into exam_plan_class_session_assignment
            (org_id,revision_id,session_id,demand_id,demand_class_id,class_id,starts_at,ends_at)
          values (?,?,?,?,?,?,?,?) returning id
          """, orgA, revisionA, session2, demand2, demandClass2, classA,
          ts("2026-10-01T09:30:00Z"), ts("2026-10-01T11:00:00Z")));

      UUID candidate2 = insertId(c, """
          insert into exam_plan_candidate(org_id,revision_id,candidate_code,name,class_id)
          values (?,?,?,?,?) returning id
          """, orgA, revisionA, "S-2", "Student Two", classA);
      insertId(c, """
          insert into exam_plan_enrollment(org_id,revision_id,candidate_id,demand_id)
          values (?,?,?,?) returning id
          """, orgA, revisionA, candidate2, demand2);
      expectSqlState("23514", () -> insertId(c, """
          insert into exam_plan_seat_assignment
            (org_id,revision_id,session_id,room_assignment_id,candidate_external_id,seat_label,
             candidate_id,seat_id,starts_at,ends_at,source_type)
          values (?,?,?,?,?,?,?,?,?,?,?) returning id
          """, orgA, revisionA, session2, roomAssignment2, "S-2", "A1", candidate2, seat1,
          ts("2026-10-01T09:30:00Z"), ts("2026-10-01T11:00:00Z"), "JOINT"));

      UUID legacySession = insertId(c, """
          insert into exam_plan_session
            (org_id,revision_id,session_code,name,starts_at,ends_at,candidate_count)
          values (?,?,?,?,?,?,?) returning id
          """, orgA, revisionA, "LEGACY-1", "Legacy", ts("2026-10-01T12:00:00Z"),
          ts("2026-10-01T13:00:00Z"), 1);
      UUID legacyRoom = insertId(c, """
          insert into exam_plan_room_assignment
            (org_id,revision_id,session_id,classroom_id,starts_at,ends_at,planned_capacity,allocated_candidates)
          values (?,?,?,?,?,?,?,?) returning id
          """, orgA, revisionA, legacySession, classroomA, ts("2026-10-01T12:00:00Z"),
          ts("2026-10-01T13:00:00Z"), 24, 1);
      UUID legacySeatAssignment = insertId(c, """
          insert into exam_plan_seat_assignment
            (org_id,revision_id,session_id,room_assignment_id,candidate_external_id,seat_label)
          values (?,?,?,?,?,?) returning id
          """, orgA, revisionA, legacySession, legacyRoom, "LEGACY-STUDENT", "L1");
      assertThat(queryString(c, "select source_type from exam_plan_seat_assignment where id=?",
          legacySeatAssignment)).isEqualTo("LEGACY");
      assertThat(queryString(c, "select source_type from exam_plan_session where id=?",
          legacySession)).isEqualTo("LEGACY");

      String hashA = "a".repeat(64);
      String hashB = "b".repeat(64);
      UUID job = insertId(c, """
          insert into exam_plan_planning_job
            (org_id,plan_id,revision_id,input_hash,request_hash,solver_version,iteration_count)
          values (?,?,?,?,?,?,?) returning id
          """, orgA, planA, revisionA, hashA, hashA, "cp-sat-test", 0);
      execute(c, "update exam_plan_planning_job set status='RUNNING',stage='SOLVING' where id=?", job);
      expectSqlState("23514", () -> execute(c,
          "update exam_plan_planning_job set input_hash=? where id=?", hashB, job));
      insertId(c, """
          insert into exam_plan_planning_job(org_id,plan_id,revision_id,status,stage)
          values (?,?,?,'FAILED','LEGACY_NULL_PROVENANCE') returning id
          """, orgA, planA, revisionA);

      execute(c, "update exam_plan_revision set status='PUBLISHED' where id=?", revisionA);
      expectSqlState("23514", () -> execute(c,
          "update exam_plan_exam_demand set name='Forbidden rewrite' where id=?", demand1));
    }
  }

  private static Timestamp ts(String value) {
    return Timestamp.from(Instant.parse(value));
  }

  private static UUID insertId(Connection c, String sql, Object... values) throws SQLException {
    try (PreparedStatement ps = c.prepareStatement(sql)) {
      bind(ps, values);
      try (ResultSet rs = ps.executeQuery()) {
        assertThat(rs.next()).isTrue();
        return rs.getObject(1, UUID.class);
      }
    }
  }

  private static void execute(Connection c, String sql, Object... values) throws SQLException {
    try (PreparedStatement ps = c.prepareStatement(sql)) {
      bind(ps, values);
      ps.executeUpdate();
    }
  }

  private static int queryInt(Connection c, String sql, Object... values) throws SQLException {
    try (PreparedStatement ps = c.prepareStatement(sql)) {
      bind(ps, values);
      try (ResultSet rs = ps.executeQuery()) {
        assertThat(rs.next()).isTrue();
        return rs.getInt(1);
      }
    }
  }

  private static String queryString(Connection c, String sql, Object... values) throws SQLException {
    try (PreparedStatement ps = c.prepareStatement(sql)) {
      bind(ps, values);
      try (ResultSet rs = ps.executeQuery()) {
        assertThat(rs.next()).isTrue();
        return rs.getString(1);
      }
    }
  }

  private static void bind(PreparedStatement ps, Object... values) throws SQLException {
    for (int i = 0; i < values.length; i++) {
      ps.setObject(i + 1, values[i]);
    }
  }

  private static void expectSqlState(String expected, SqlWork work) {
    try {
      work.run();
      fail("Expected SQLSTATE " + expected);
    } catch (SQLException ex) {
      assertThat(ex.getSQLState()).isEqualTo(expected);
    }
  }

  @FunctionalInterface
  private interface SqlWork {
    void run() throws SQLException;
  }
}
