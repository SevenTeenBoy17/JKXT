package com.jkxt.masterdata.repo;

import com.jkxt.masterdata.domain.SchoolClass;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SchoolClassRepository extends JpaRepository<SchoolClass, UUID> {

  List<SchoolClass> findByOrgIdOrderByGradeAscCodeAsc(UUID orgId);

  Optional<SchoolClass> findByOrgIdAndId(UUID orgId, UUID id);

  Optional<SchoolClass> findByOrgIdAndCode(UUID orgId, String code);
}
