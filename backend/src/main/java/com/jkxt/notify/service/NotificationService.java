package com.jkxt.notify.service;

import com.jkxt.notify.api.NotificationDtos.NotificationView;
import com.jkxt.notify.domain.Notification;
import com.jkxt.notify.repo.NotificationRepository;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * In-app notification inbox: lists the org's notification feed with a per-user read flag and supports
 * mark-read / mark-all-read. Read state lives in {@code notification_read} (per user), so the same
 * org-level notification can be read independently by each operator.
 */
@Service
public class NotificationService {

  private final NotificationRepository notificationRepository;
  private final JdbcTemplate jdbcTemplate;

  public NotificationService(NotificationRepository notificationRepository, JdbcTemplate jdbcTemplate) {
    this.notificationRepository = notificationRepository;
    this.jdbcTemplate = jdbcTemplate;
  }

  /**
   * 系统事件入收件箱（P18 批次 5.3）。
   *
   * <p>此前全库只有「排班发布」一个生产者（ScheduleEditService.publish），导入完成、排考失败等
   * 关键事件都进不了收件箱——顶栏铃铛因此长期近乎空转。本方法给各业务模块提供统一入口；
   * org 级广播、按用户记已读的语义与既有通知完全一致。
   */
  @Transactional
  public void publishSystemNotice(UUID orgId, UUID examTaskId, String type, String title, String body,
      String targetUrl, java.util.Map<String, Object> payload) {
    Notification notification = new Notification();
    notification.setOrgId(orgId);
    notification.setExamTaskId(examTaskId);
    notification.setType(type);
    notification.setTitle(title);
    notification.setBody(body);
    notification.setStatus("sent");
    notification.setTargetUrl(targetUrl);
    notification.setPublishedAt(java.time.Instant.now());
    notification.setPayload(payload == null ? java.util.Map.of() : payload);
    notificationRepository.save(notification);
  }

  @Transactional(readOnly = true)
  public List<NotificationView> list(UUID orgId, UUID userId) {
    Set<UUID> read = readIds(orgId, userId);
    return notificationRepository.findTop50ByOrgIdOrderByCreatedAtDesc(orgId).stream()
        .map(n -> toView(n, read.contains(n.getId())))
        .toList();
  }

  @Transactional(readOnly = true)
  public long unreadCount(UUID orgId, UUID userId) {
    Long count = jdbcTemplate.queryForObject("""
        select count(*) from notification n
        where n.org_id = ?
          and not exists (select 1 from notification_read r where r.notification_id = n.id and r.user_id = ?)
        """, Long.class, orgId, userId);
    return count == null ? 0L : count;
  }

  /** Returns the number of read rows actually inserted (0 if already read, unknown, or cross-org). */
  @Transactional
  public int markRead(UUID orgId, UUID userId, UUID notificationId) {
    // The select-from-notification guard ensures a user can only mark a notification in their own org.
    return jdbcTemplate.update("""
        insert into notification_read (notification_id, user_id, org_id)
        select n.id, ?, n.org_id from notification n where n.id = ? and n.org_id = ?
        on conflict (notification_id, user_id) do nothing
        """, userId, notificationId, orgId);
  }

  @Transactional
  public int markAllRead(UUID orgId, UUID userId) {
    return jdbcTemplate.update("""
        insert into notification_read (notification_id, user_id, org_id)
        select n.id, ?, n.org_id from notification n
        where n.org_id = ?
          and not exists (select 1 from notification_read r where r.notification_id = n.id and r.user_id = ?)
        on conflict (notification_id, user_id) do nothing
        """, userId, orgId, userId);
  }

  private Set<UUID> readIds(UUID orgId, UUID userId) {
    return new HashSet<>(jdbcTemplate.query(
        "select notification_id from notification_read where org_id = ? and user_id = ?",
        (rs, rowNum) -> rs.getObject("notification_id", UUID.class), orgId, userId));
  }

  private NotificationView toView(Notification n, boolean read) {
    return new NotificationView(
        n.getId(),
        n.getType(),
        n.getTitle(),
        n.getBody(),
        n.getStatus(),
        n.getTargetUrl(),
        n.getPayload(),
        n.getPublishedAt(),
        n.getCreatedAt(),
        read
    );
  }
}
