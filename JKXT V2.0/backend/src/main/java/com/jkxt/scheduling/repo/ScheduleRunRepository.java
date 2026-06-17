package com.jkxt.scheduling.repo;

import com.jkxt.scheduling.domain.ScheduleRun;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ScheduleRunRepository extends JpaRepository<ScheduleRun, UUID> {

  List<ScheduleRun> findByOrgIdAndExamTaskId(UUID orgId, UUID examTaskId);

  List<ScheduleRun> findByOrgIdAndExamTaskIdOrderByCreatedAtDesc(UUID orgId, UUID examTaskId);

  Optional<ScheduleRun> findByOrgIdAndId(UUID orgId, UUID id);
}
