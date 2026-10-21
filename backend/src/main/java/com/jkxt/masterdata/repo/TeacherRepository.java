package com.jkxt.masterdata.repo;

import com.jkxt.masterdata.domain.Teacher;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TeacherRepository extends JpaRepository<Teacher, UUID> {

  List<Teacher> findByOrgIdOrderByTeacherNoAsc(UUID orgId);

  long countByOrgId(UUID orgId);

  /** Teachers who declared this homeroom class code but whose binding is not yet resolved (class
   * imported after the teacher) — used to backfill homeroom_class_id order-independently. */
  List<Teacher> findByOrgIdAndHomeroomClassCodeAndHomeroomClassIdIsNull(UUID orgId, String homeroomClassCode);

  List<Teacher> findByOrgIdAndIdIn(UUID orgId, Collection<UUID> ids);

  Optional<Teacher> findByOrgIdAndId(UUID orgId, UUID id);

  Optional<Teacher> findByOrgIdAndTeacherNo(UUID orgId, String teacherNo);

  Optional<Teacher> findByOrgIdAndEmail(UUID orgId, String email);
}
