package com.jkxt.notify.repo;

import com.jkxt.notify.domain.Notification;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationRepository extends JpaRepository<Notification, UUID> {

  List<Notification> findByOrgIdAndExamTaskId(UUID orgId, UUID examTaskId);

  List<Notification> findTop50ByOrgIdOrderByCreatedAtDesc(UUID orgId);
}
