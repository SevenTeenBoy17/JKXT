package com.jkxt.tasks.repo;

import com.jkxt.tasks.domain.Exam;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ExamRepository extends JpaRepository<Exam, UUID> {

  List<Exam> findByOrgIdAndExamTaskId(UUID orgId, UUID examTaskId);

  Optional<Exam> findByOrgIdAndId(UUID orgId, UUID id);
}
