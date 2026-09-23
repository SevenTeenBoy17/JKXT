package com.jkxt.examops.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jkxt.common.tenant.TenantContext;
import com.jkxt.common.web.ApiException;
import com.jkxt.examops.api.NotificationDeliveryDtos.DeliveryView;
import com.jkxt.examops.api.NotificationDeliveryDtos.DispatchRequest;
import com.jkxt.examops.api.NotificationDeliveryDtos.DispatchResponse;
import com.jkxt.examops.api.NotificationDeliveryDtos.ReadReceiptRequest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class NotificationDeliveryService {

  private final JdbcTemplate jdbcTemplate;
  private final ObjectMapper objectMapper;

  public NotificationDeliveryService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
    this.jdbcTemplate = jdbcTemplate;
    this.objectMapper = objectMapper;
  }

  @Transactional
  public DispatchResponse dispatch(DispatchRequest request, UUID actorId) {
    UUID orgId = TenantContext.getRequired();
    int limit = request == null || request.limit() == null ? 100 : request.limit();
    boolean includeDeferred = request != null && Boolean.TRUE.equals(request.includeDeferred());
    Set<UUID> forcedFailures = request == null || request.failDeliveryIds() == null
        ? Set.of() : Set.copyOf(request.failDeliveryIds());
    List<DispatchRow> selected = jdbcTemplate.query("""
        select d.id, d.outbox_id, d.attempt_count, d.channel
        from exam_plan_delivery d
        where d.org_id=? and d.status in ('PENDING','FAILED')
          and (?::boolean or d.next_attempt_at<=now())
        order by d.next_attempt_at, d.created_at, d.id
        limit ? for update of d skip locked
        """, (rs, row) -> new DispatchRow(rs.getObject("id", UUID.class),
        rs.getObject("outbox_id", UUID.class), rs.getInt("attempt_count"), rs.getString("channel")),
        orgId, includeDeferred, limit);

    int delivered = 0;
    int failed = 0;
    Set<UUID> touchedOutboxes = new LinkedHashSet<>();
    List<UUID> deliveryIds = new java.util.ArrayList<>();
    for (DispatchRow row : selected) {
      touchedOutboxes.add(row.outboxId());
      deliveryIds.add(row.id());
      int attempt = row.attemptCount() + 1;
      if (!"IN_APP".equals(row.channel())) {
        // A local database receipt cannot prove delivery by an external provider.
        jdbcTemplate.update("""
            update exam_plan_delivery set status='FAILED', attempt_count=?, last_attempt_at=now(),
              next_attempt_at=now()+interval '1 hour', failure_reason='CHANNEL_NOT_CONFIGURED',
              provider_message_id=null, receipt='{}'::jsonb, delivered_at=null, read_at=null,
              updated_by=?, updated_at=now(), version=version+1 where org_id=? and id=?
            """, attempt, actorId, orgId, row.id());
        failed++;
      } else if (forcedFailures.contains(row.id())) {
        long retrySeconds = Math.min(3600L, 30L * (1L << Math.min(attempt - 1, 6)));
        jdbcTemplate.update("""
            update exam_plan_delivery set status='FAILED', attempt_count=?, last_attempt_at=now(),
              next_attempt_at=now()+(? * interval '1 second'), failure_reason='SIMULATED_PROVIDER_FAILURE',
              updated_by=?, updated_at=now(), version=version+1 where org_id=? and id=?
            """, attempt, retrySeconds, actorId, orgId, row.id());
        failed++;
      } else {
        String providerId = "local:" + row.id();
        ObjectNode receipt = objectMapper.createObjectNode().put("provider", "LOCAL_DISPATCH")
            .put("providerMessageId", providerId).put("deliveredAt", Instant.now().toString());
        jdbcTemplate.update("""
            update exam_plan_delivery set status='DELIVERED', attempt_count=?, last_attempt_at=now(),
              next_attempt_at=now(), failure_reason=null, provider_message_id=?, receipt=?::jsonb,
              delivered_at=coalesce(delivered_at, now()), updated_by=?, updated_at=now(),
              version=version+1 where org_id=? and id=?
            """, attempt, providerId, receipt.toString(), actorId, orgId, row.id());
        delivered++;
      }
    }
    touchedOutboxes.forEach(outboxId -> refreshOutbox(orgId, outboxId, actorId));
    List<DeliveryView> views = deliveryIds.stream().map(id -> require(orgId, id)).toList();
    return new DispatchResponse(selected.size(), delivered, failed, views);
  }

  @Transactional(readOnly = true)
  public List<DeliveryView> list(UUID revisionId, String status) {
    UUID orgId = TenantContext.getRequired();
    String normalized = status == null || status.isBlank() ? null : status.strip().toUpperCase();
    if (normalized != null && !Set.of("PENDING", "SENT", "DELIVERED", "READ", "FAILED", "CANCELLED")
        .contains(normalized)) {
      throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "DELIVERY_STATUS_INVALID",
          "Unknown delivery status");
    }
    StringBuilder sql = new StringBuilder(deliveryQuery()).append(" where d.org_id=?");
    List<Object> args = new java.util.ArrayList<>();
    args.add(orgId);
    if (revisionId != null) {
      sql.append(" and o.revision_id=?");
      args.add(revisionId);
    }
    if (normalized != null) {
      sql.append(" and d.status=?");
      args.add(normalized);
    }
    sql.append(" order by d.created_at desc, d.id");
    return jdbcTemplate.query(sql.toString(), this::deliveryView, args.toArray());
  }

  @Transactional(readOnly = true)
  public List<DeliveryView> self(UUID userId) {
    UUID orgId = TenantContext.getRequired();
    return jdbcTemplate.query(deliveryQuery() + """
        join teacher self_teacher on self_teacher.org_id=d.org_id and self_teacher.app_user_id=?
        where d.org_id=? and (
          (d.recipient_type='TEACHER' and d.recipient_id=self_teacher.teacher_no)
          or (d.recipient_type='USER' and d.recipient_id=?))
        order by d.created_at desc, d.id
        """, this::deliveryView, userId, orgId, userId.toString());
  }

  @Transactional
  public DeliveryView markRead(UUID userId, UUID deliveryId, ReadReceiptRequest request) {
    UUID orgId = TenantContext.getRequired();
    List<String> statuses = jdbcTemplate.query("""
        select d.status from exam_plan_delivery d
        join teacher t on t.org_id=d.org_id and t.app_user_id=?
        where d.org_id=? and d.id=? and (
          (d.recipient_type='TEACHER' and d.recipient_id=t.teacher_no)
          or (d.recipient_type='USER' and d.recipient_id=?))
        for update of d
        """, (rs, row) -> rs.getString(1), userId, orgId, deliveryId, userId.toString());
    if (statuses.isEmpty()) throw notFound();
    String current = statuses.getFirst();
    if (current.equals("READ")) return require(orgId, deliveryId);
    if (!Set.of("SENT", "DELIVERED").contains(current)) {
      throw new ApiException(HttpStatus.CONFLICT, "DELIVERY_STATE_INVALID",
          "Only a delivered notification can be marked read");
    }
    String receipt = request == null || request.receipt() == null
        ? "{}" : request.receipt().toString();
    jdbcTemplate.update("""
        update exam_plan_delivery set status='READ', read_at=now(), receipt=receipt || ?::jsonb,
          updated_by=?, updated_at=now(), version=version+1 where org_id=? and id=?
        """, receipt, userId, orgId, deliveryId);
    UUID outboxId = jdbcTemplate.queryForObject(
        "select outbox_id from exam_plan_delivery where org_id=? and id=?",
        UUID.class, orgId, deliveryId);
    refreshOutbox(orgId, outboxId, userId);
    return require(orgId, deliveryId);
  }

  private void refreshOutbox(UUID orgId, UUID outboxId, UUID actorId) {
    DeliverySummary summary = jdbcTemplate.query("""
        select count(*) total,
          count(*) filter (where status in ('DELIVERED','READ','CANCELLED')) settled,
          count(*) filter (where status='FAILED') failed,
          coalesce(max(attempt_count),0) attempts,
          min(next_attempt_at) filter (where status in ('PENDING','FAILED')) next_attempt
        from exam_plan_delivery where org_id=? and outbox_id=?
        """, rs -> {
      rs.next();
      return new DeliverySummary(rs.getInt("total"), rs.getInt("settled"), rs.getInt("failed"),
          rs.getInt("attempts"), instant(rs.getTimestamp("next_attempt")));
    }, orgId, outboxId);
    String status = summary.total() > 0 && summary.settled() == summary.total()
        ? "SENT" : summary.failed() > 0 ? "FAILED" : "PROCESSING";
    String error = summary.failed() > 0 ? "One or more recipient deliveries failed" : null;
    Timestamp availableAt = Timestamp.from(summary.nextAttempt() == null ? Instant.now() : summary.nextAttempt());
    jdbcTemplate.update("""
        update exam_plan_outbox set status=?, attempt_count=?, available_at=?, last_error=?,
          updated_by=?, updated_at=now(), version=version+1 where org_id=? and id=?
        """, status, summary.attempts(), availableAt, error, actorId, orgId, outboxId);
  }

  private DeliveryView require(UUID orgId, UUID deliveryId) {
    List<DeliveryView> rows = jdbcTemplate.query(deliveryQuery() + " where d.org_id=? and d.id=?",
        this::deliveryView, orgId, deliveryId);
    if (rows.isEmpty()) throw notFound();
    return rows.getFirst();
  }

  private String deliveryQuery() {
    return """
        select d.id, d.outbox_id, o.revision_id, o.event_type, d.recipient_type, d.recipient_id,
          d.channel, d.status, d.attempt_count, d.next_attempt_at, d.last_attempt_at,
          d.failure_reason, d.provider_message_id, d.receipt::text, d.delivered_at, d.read_at,
          d.version, d.created_at, d.updated_at
        from exam_plan_delivery d
        join exam_plan_outbox o on o.org_id=d.org_id and o.id=d.outbox_id
        """;
  }

  private DeliveryView deliveryView(ResultSet rs, int row) throws SQLException {
    return new DeliveryView(rs.getObject("id", UUID.class), rs.getObject("outbox_id", UUID.class),
        rs.getObject("revision_id", UUID.class), rs.getString("event_type"),
        rs.getString("recipient_type"), rs.getString("recipient_id"), rs.getString("channel"),
        rs.getString("status"), rs.getInt("attempt_count"),
        instant(rs.getTimestamp("next_attempt_at")), instant(rs.getTimestamp("last_attempt_at")),
        rs.getString("failure_reason"), rs.getString("provider_message_id"),
        json(rs.getString("receipt")), instant(rs.getTimestamp("delivered_at")),
        instant(rs.getTimestamp("read_at")), rs.getLong("version"),
        rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());
  }

  private JsonNode json(String value) {
    try {
      return objectMapper.readTree(value == null ? "{}" : value);
    } catch (Exception ignored) {
      return objectMapper.createObjectNode();
    }
  }

  private Instant instant(Timestamp value) {
    return value == null ? null : value.toInstant();
  }

  private ApiException notFound() {
    return new ApiException(HttpStatus.NOT_FOUND, "NOTIFICATION_DELIVERY_NOT_FOUND",
        "Notification delivery is unavailable");
  }

  private record DispatchRow(UUID id, UUID outboxId, int attemptCount, String channel) {
  }

  private record DeliverySummary(int total, int settled, int failed, int attempts, Instant nextAttempt) {
  }
}
