package com.jkxt.notify.channel;

/**
 * One delivery channel for a notification (email / SMS). In-app delivery is handled directly by the
 * dispatcher (DB row), so this abstraction covers the external channels. The shipped implementations
 * are logging-only (they record intent without a real provider); wiring a real SMTP / SMS-provider
 * bean for the same channel name replaces them without touching the dispatcher.
 */
public interface NotificationSender {

  /** Channel id, e.g. "email" or "sms". */
  String channel();

  /** Whether this channel is currently enabled (config-driven). */
  boolean isEnabled();

  /**
   * Attempt delivery to one recipient. Implementations must not throw — return a FAILED outcome on
   * error so one bad address does not abort the fan-out.
   */
  DeliveryOutcome send(NotificationRecipient recipient, String subject, String body);

  /** Outcome of a single delivery attempt. */
  enum DeliveryOutcome {
    /** Really handed off to an external provider. */
    DELIVERED,
    /** Recorded/logged only — no external provider configured for this channel. */
    LOGGED,
    /** No usable address for this recipient (e.g. missing email/phone) — nothing sent. */
    SKIPPED,
    /** An error occurred attempting delivery. */
    FAILED
  }
}
