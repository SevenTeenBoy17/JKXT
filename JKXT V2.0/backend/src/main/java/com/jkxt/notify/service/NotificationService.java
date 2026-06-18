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

  @Transactional
  public long markRead(UUID orgId, UUID userId, UUID notificationId) {
    // The select-from-notification guard ensures a user can only mark a notification in their own org.
    jdbcTemplate.update("""
        insert into notification_read (notification_id, user_id, org_id)
        select n.id, ?, n.org_id from notification n where n.id = ? and n.org_id = ?
        on conflict (notification_id, user_id) do nothing
        """, userId, notificationId, orgId);
    return unreadCount(orgId, userId);
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
