package com.jkxt.audit;

import static org.assertj.core.api.Assertions.assertThat;

import com.jkxt.audit.domain.AuditLog;
import com.jkxt.audit.repo.AuditLogRepository;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * 迭代8 待查项探针：复现"主数据审计行不落库"。隔离验证用 MasterDataService.audit 当时的字段组合
 * (actorId=null、targetId 非空、payload=Map.of(...) 不可变 map、severity=info) 直接经 AuditLogRepository.save
 * 是否能真正持久化。若此探针通过 → save 本身没问题，问题在 MasterDataService 调用上下文；若失败 → 字段组合即根因。
 */
@SpringBootTest
@ActiveProfiles("test")
class AuditPersistenceProbeTest {

  private static final UUID DEMO_ORG = UUID.fromString("00000000-0000-0000-0000-000000000001");

  @Autowired
  private AuditLogRepository auditLogRepository;

  @Autowired
  private JdbcTemplate jdbcTemplate;

  @Test
  void auditSaveWithImmutableMapPayloadAndNullActorPersists() {
    String action = "PROBE_" + System.nanoTime();
    AuditLog log = new AuditLog();
    log.setOrgId(DEMO_ORG);
    log.setActorId(null);
    log.setAction(action);
    log.setTargetType("teacher");
    log.setTargetId(UUID.randomUUID());
    log.setSeverity("info");
    log.setPayload(Map.of("teacherNo", "PRB1", "name", "探针老师"));

    auditLogRepository.saveAndFlush(log);

    Integer count = jdbcTemplate.queryForObject(
        "select count(*) from audit_log where action = ?", Integer.class, action);
    assertThat(count).isEqualTo(1);
  }
}
