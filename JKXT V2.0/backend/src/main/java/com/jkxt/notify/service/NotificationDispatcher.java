package com.jkxt.notify.service;

import com.jkxt.notify.channel.NotificationRecipient;
import com.jkxt.notify.channel.NotificationSender;
import com.jkxt.notify.channel.NotificationSender.DeliveryOutcome;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Fans a notification out across the external channels (email + SMS) and returns a per-channel
 * delivery summary suitable for persisting on the notification payload. In-app delivery is the DB
 * row itself and is summarised here for completeness.
 */
@Service
public class NotificationDispatcher {

  private final List<NotificationSender> senders;

  public NotificationDispatcher(List<NotificationSender> senders) {
    this.senders = senders;
  }

  public Map<String, Object> dispatch(List<NotificationRecipient> recipients, String subject, String body) {
    Map<String, Object> summary = new LinkedHashMap<>();
    summary.put("inApp", Map.of("enabled", true, "recipients", recipients.size(), "delivered", recipients.size()));

    for (NotificationSender sender : senders) {
      if (!sender.isEnabled()) {
        summary.put(sender.channel(), Map.of("enabled", false, "recipients", recipients.size()));
        continue;
      }
      int delivered = 0;
      int logged = 0;
      int skipped = 0;
      int failed = 0;
      for (NotificationRecipient recipient : recipients) {
        DeliveryOutcome outcome;
        try {
          outcome = sender.send(recipient, subject, body);
        } catch (RuntimeException ex) {
          outcome = DeliveryOutcome.FAILED;
        }
        switch (outcome == null ? DeliveryOutcome.FAILED : outcome) {
          case DELIVERED -> delivered++;
          case LOGGED -> logged++;
          case SKIPPED -> skipped++;
          case FAILED -> failed++;
        }
      }
      Map<String, Object> channel = new LinkedHashMap<>();
      channel.put("enabled", true);
      channel.put("recipients", recipients.size());
      channel.put("delivered", delivered);
      channel.put("logged", logged);
      channel.put("skipped", skipped);
      channel.put("failed", failed);
      summary.put(sender.channel(), channel);
    }
    return summary;
  }
}
