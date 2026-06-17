package com.jkxt.masterdata.repo;

import com.jkxt.masterdata.domain.Teacher;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TeacherRepository extends JpaRepository<Teacher, UUID> {

  List<Teacher> findByOrgIdOrderByTeacherNoAsc(UUID orgId);

  Optional<Teacher> findByOrgIdAndId(UUID orgId, UUID id);

  Optional<Teacher> findByOrgIdAndTeacherNo(UUID orgId, String teacherNo);
}
