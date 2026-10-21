package com.jkxt.masterdata;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * PB-2 (2026-10-18): V24 removed the V23 compatibility CHIEF grant from every pre-V23 teacher,
 * including the synthetic V5 demo roster, so the shipped sample exam (2 supervisors per room:
 * one CHIEF + one INVIGILATOR) became unschedulable (H2_CHIEF_QUALIFICATION). V34 restores CHIEF
 * only for active, still-unmodified synthetic demo seed rows. Real PostgreSQL + Flyway, own
 * disposable container, synthetic data only.
 */
@EnabledIfSystemProperty(named = "jkxt.testcontainers.enabled", matches = "true")
class DemoSeedChiefMigrationIntegrationTest {
  private static final String ORG = "00000000-0000-0000-0000-000000000001";
  private static PostgreSQLContainer<?> postgres;

  @BeforeAll
  static void start() {
    postgres = new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
        .withDatabaseName("jkxt_test_demo_seed_chief")
        .withUsername("jkxt_test")
        .withPassword("synthetic-isolated-migration-only");
    postgres.start();
  }

  @AfterAll
  static void stop() {
    if (postgres != null) postgres.stop();
  }

  @Test
  void version34RestoresChiefOnlyForUnmodifiedActiveSyntheticDemoSeedTeachers() throws Exception {
    String url = postgres.getJdbcUrl();
    migrate(url, "33");
    try (Connection c = connect(url)) {
      for (int i = 1; i <= 6; i++) {
        assertThat(qualifications(c, seed(i))).as("V24 baseline for seed %s", i).isEqualTo("[\"INVIGILATOR\"]");
      }
      // A school re-imported T2026002 with real contact data: no longer the synthetic seed row.
      exec(c, "update teacher set email='real.teacher@school.example' where id=?::uuid", seed(2));
      // An administrator explicitly set a qualification expiry on T2026003: explicit decision, keep it.
      exec(c, "update teacher set qualification_expiry=date '2027-01-31' where id=?::uuid", seed(3));
      // A non-seed teacher in the same organization with the post-V23 default.
      UUID other = UUID.randomUUID();
      exec(c, """
          insert into teacher select (jsonb_populate_record(null::teacher, to_jsonb(t)
            || jsonb_build_object('id', ?::text, 'teacher_no', 'T-NONSEED-001', 'email', 'nonseed@school.example',
                                  'phone', '13900009999'))).* from teacher t where t.id=?::uuid
          """, other.toString(), seed(1));
      String history = string(c, "select string_agg(version||':'||coalesce(checksum::text,''),',' order by installed_rank) from flyway_schema_history");

      assertThat(migrate(url, "34")).as("exactly one forward migration (V34) applies").isOne();

      assertThat(qualifications(c, seed(1))).isEqualTo("[\"INVIGILATOR\", \"CHIEF\"]");
      assertThat(qualifications(c, seed(4))).isEqualTo("[\"INVIGILATOR\", \"CHIEF\"]");
      assertThat(qualifications(c, seed(5))).isEqualTo("[\"INVIGILATOR\", \"CHIEF\"]");
      assertThat(qualifications(c, seed(2))).as("re-imported row untouched").isEqualTo("[\"INVIGILATOR\"]");
      assertThat(qualifications(c, seed(3))).as("explicitly re-qualified row untouched").isEqualTo("[\"INVIGILATOR\"]");
      assertThat(qualifications(c, seed(6))).as("inactive (left) seed row untouched").isEqualTo("[\"INVIGILATOR\"]");
      assertThat(qualifications(c, other.toString())).as("non-seed teacher untouched").isEqualTo("[\"INVIGILATOR\"]");
      assertThat(string(c, "select string_agg(version||':'||coalesce(checksum::text,''),',' order by installed_rank) from flyway_schema_history where version::int <= 33"))
          .as("applied V1..V33 history (including V24 checksum) is not altered").isEqualTo(history);
      assertThat(migrate(url, "34")).as("repeat migrate is a no-op").isZero();
    }
  }

  private static String seed(int n) {
    return "00000000-0000-0000-0000-00000000400" + n;
  }

  private static int migrate(String url, String target) {
    return Flyway.configure().dataSource(url, postgres.getUsername(), postgres.getPassword())
        .locations("classpath:db/migration").target(target).load().migrate().migrationsExecuted;
  }

  private static Connection connect(String url) throws SQLException {
    return DriverManager.getConnection(url, postgres.getUsername(), postgres.getPassword());
  }

  private static String qualifications(Connection c, String id) throws SQLException {
    return string(c, "select qualifications::text from teacher where org_id='" + ORG + "'::uuid and id=?::uuid", id);
  }

  private static void exec(Connection c, String sql, Object... values) throws SQLException {
    try (PreparedStatement ps = c.prepareStatement(sql)) {
      for (int i = 0; i < values.length; i++) ps.setObject(i + 1, values[i]);
      ps.executeUpdate();
    }
  }

  private static String string(Connection c, String sql, Object... values) throws SQLException {
    try (PreparedStatement ps = c.prepareStatement(sql)) {
      for (int i = 0; i < values.length; i++) ps.setObject(i + 1, values[i]);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? rs.getString(1) : null;
      }
    }
  }
}