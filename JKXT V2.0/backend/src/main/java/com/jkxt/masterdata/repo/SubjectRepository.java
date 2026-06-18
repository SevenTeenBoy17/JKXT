package com.jkxt.masterdata.repo;

import com.jkxt.masterdata.domain.Subject;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SubjectRepository extends JpaRepository<Subject, UUID> {

  List<Subject> findByOrgIdOrderByCodeAscNameAsc(UUID orgId);

  Optional<Subject> findByOrgIdAndId(UUID orgId, UUID id);

  Optional<Subject> findByOrgIdAndCode(UUID orgId, String code);

  Optional<Subject> findByOrgIdAndName(UUID orgId, String name);
}
