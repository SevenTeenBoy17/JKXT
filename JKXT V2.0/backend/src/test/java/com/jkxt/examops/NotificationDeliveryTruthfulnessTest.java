package com.jkxt.examops;

import static org.assertj.core.api.Assertions.assertThat;

import com.jkxt.common.tenant.TenantContext;
import com.jkxt.examops.api.NotificationDeliveryDtos.DispatchRequest;
import com.jkxt.examops.service.NotificationDeliveryService;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class NotificationDeliveryTruthfulnessTest {
  @Autowired JdbcTemplate jdbc;
  @Autowired NotificationDeliveryService service;
  private UUID org;
  private UUID outbox;

  @BeforeEach
  void createIsolatedOrganization() {
    org = UUID.randomUUID();
    outbox = UUID.randomUUID();
    jdbc.update("insert into organization(id,name,slug) values (?, 'Delivery truth test', ?)",
        org, "delivery-truth-" + org);
    jdbc.update("""
        insert into exam_plan_outbox(id,org_id,event_type,aggregate_type,payload)
        values (?,?,'TRUTH_TEST','notification','{}'::jsonb)
        """, outbox, org);
    TenantContext.set(org);
  }

  @AfterEach void clearTenant() { TenantContext.clear(); }

  @ParameterizedTest
  @ValueSource(strings = { "SMS", "EMAIL", "UNKNOWN" })
  void unsupportedChannelsRemainFailedWithoutLocalReceipt(String channel) {
    UUID id = delivery(channel);
    for (int attempt = 1; attempt <= 2; attempt++) {
      var result = service.dispatch(new DispatchRequest(10, Set.of(), true), null);
      assertThat(result.selected()).isOne();
      assertThat(result.delivered()).isZero();
      assertThat(result.failed()).isOne();
      Map<String, Object> row = jdbc.queryForMap("""
          select status, failure_reason, provider_message_id, receipt::text, delivered_at, attempt_count
          from exam_plan_delivery where id=?
          """, id);
      assertThat(row).containsEntry("status", "FAILED")
          .containsEntry("failure_reason", "CHANNEL_NOT_CONFIGURED")
          .containsEntry("provider_message_id", null).containsEntry("delivered_at", null)
          .containsEntry("receipt", "{}").containsEntry("attempt_count", attempt);
      assertThat(jdbc.queryForObject("select status from exam_plan_outbox where id=?", String.class, outbox))
          .isEqualTo("FAILED");
    }
  }

  @Test
  void inAppStillDeliversOnceWithPersistedReceipt() {
    UUID id = delivery("IN_APP");
    var first = service.dispatch(new DispatchRequest(10, Set.of(), true), null);
    assertThat(first.delivered()).isOne();
    assertThat(first.failed()).isZero();
    assertThat(jdbc.queryForMap("""
        select status, provider_message_id, attempt_count from exam_plan_delivery where id=?
        """, id)).containsEntry("status", "DELIVERED")
        .containsEntry("provider_message_id", "local:" + id).containsEntry("attempt_count", 1);
    assertThat(service.dispatch(new DispatchRequest(10, Set.of(), true), null).selected()).isZero();
    assertThat(jdbc.queryForObject("select status from exam_plan_outbox where id=?", String.class, outbox))
        .isEqualTo("SENT");
  }

  private UUID delivery(String channel) {
    UUID id = UUID.randomUUID();
    jdbc.update("""
        insert into exam_plan_delivery(id,org_id,outbox_id,recipient_type,recipient_id,channel,status)
        values (?,?,?,'TEACHER','test-recipient',?,'PENDING')
        """, id, org, outbox, channel);
    return id;
  }
}
