package com.jkxt.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * Round 9, PB-18 (OPS-01): V35 retires the fabricated V9 demo backup 'BAK-P10-SEED' (status succeeded,
 * fake checksum, no artifact) and touches nothing else.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class FabricatedSeedBackupRetirementTest {

  private static final String SEED_ID = "00000000-0000-0000-0000-000000093001";

  @Autowired
  private JdbcTemplate jdbcTemplate;

  @Test
  void migratedDatabaseListsNoFabricatedSucceededBackup() {
    assertThat(jdbcTemplate.queryForObject(
        "select count(*) from backup_record where backup_no='BAK-P10-SEED' or checksum='p10-seed-checksum'",
        Integer.class)).isZero();
  }

  @Test
  void migrationRemovesOnlyTheExactSeedRow() throws Exception {
    jdbcTemplate.update("delete from backup_record where id=?::uuid", SEED_ID);
    insert(SEED_ID, "BAK-P10-SEED", "p10-seed-checksum", "{\"source\":\"p10-seed\"}");
    // same size/storage but a real backup number and checksum: must survive
    insert("00000000-0000-0000-0000-0000000a3002", "BAK-R9-REAL", "sha256:" + "a".repeat(64), "{\"source\":\"backup-service\"}");
    String sql = new String(new ClassPathResource("db/migration/V35__retire_fabricated_seed_backup_record.sql")
        .getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    jdbcTemplate.execute(sql);
    assertThat(jdbcTemplate.queryForObject("select count(*) from backup_record where id=?::uuid", Integer.class, SEED_ID))
        .isZero();
    assertThat(jdbcTemplate.queryForObject("select count(*) from backup_record where backup_no='BAK-R9-REAL'", Integer.class))
        .isOne();
    // idempotent: a second run is a no-op
    jdbcTemplate.execute(sql);
    assertThat(jdbcTemplate.queryForObject("select count(*) from backup_record where backup_no='BAK-R9-REAL'", Integer.class))
        .isOne();
  }

  private void insert(String id, String no, String checksum, String payload) {
    jdbcTemplate.update("""
        insert into backup_record (id, backup_no, status, scope, storage, size_mb, checksum, started_at, finished_at, payload)
        values (?::uuid, ?, 'succeeded', 'platform', 'local-snapshot', 4200.00, ?, now() - interval '6 hours',
                now() - interval '5 hours 55 minutes', ?::jsonb)
        """, id, no, checksum, payload);
  }
}
