package com.jkxt.ai.repo;

import com.jkxt.ai.domain.AiCallLog;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AiCallLogRepository extends JpaRepository<AiCallLog, UUID> {

  List<AiCallLog> findByOrgIdAndExamTaskId(UUID orgId, UUID examTaskId);

  List<AiCallLog> findByOrgIdAndCreatedAtGreaterThanEqualOrderByCreatedAtDesc(UUID orgId, Instant since);

  List<AiCallLog> findTop5ByOrgIdOrderByCreatedAtDesc(UUID orgId);

  List<AiCallLog> findTop10ByOrgIdAndProviderOrderByCreatedAtDesc(UUID orgId, String provider);
}
