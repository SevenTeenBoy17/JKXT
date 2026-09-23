package com.jkxt.org.repo;

import com.jkxt.org.domain.AcademicTerm;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AcademicTermRepository extends JpaRepository<AcademicTerm, UUID> {

  List<AcademicTerm> findByOrgIdAndCurrentTrue(UUID orgId);

  /** 本 org 的全部学期（学期管理列表用；排序在 service 层做，便于统一口径）。 */
  List<AcademicTerm> findByOrgId(UUID orgId);
}
