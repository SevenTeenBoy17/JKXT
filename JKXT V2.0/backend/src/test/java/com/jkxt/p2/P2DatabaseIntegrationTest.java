package com.jkxt.p2;

import static org.assertj.core.api.Assertions.assertThat;

import com.jkxt.masterdata.domain.Teacher;
import com.jkxt.masterdata.repo.ClassroomRepository;
import com.jkxt.masterdata.repo.SchoolClassRepository;
import com.jkxt.masterdata.repo.SubjectRepository;
import com.jkxt.masterdata.repo.TeacherRepository;
import com.jkxt.org.domain.Organization;
import com.jkxt.org.repo.AcademicTermRepository;
import com.jkxt.org.repo.OrganizationRepository;
import com.jkxt.security.repo.UserAccountRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class P2DatabaseIntegrationTest {

  private static final List<String> REQUIRED_TABLES = List.of(
      "organization",
      "role",
      "user_role",
      "app_user",
      "academic_term",
      "teacher",
      "classroom",
      "school_class",
      "subject",
      "teacher_subject",
      "school_class_subject",
      "exam_task",
      "exam",
      "exam_class",
      "exam_room",
      "uploaded_file",
      "import_batch",
      "validation_issue",
      "schedule_run",
      "schedule_assignment",
      "audit_log",
      "ai_call_log"
  );

  @Autowired
  private JdbcTemplate jdbcTemplate;

  @Autowired
  private OrganizationRepository organizationRepository;

  @Autowired
  private UserAccountRepository userAccountRepository;

  @Autowired
  private AcademicTermRepository academicTermRepository;

  @Autowired
  private TeacherRepository teacherRepository;

  @Autowired
  private ClassroomRepository classroomRepository;

  @Autowired
  private SchoolClassRepository schoolClassRepository;

  @Autowired
  private SubjectRepository subjectRepository;

  @Test
  void flywayCreatesRequiredP2Tables() {
    for (String table : REQUIRED_TABLES) {
      String regclass = jdbcTemplate.queryForObject(
          "select to_regclass(?)::text",
          String.class,
          "public." + table
      );
      assertThat(regclass).as(table).isEqualTo(table);
    }
  }

  @Test
  void seedCreatesDemoOrganizationAdminRoleAndCurrentTerm() {
    Organization demo = organizationRepository.findBySlug("demo").orElseThrow();
    UUID orgId = demo.getId();

    // P18 批次6.1 删除了零业务消费者的 Role 实体/RoleRepository（role 表保留，AuthService 裸 SQL 在用）；
    // 本断言改走 JdbcTemplate 验证同一事实：种子必须建出该 org 的 ADMIN 角色行。
    Integer adminRoleRows = jdbcTemplate.queryForObject(
        "select count(*) from role where org_id = ? and code = 'ADMIN'", Integer.class, orgId);
    assertThat(adminRoleRows).isEqualTo(1);
    assertThat(userAccountRepository.findByOrgIdAndUsername(orgId, "admin"))
        .hasValueSatisfying(admin -> {
          // The V10 P13 demo-login migration intentionally promoted the seeded `admin` account to the
          // platform SUPER_ADMIN operator (ON CONFLICT DO UPDATE SET role = 'SUPER_ADMIN'); this
          // assertion was written against the original P2 seed and is updated to match that reality.
          assertThat(admin.getRole()).isEqualTo("SUPER_ADMIN");
          assertThat(admin.getStatus()).isEqualTo("active");
          assertThat(admin.isOnboarded()).isTrue();
        });
    assertThat(academicTermRepository.findByOrgIdAndCurrentTrue(orgId))
        .hasSize(1)
        .first()
        .satisfies(term -> assertThat(term.getYearLabel()).isEqualTo("2025-2026"));
  }

  @Test
  void repositoriesCanPersistTenantScopedMasterData() {
    Organization demo = organizationRepository.findBySlug("demo").orElseThrow();

    Teacher teacher = new Teacher();
    teacher.setOrgId(demo.getId());
    teacher.setTeacherNo("IT-" + UUID.randomUUID().toString().substring(0, 8));
    teacher.setName("Integration Test Teacher");
    teacher.setGender("unknown");
    teacher.setGrade("G9");
    teacher.setStatus("active");

    Teacher saved = teacherRepository.saveAndFlush(teacher);

    assertThat(teacherRepository.findByOrgIdAndTeacherNo(demo.getId(), saved.getTeacherNo()))
        .isPresent();
    assertThat(classroomRepository.count()).isGreaterThanOrEqualTo(0);
    assertThat(schoolClassRepository.count()).isGreaterThanOrEqualTo(0);
    assertThat(subjectRepository.count()).isGreaterThanOrEqualTo(0);
  }
}
