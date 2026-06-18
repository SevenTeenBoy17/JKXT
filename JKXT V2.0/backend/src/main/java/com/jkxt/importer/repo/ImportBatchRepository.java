package com.jkxt.importer.repo;

import com.jkxt.importer.domain.ImportBatch;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ImportBatchRepository extends JpaRepository<ImportBatch, UUID> {

  List<ImportBatch> findByOrgIdAndExamTaskId(UUID orgId, UUID examTaskId);

  List<ImportBatch> findByOrgIdAndExamTaskIdOrderByCreatedAtDesc(UUID orgId, UUID examTaskId);
}
