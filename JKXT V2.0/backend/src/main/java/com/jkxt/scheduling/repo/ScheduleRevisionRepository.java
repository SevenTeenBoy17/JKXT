package com.jkxt.scheduling.repo;

import com.jkxt.scheduling.domain.ScheduleRevision;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ScheduleRevisionRepository extends JpaRepository<ScheduleRevision, UUID> {

  Optional<ScheduleRevision> findByOrgIdAndScheduleRunIdAndRevisionNo(UUID orgId, UUID scheduleRunId, int revisionNo);

  List<ScheduleRevision> findByOrgIdAndScheduleRunIdOrderByRevisionNoAsc(UUID orgId, UUID scheduleRunId);

  void deleteByOrgIdAndScheduleRunIdAndRevisionNoGreaterThan(UUID orgId, UUID scheduleRunId, int revisionNo);
}
