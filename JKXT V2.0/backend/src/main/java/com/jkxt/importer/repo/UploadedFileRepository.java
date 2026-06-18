package com.jkxt.importer.repo;

import com.jkxt.importer.domain.UploadedFile;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UploadedFileRepository extends JpaRepository<UploadedFile, UUID> {

  List<UploadedFile> findByOrgIdAndExamTaskId(UUID orgId, UUID examTaskId);

  Optional<UploadedFile> findTopByOrgIdAndExamTaskIdAndKindOrderByCreatedAtDesc(UUID orgId, UUID examTaskId, String kind);
}
