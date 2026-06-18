package com.jkxt.notify.api;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public final class NotificationDtos {

  private NotificationDtos() {
  }

  public record NotificationView(
      UUID id,
      String type,
      String title,
      String body,
      String status,
      String targetUrl,
      Map<String, Object> payload,
      Instant publishedAt,
      Instant createdAt,
      boolean read
  ) {
  }

  public record UnreadCount(long count) {
  }

  public record MarkReadResult(int updated, long unread) {
  }
}
