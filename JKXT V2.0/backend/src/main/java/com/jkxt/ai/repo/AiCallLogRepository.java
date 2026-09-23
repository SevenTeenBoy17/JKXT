package com.jkxt.ai.repo;

import com.jkxt.ai.domain.AiCallLog;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AiCallLogRepository extends JpaRepository<AiCallLog, UUID> {

  @Query(value = "SELECT EXISTS(SELECT 1 FROM exam_task WHERE org_id = :orgId AND id = :taskId)", nativeQuery = true)
  boolean taskExistsInOrganization(@Param("orgId") UUID orgId, @Param("taskId") UUID taskId);

  List<AiCallLog> findByOrgIdAndExamTaskId(UUID orgId, UUID examTaskId);

  List<AiCallLog> findByOrgIdAndCreatedAtGreaterThanEqualOrderByCreatedAtDesc(UUID orgId, Instant since);

  List<AiCallLog> findTop5ByOrgIdOrderByCreatedAtDesc(UUID orgId);

  List<AiCallLog> findTop10ByOrgIdAndProviderOrderByCreatedAtDesc(UUID orgId, String provider);
}
