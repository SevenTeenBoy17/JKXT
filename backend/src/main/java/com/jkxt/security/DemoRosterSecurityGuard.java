package com.jkxt.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Round 11 go-live prerequisite: retires the synthetic V5 demo roster (organization
 * 00000000-...-0001, teachers T2026001-T2026006, *@demo.local) that V34 re-qualified as CHIEF, so a
 * real school deployed on the fixed org never schedules fictitious teachers.
 *
 * <p>Controlled by {@code jkxt.security.disable-demo-roster}: OFF by default (dev, test suite and the
 * on-prem demo keep the sample exam schedulable), ON by default in the {@code saas} profile and in the
 * production deployment bundle. Only rows that are still exactly the V5 seed identity (id,
 * organization, teacher_no, seed e-mail and phone) and still {@code active} are set to {@code left};
 * rows a school re-imported or edited are untouched. Nothing is deleted, so historical assignments keep
 * their foreign keys. Idempotent; re-runs on every boot like {@link DemoAccountSecurityGuard}.
 */
@Component
public class DemoRosterSecurityGuard {

  private static final Logger log = LoggerFactory.getLogger(DemoRosterSecurityGuard.class);

  static final String DEMO_ORG_ID = "00000000-0000-0000-0000-000000000001";

  private static final String RETIRE_SQL = """
      UPDATE teacher t
      SET status = 'left', updated_at = now()
      FROM (VALUES
        ('00000000-0000-0000-0000-000000004001'::uuid, 'T2026001', 'wang.yn@demo.local', '13800001001'),
        ('00000000-0000-0000-0000-000000004002'::uuid, 'T2026002', 'li.my@demo.local',   '13800001002'),
        ('00000000-0000-0000-0000-000000004003'::uuid, 'T2026003', 'chen.sy@demo.local', '13800001003'),
        ('00000000-0000-0000-0000-000000004004'::uuid, 'T2026004', 'zhao.qh@demo.local', '13800001004'),
        ('00000000-0000-0000-0000-000000004005'::uuid, 'T2026005', 'zhou.xh@demo.local', '13800001005'),
        ('00000000-0000-0000-0000-000000004006'::uuid, 'T2026006', 'lin.yz@demo.local',  '13800001006')
      ) AS seed(id, teacher_no, email, phone)
      WHERE t.id = seed.id
        AND t.org_id = ?::uuid
        AND t.teacher_no = seed.teacher_no
        AND t.email = seed.email
        AND t.phone = seed.phone
        AND t.status = 'active'
      """;

  private final JdbcTemplate jdbc;
  private final boolean disableDemoRoster;

  public DemoRosterSecurityGuard(
      JdbcTemplate jdbc,
      @Value("${jkxt.security.disable-demo-roster:false}") boolean disableDemoRoster
  ) {
    this.jdbc = jdbc;
    this.disableDemoRoster = disableDemoRoster;
  }

  @EventListener(ApplicationReadyEvent.class)
  public void enforceOnStartup() {
    retireSyntheticDemoRoster();
  }

  /** Returns the number of seed teachers newly retired (0 when the flag is off). */
  @Transactional
  public int retireSyntheticDemoRoster() {
    if (!disableDemoRoster) {
      return 0;
    }
    int retired = jdbc.update(RETIRE_SQL, DEMO_ORG_ID);
    if (retired > 0) {
      log.warn("SECURITY: retired {} synthetic demo roster teacher(s) (status=left) in org {} "
          + "(jkxt.security.disable-demo-roster=true).", retired, DEMO_ORG_ID);
    }
    return retired;
  }
}