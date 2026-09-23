package com.jkxt.masterdata.repo;

import com.jkxt.masterdata.domain.Classroom;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ClassroomRepository extends JpaRepository<Classroom, UUID> {

  List<Classroom> findByOrgIdOrderByCodeAsc(UUID orgId);

  long countByOrgId(UUID orgId);

  Optional<Classroom> findByOrgIdAndId(UUID orgId, UUID id);

  Optional<Classroom> findByOrgIdAndCode(UUID orgId, String code);
}
