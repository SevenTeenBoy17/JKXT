package com.jkxt.examops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jkxt.common.tenant.TenantContext;
import com.jkxt.common.web.ApiException;
import com.jkxt.examops.api.NotificationDeliveryDtos.DispatchRequest;
import com.jkxt.examops.api.NotificationDeliveryDtos.ReadReceiptRequest;
import com.jkxt.examops.service.NotificationDeliveryService;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * Round 9, ADR-08 / NOTIFY-03 / NOTIFY-04:
 * PB-16 - with fault injection at its production default (disabled), failDeliveryIds is refused with
 *         422 FAULT_INJECTION_DISABLED and no delivery row changes;
 * PB-15 - a client read receipt that forges provider / providerMessageId / deliveredAt cannot alter the
 *         dispatcher's proof; it is stored only under receipt.clientReceipt; non-object receipts are refused.
 */
@SpringBootTest(properties = "jkxt.notify.fault-injection-enabled=false")
@ActiveProfiles("test")
@Transactional
class NotificationProofIsolationTest {
  @Autowired JdbcTemplate jdbc;
  @Autowired NotificationDeliveryService service;
  @Autowired ObjectMapper objectMapper;
  private UUID org;
  private UUID outbox;
  private UUID user;

  @BeforeEach
  void createIsolatedOrganization() {
    org = UUID.randomUUID();
    outbox = UUID.randomUUID();
    user = UUID.randomUUID();
    jdbc.update("insert into organization(id,name,slug) values (?, 'Delivery proof test', ?)",
        org, "delivery-proof-" + org);
    jdbc.update("""
        insert into exam_plan_outbox(id,org_id,event_type,aggregate_type,payload)
        values (?,?,'PROOF_TEST','notification','{}'::jsonb)
        """, outbox, org);
    jdbc.update("""
        insert into app_user(id,org_id,username,display_name,password_hash,role)
        values (?,?,?,'Synthetic receipt teacher','not-a-login-credential','TEACHER_QUERY')
        """, user, org, "proof.teacher." + user);
    jdbc.update("""
        insert into teacher(id,org_id,teacher_no,name,gender,status,is_homeroom,app_user_id)
        values (?,?,'PROOF-T1','Synthetic receipt teacher','unknown','active',false,?)
        """, UUID.randomUUID(), org, user);
    TenantContext.set(org);
  }

  @AfterEach void clearTenant() { TenantContext.clear(); }

  @Test
  void faultInjectionIsRefusedOutsideTestDeploymentsWithoutSideEffects() {
    UUID id = delivery();
    assertThatThrownBy(() -> service.dispatch(new DispatchRequest(10, Set.of(id), true), null))
        .isInstanceOfSatisfying(ApiException.class, e -> {
          assertThat(e.getStatus().value()).isEqualTo(422);
          assertThat(e.getCode()).isEqualTo("FAULT_INJECTION_DISABLED");
        });
    assertThat(jdbc.queryForMap("select status, attempt_count from exam_plan_delivery where id=?", id))
        .containsEntry("status", "PENDING").containsEntry("attempt_count", 0);
    // the ordinary (non-injected) dispatch and retry path still works
    assertThat(service.dispatch(new DispatchRequest(10, Set.of(), true), null).delivered()).isOne();
  }

  @Test
  void forgedClientReceiptCannotOverwriteProviderProof() throws Exception {
    UUID id = delivery();
    service.dispatch(new DispatchRequest(10, null, true), null);
    Map<String, Object> before = proof(id);
    var forged = objectMapper.readTree("""
        {"provider":"FORGED_PROVIDER","providerMessageId":"forged-123","deliveredAt":"2000-01-01T00:00:00Z","client":"web"}
        """);
    var view = service.markRead(user, id, new ReadReceiptRequest(forged));
    assertThat(view.status()).isEqualTo("READ");
    assertThat(proof(id)).isEqualTo(before);
    assertThat(view.receipt().path("provider").asText()).isEqualTo("LOCAL_DISPATCH");
    assertThat(view.receipt().path("providerMessageId").asText()).isEqualTo("local:" + id);
    assertThat(view.receipt().path("clientReceipt").path("provider").asText()).isEqualTo("FORGED_PROVIDER");
    assertThat(view.receipt().path("clientReceipt").path("client").asText()).isEqualTo("web");
  }

  @Test
  void nonObjectClientReceiptIsRefused() throws Exception {
    UUID id = delivery();
    service.dispatch(new DispatchRequest(10, null, true), null);
    Map<String, Object> before = proof(id);
    assertThatThrownBy(() -> service.markRead(user, id, new ReadReceiptRequest(objectMapper.readTree("[1,2]"))))
        .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("DELIVERY_RECEIPT_INVALID"));
    assertThat(proof(id)).isEqualTo(before);
    assertThat(jdbc.queryForObject("select status from exam_plan_delivery where id=?", String.class, id))
        .isEqualTo("DELIVERED");
  }

  private Map<String, Object> proof(UUID id) {
    return jdbc.queryForMap("""
        select receipt->>'provider' as provider, receipt->>'providerMessageId' as message_id,
               receipt->>'deliveredAt' as receipt_delivered_at, provider_message_id, delivered_at
        from exam_plan_delivery where id=?
        """, id);
  }

  private UUID delivery() {
    UUID id = UUID.randomUUID();
    jdbc.update("""
        insert into exam_plan_delivery(id,org_id,outbox_id,recipient_type,recipient_id,channel,status)
        values (?,?,?,'TEACHER','PROOF-T1','IN_APP','PENDING')
        """, id, org, outbox);
    return id;
  }
}
