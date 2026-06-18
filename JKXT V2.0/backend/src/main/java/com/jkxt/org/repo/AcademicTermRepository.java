package com.jkxt.org.repo;

import com.jkxt.org.domain.AcademicTerm;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AcademicTermRepository extends JpaRepository<AcademicTerm, UUID> {

  List<AcademicTerm> findByOrgIdAndCurrentTrue(UUID orgId);
}
