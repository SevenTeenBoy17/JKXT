package com.jkxt.notify.channel;

import com.jkxt.notify.config.NotifyProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Default email channel: records intent and logs it, without contacting an SMTP server (no provider
 * credentials in scope). Replace with a JavaMailSender-backed bean (same {@code channel()} = "email")
 * to perform real delivery; the dispatcher and publish flow stay unchanged.
 */
@Component
public class LoggingEmailSender implements NotificationSender {

  private static final Logger log = LoggerFactory.getLogger(LoggingEmailSender.class);

  private final NotifyProperties properties;

  public LoggingEmailSender(NotifyProperties properties) {
    this.properties = properties;
  }

  @Override
  public String channel() {
    return "email";
  }

  @Override
  public boolean isEnabled() {
    return properties.getEmail().isEnabled();
  }

  @Override
  public DeliveryOutcome send(NotificationRecipient recipient, String subject, String body) {
    String to = recipient.email();
    if (to == null || to.isBlank()) {
      return DeliveryOutcome.SKIPPED;
    }
    // Logging-only: a real SMTP integration replaces this method's body.
    log.info("[notify:email] to={} subject={} (logged-only; configure an SMTP provider for real delivery)", to, subject);
    return DeliveryOutcome.LOGGED;
  }
}
