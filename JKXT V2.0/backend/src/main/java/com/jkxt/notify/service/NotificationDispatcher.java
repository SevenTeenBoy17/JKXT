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
    // 站内通知 = 一条机构级广播 DB 行，面向"已登录"的教务/管理员。监考教师在本系统没有登录账号，
    // 其个人监考安排经『导出张贴/下发』触达（M3 决策：站内通知 + 导出张贴）。因此如实区分：
    //   deliveredNotifications=1（实投的站内广播条数）、addressedTeachers=N（被涉及的监考教师数，
    // 经导出张贴下发），绝不把"被涉及教师数"伪装成"N 个已送达的教师收件箱"。
    Map<String, Object> inApp = new LinkedHashMap<>();
    inApp.put("enabled", true);
    inApp.put("status", "delivered");
    inApp.put("scope", "org_broadcast");
    inApp.put("deliveredNotifications", 1);
    inApp.put("addressedTeachers", recipients.size());
    inApp.put("note", "站内为机构级广播（面向已登录教务/管理员）；监考教师经『导出张贴』下发，不依赖教师登录");
    summary.put("inApp", inApp);

    for (NotificationSender sender : senders) {
      if (!sender.isEnabled()) {
        // 未配置外发网关：如实标注未启用，不可显示为"已发送"。
        summary.put(sender.channel(), Map.of("enabled", false, "status", "disabled", "recipients", recipients.size()));
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
      // 诚实口径：仅 log 桩(无真实网关)记为 logged_only(仅记录·未真正发送)，绝不报"已送达"。
      String status = failed > 0 ? "failed"
          : delivered > 0 ? "delivered"
          : logged > 0 ? "logged_only"
          : "skipped";
      Map<String, Object> channel = new LinkedHashMap<>();
      channel.put("enabled", true);
      channel.put("status", status);
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
