package com.jkxt.notify.channel;

import com.jkxt.notify.config.NotifyProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Default SMS channel: records intent and logs it, without contacting an SMS gateway (no provider
 * credentials in scope). Replace with a real SMS-provider bean (same {@code channel()} = "sms") to
 * perform real delivery; the dispatcher and publish flow stay unchanged.
 */
@Component
public class LoggingSmsSender implements NotificationSender {

  private static final Logger log = LoggerFactory.getLogger(LoggingSmsSender.class);

  private final NotifyProperties properties;

  public LoggingSmsSender(NotifyProperties properties) {
    this.properties = properties;
  }

  @Override
  public String channel() {
    return "sms";
  }

  @Override
  public boolean isEnabled() {
    return properties.getSms().isEnabled();
  }

  @Override
  public DeliveryOutcome send(NotificationRecipient recipient, String subject, String body) {
    String to = recipient.phone();
    if (to == null || to.isBlank()) {
      return DeliveryOutcome.SKIPPED;
    }
    // Logging-only: a real SMS-gateway integration replaces this method's body.
    log.info("[notify:sms] to={} (logged-only; configure an SMS provider for real delivery)", to);
    return DeliveryOutcome.LOGGED;
  }
}
