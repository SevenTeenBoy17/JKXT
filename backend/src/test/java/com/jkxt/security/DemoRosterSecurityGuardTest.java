package com.jkxt.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/** Round 11: the synthetic V5 demo roster is retired only when jkxt.security.disable-demo-roster is on. */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class DemoRosterSecurityGuardTest {

  private static final UUID ORG = UUID.fromString(DemoRosterSecurityGuard.DEMO_ORG_ID);
  private static final UUID PRISTINE_TEACHER = UUID.fromString("00000000-0000-0000-0000-000000004005");

  @Autowired
  private JdbcTemplate jdbc;

  private List<Map<String, Object>> seedRows() {
    return jdbc.queryForList(
        "SELECT id::text AS id, status, email FROM teacher WHERE org_id = ?::uuid AND teacher_no LIKE 'T202600%' ORDER BY teacher_no",
        DemoRosterSecurityGuard.DEMO_ORG_ID);
  }

  @Test
  void retiresOnlyPristineActiveSeedTeachersWhenFlagEnabled() {
    List<Map<String, Object>> original = seedRows();
    long activeBefore = original.stream().filter(r -> "active".equals(r.get("status"))).count();
    assertThat(activeBefore).as("V5 seed roster has active demo teachers in the test DB").isGreaterThan(1);
    String edited = "00000000-0000-0000-0000-000000004001";
    String originalEmail = jdbc.queryForObject("SELECT email FROM teacher WHERE id = ?::uuid", String.class, edited);
    try {
      assertThat(new DemoRosterSecurityGuard(jdbc, false).retireSyntheticDemoRoster()).isZero();
      assertThat(seedRows()).isEqualTo(original);

      // A row the school edited is no longer the seed identity and must be left alone.
      jdbc.update("UPDATE teacher SET email = 'real.teacher@school.example' WHERE id = ?::uuid", edited);
      DemoRosterSecurityGuard on = new DemoRosterSecurityGuard(jdbc, true);
      int retired = on.retireSyntheticDemoRoster();
      assertThat(retired).isEqualTo((int) activeBefore - 1);
      assertThat(jdbc.queryForObject("SELECT status FROM teacher WHERE id = ?::uuid", String.class, edited)).isEqualTo("active");
      assertThat(jdbc.queryForObject(
          "SELECT count(*) FROM teacher WHERE org_id = ?::uuid AND email LIKE '%@demo.local' AND status = 'active'",
          Integer.class, DemoRosterSecurityGuard.DEMO_ORG_ID)).isZero();
      assertThat(on.retireSyntheticDemoRoster()).as("idempotent").isZero();
    } finally {
      jdbc.update("UPDATE teacher SET email = ? WHERE id = ?::uuid", originalEmail, edited);
      for (Map<String, Object> row : original) {
        jdbc.update("UPDATE teacher SET status = ? WHERE id = ?::uuid", row.get("status"), row.get("id"));
      }
    }
    assertThat(seedRows()).isEqualTo(original);
  }

  @Test
  void retirementPreservesPublishedAssignmentsAndTheirTeacherSnapshot() {
    assertThat(jdbc.queryForObject("SELECT status FROM teacher WHERE id = ?", String.class, PRISTINE_TEACHER))
        .as("the historical fixture starts with an active pristine seed teacher").isEqualTo("active");
    UUID assignment = publishedAssignmentFor(PRISTINE_TEACHER);
    Map<String, Object> historyBefore = publishedHistory(assignment);

    DemoRosterSecurityGuard on = new DemoRosterSecurityGuard(jdbc, true);
    assertThat(on.retireSyntheticDemoRoster()).isPositive();

    assertThat(jdbc.queryForObject("SELECT status FROM teacher WHERE id = ?", String.class, PRISTINE_TEACHER))
        .isEqualTo("left");
    assertThat(publishedHistory(assignment))
        .as("the historical assignment, snapshot and live teacher reference remain intact")
        .isEqualTo(historyBefore);
    assertThat(on.retireSyntheticDemoRoster()).as("retirement remains idempotent with history").isZero();
    assertThat(publishedHistory(assignment)).isEqualTo(historyBefore);
  }

  @Test
  void reimportedSameNumberAndContactWithANewIdIsNotRetired() {
    // Free the unique number/email inside this rolled-back transaction, without deleting seed history.
    UUID imported = UUID.randomUUID();
    jdbc.update("UPDATE teacher SET teacher_no = ?, email = ? WHERE id = ?",
        "R12-SOURCE-" + imported.toString().substring(0, 8),
        "r12-source-" + imported + "@example.invalid", PRISTINE_TEACHER);
    insertSyntheticTeacher(imported, ORG, "R12 reimport fixture");
    String importedBefore = teacherRecord(imported);
    String editedSeedBefore = teacherRecord(PRISTINE_TEACHER);

    assertThat(new DemoRosterSecurityGuard(jdbc, true).retireSyntheticDemoRoster()).isPositive();

    assertThat(teacherRecord(imported))
        .as("matching number/email/phone do not make a new identity a V5 seed")
        .isEqualTo(importedBefore);
    assertThat(teacherRecord(PRISTINE_TEACHER))
        .as("the edited original identity also remains untouched").isEqualTo(editedSeedBefore);
  }

  @Test
  void matchingNumberAndContactInAnotherOrganizationRemainUntouched() {
    UUID otherOrg = UUID.randomUUID();
    UUID otherTeacher = UUID.randomUUID();
    jdbc.update("INSERT INTO organization (id, name, slug) VALUES (?, ?, ?)",
        otherOrg, "R12 isolated school fixture", "r12-" + otherOrg);
    insertSyntheticTeacher(otherTeacher, otherOrg, "R12 other school teacher");
    String before = teacherRecord(otherTeacher);

    assertThat(new DemoRosterSecurityGuard(jdbc, true).retireSyntheticDemoRoster()).isPositive();

    assertThat(teacherRecord(otherTeacher))
        .as("another school's matching teacher is not retired or otherwise changed").isEqualTo(before);
  }

  private void insertSyntheticTeacher(UUID teacher, UUID org, String name) {
    jdbc.update("""
        INSERT INTO teacher (id, org_id, teacher_no, name, gender, phone, email, status)
        VALUES (?, ?, 'T2026005', ?, 'unknown', '13800001005', 'zhou.xh@demo.local', 'active')
        """, teacher, org, name);
  }

  private String teacherRecord(UUID teacher) {
    return jdbc.queryForObject("SELECT to_jsonb(t)::text FROM teacher t WHERE id = ?", String.class, teacher);
  }

  private UUID publishedAssignmentFor(UUID teacher) {
    UUID plan = UUID.randomUUID(), revision = UUID.randomUUID(), session = UUID.randomUUID();
    UUID requirement = UUID.randomUUID(), assignment = UUID.randomUUID();
    jdbc.update("""
        INSERT INTO exam_plan (id, org_id, plan_no, name, status)
        VALUES (?, ?, ?, 'R12 historical roster fixture', 'DRAFT')
        """, plan, ORG, "R12-" + plan);
    jdbc.update("""
        INSERT INTO exam_plan_revision (id, org_id, plan_id, revision_no, status, source_type)
        VALUES (?, ?, ?, 1, 'DRAFT', 'API')
        """, revision, ORG, plan);
    jdbc.update("""
        INSERT INTO exam_plan_session (id, org_id, revision_id, session_code, name,
          starts_at, ends_at, candidate_count)
        VALUES (?, ?, ?, 'R12-HISTORY', 'Historical session fixture',
          '2001-02-03 08:00:00+00'::timestamptz, '2001-02-03 10:00:00+00'::timestamptz, 0)
        """, session, ORG, revision);
    jdbc.update("""
        INSERT INTO exam_plan_duty_requirement (id, org_id, revision_id, session_id, duty_role, required_count)
        VALUES (?, ?, ?, ?, 'INVIGILATOR', 1)
        """, requirement, ORG, revision, session);
    jdbc.update("""
        INSERT INTO exam_plan_duty_assignment
          (id, org_id, revision_id, requirement_id, teacher_id, starts_at, ends_at, duty_role)
        VALUES (?, ?, ?, ?, ?, '2001-02-03 08:00:00+00'::timestamptz,
          '2001-02-03 10:00:00+00'::timestamptz, 'INVIGILATOR')
        """, assignment, ORG, revision, requirement, teacher);
    jdbc.update("UPDATE exam_plan_revision SET status = 'PUBLISHED', published_at = now() WHERE id = ?", revision);
    jdbc.update("UPDATE exam_plan SET status = 'PUBLISHED', active_revision_id = ? WHERE id = ?", revision, plan);
    return assignment;
  }

  private Map<String, Object> publishedHistory(UUID assignment) {
    return jdbc.queryForMap("""
        SELECT to_jsonb(a)::text AS assignment, to_jsonb(s)::text AS teacher_snapshot
        FROM exam_plan_duty_assignment a
        JOIN teacher t ON t.org_id = a.org_id AND t.id = a.teacher_id
        JOIN exam_plan_teacher_snapshot s
          ON s.org_id = a.org_id AND s.revision_id = a.revision_id AND s.teacher_id = a.teacher_id
        WHERE a.id = ?
        """, assignment);
  }
}
