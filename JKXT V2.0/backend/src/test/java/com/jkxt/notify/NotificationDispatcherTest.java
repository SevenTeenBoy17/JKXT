package com.jkxt.notify;

import static org.assertj.core.api.Assertions.assertThat;

import com.jkxt.notify.channel.LoggingEmailSender;
import com.jkxt.notify.channel.LoggingSmsSender;
import com.jkxt.notify.channel.NotificationRecipient;
import com.jkxt.notify.config.NotifyProperties;
import com.jkxt.notify.service.NotificationDispatcher;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class NotificationDispatcherTest {

  @Test
  @SuppressWarnings("unchecked")
  void dispatchTalliesLoggedAndSkippedPerChannel() {
    NotifyProperties props = new NotifyProperties();
    NotificationDispatcher dispatcher = new NotificationDispatcher(
        List.of(new LoggingEmailSender(props), new LoggingSmsSender(props)), formalPolicy());

    List<NotificationRecipient> recipients = List.of(
        new NotificationRecipient(UUID.randomUUID(), "有联系方式", "a@example.com", "13800000000"),
        new NotificationRecipient(UUID.randomUUID(), "无联系方式", null, null));

    Map<String, Object> summary = dispatcher.dispatch(recipients, "主题", "正文");

    Map<String, Object> email = (Map<String, Object>) summary.get("email");
    assertThat(email)
        .containsEntry("enabled", true)
        .containsEntry("recipients", 2)
        .containsEntry("logged", 1)   // the one with an email
        .containsEntry("skipped", 1)  // the one without
        .containsEntry("delivered", 0)
        .containsEntry("failed", 0);

    Map<String, Object> sms = (Map<String, Object>) summary.get("sms");
    assertThat(sms).containsEntry("logged", 1).containsEntry("skipped", 1);

    Map<String, Object> inApp = (Map<String, Object>) summary.get("inApp");
    // 站内为机构级广播：实投 1 条，涉及 2 位教师（经导出张贴下发），不伪装成 2 个已送达收件箱。
    assertThat(inApp)
        .containsEntry("enabled", true)
        .containsEntry("status", "delivered")
        .containsEntry("scope", "org_broadcast")
        .containsEntry("deliveredNotifications", 1)
        .containsEntry("addressedTeachers", 2);
  }

  @Test
  @SuppressWarnings("unchecked")
  void disabledChannelIsNotAttempted() {
    NotifyProperties props = new NotifyProperties();
    props.getEmail().setEnabled(false);
    NotificationDispatcher dispatcher = new NotificationDispatcher(
        List.of(new LoggingEmailSender(props), new LoggingSmsSender(props)), formalPolicy());

    Map<String, Object> summary = dispatcher.dispatch(
        List.of(new NotificationRecipient(UUID.randomUUID(), "n", "a@example.com", "138")), "s", "b");

    Map<String, Object> email = (Map<String, Object>) summary.get("email");
    assertThat(email).containsEntry("enabled", false);
    assertThat(email).doesNotContainKey("logged");
  }
  private static com.jkxt.demo.DemoSideEffectPolicy formalPolicy() {
    var deploy=new com.jkxt.common.config.DeployProperties();deploy.setMode("private");
    return new com.jkxt.demo.DemoSideEffectPolicy(deploy);
  }
}
