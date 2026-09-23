package com.jkxt.examops.api;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public final class NotificationDeliveryDtos {

  private NotificationDeliveryDtos() {
  }

  public record DispatchRequest(
      @Min(1) @Max(500) Integer limit,
      Set<UUID> failDeliveryIds,
      Boolean includeDeferred
  ) {
  }

  public record ReadReceiptRequest(JsonNode receipt) {
  }

  public record DeliveryView(
      UUID id,
      UUID outboxId,
      UUID revisionId,
      String eventType,
      String recipientType,
      String recipientId,
      String channel,
      String status,
      int attemptCount,
      Instant nextAttemptAt,
      Instant lastAttemptAt,
      String failureReason,
      String providerMessageId,
      JsonNode receipt,
      Instant deliveredAt,
      Instant readAt,
      long version,
      Instant createdAt,
      Instant updatedAt
  ) {
  }

  public record DispatchResponse(
      int selected,
      int delivered,
      int failed,
      List<DeliveryView> deliveries
  ) {
  }
}
