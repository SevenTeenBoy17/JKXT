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

  /** 容错回退：当导入时把"班级名称"误填进"班级编号/班级"列时，按名称匹配第一条（名称在机构内通常唯一）。 */
  Optional<SchoolClass> findFirstByOrgIdAndName(UUID orgId, String name);
}
