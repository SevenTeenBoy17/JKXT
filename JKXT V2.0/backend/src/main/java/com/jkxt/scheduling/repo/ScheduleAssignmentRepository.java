package com.jkxt.scheduling.repo;

import com.jkxt.scheduling.domain.ScheduleAssignment;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ScheduleAssignmentRepository extends JpaRepository<ScheduleAssignment, UUID> {

  List<ScheduleAssignment> findByOrgIdAndTeacherIdAndExamDate(UUID orgId, UUID teacherId, LocalDate examDate);

  List<ScheduleAssignment> findByOrgIdAndScheduleRunId(UUID orgId, UUID scheduleRunId);

  Optional<ScheduleAssignment> findByOrgIdAndId(UUID orgId, UUID id);
}
