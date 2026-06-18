package com.jkxt.notify.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Toggles for the notification delivery channels (R3: in-app + email + SMS).
 * In-app is always on. Email/SMS default on but ship with logging-only senders — set
 * a real {@code NotificationSender} bean (SMTP / SMS provider) to perform real delivery.
 */
@Component
@ConfigurationProperties(prefix = "jkxt.notify")
public class NotifyProperties {

  private Channel email = new Channel();
  private Channel sms = new Channel();

  public Channel getEmail() {
    return email;
  }

  public void setEmail(Channel email) {
    this.email = email;
  }

  public Channel getSms() {
    return sms;
  }

  public void setSms(Channel sms) {
    this.sms = sms;
  }

  public static class Channel {
    /** Whether this channel is attempted on publish. Default true (logging-only without a provider). */
    private boolean enabled = true;

    public boolean isEnabled() {
      return enabled;
    }

    public void setEnabled(boolean enabled) {
      this.enabled = enabled;
    }
  }
}
