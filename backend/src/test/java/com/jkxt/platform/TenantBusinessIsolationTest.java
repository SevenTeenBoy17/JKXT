package com.jkxt.platform;

import static org.assertj.core.api.Assertions.assertThat;

import com.jkxt.common.tenant.TenantContext;
import com.jkxt.masterdata.service.MasterDataService;
import com.jkxt.platform.service.AdminPlatformService;
import com.jkxt.reporting.service.ReportingService;
import com.jkxt.security.auth.SessionPrincipal;
import com.jkxt.tasks.service.ExamTaskService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties = "jkxt.deploy.mode=saas")
@ActiveProfiles("test")
@Transactional
@WithMockUser(roles = "SUPER_ADMIN")
class TenantBusinessIsolationTest {
  @Autowired AdminPlatformService platform;
  @Autowired MasterDataService masterData;
  @Autowired ExamTaskService tasks;
  @Autowired ReportingService reporting;
  @Autowired JdbcTemplate jdbc;

  @AfterEach
  void clearContext() {
    TenantContext.clear();
    SecurityContextHolder.clearContext();
  }

  @Test
  void newTenantAndAccountsHaveOnlyInitialTermAndNoSampleBusinessRows() {
    UUID org = tenant();
    createAccount(org, "one");
    createAccount(org, "two");
    TenantContext.set(org);
    assertThat(jdbc.queryForObject(
        "select count(*) from academic_term where org_id=? and is_current", Integer.class, org)).isOne();
    for (String table : List.of("teacher", "classroom", "school_class", "subject", "exam_task",
        "exam", "exam_plan", "schedule_run", "schedule_assignment", "uploaded_file", "import_batch")) {
      assertThat(jdbc.queryForObject("select count(*) from " + table + " where org_id=?", Integer.class, org))
          .as("New tenant business table %s", table).isZero();
    }
    assertThat(tasks.list()).isEmpty();
    assertThat(masterData.listTeachers(null, null, null, null, null)).isEmpty();
    assertThat(masterData.listClassrooms(null, null, null)).isEmpty();
    assertThat((List<?>) reporting.supervise("teacher", "count").get("rows")).isEmpty();
  }

  @Test
  void accountsInSameSchoolShareItsRealRowsWhileAnotherSchoolIsEmpty() {
    UUID org = tenant();
    UUID first = createAccount(org, "first");
    UUID second = createAccount(org, "second");
    UUID otherOrg = tenant();
    UUID teacher = UUID.randomUUID();
    jdbc.update("""
        insert into teacher(id,org_id,teacher_no,name,gender,status)
        values (?,?,'REAL-001','School uploaded teacher','unknown','active')
        """, teacher, org);
    for (UUID user : List.of(first, second)) {
      TenantContext.set(org);
      SessionPrincipal principal = new SessionPrincipal("test", user, org, user.toString(), "Account",
          "ADMIN", List.of("masterdata:read"));
      SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
          principal, null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
      assertThat(masterData.listTeachers(null, null, null, null, null))
          .hasSize(1).first().extracting("uuid").isEqualTo(teacher);
    }
    TenantContext.set(otherOrg);
    assertThat(masterData.listTeachers(null, null, null, null, null)).isEmpty();
  }

  private UUID tenant() {
    String suffix = UUID.randomUUID().toString();
    return UUID.fromString(platform.createTenant(Map.of("name", "Isolated school", "slug", "truth-" + suffix))
        .get("uuid").toString());
  }

  private UUID createAccount(UUID org, String label) {
    String username = "truth-" + label + "-" + UUID.randomUUID();
    return UUID.fromString(platform.createAccount(Map.of("tenantId", org.toString(), "username", username,
        "name", label, "email", username + "@example.test", "role", "admin")).get("id").toString());
  }
}
