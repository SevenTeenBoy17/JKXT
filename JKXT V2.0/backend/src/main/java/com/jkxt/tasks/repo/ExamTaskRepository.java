package com.jkxt.tasks.repo;

import com.jkxt.tasks.domain.ExamTask;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ExamTaskRepository extends JpaRepository<ExamTask, UUID> {

  Optional<ExamTask> findByOrgIdAndTaskNo(UUID orgId, String taskNo);

  Optional<ExamTask> findByOrgIdAndId(UUID orgId, UUID id);

  List<ExamTask> findByOrgIdOrderByCreatedAtDesc(UUID orgId);
}
