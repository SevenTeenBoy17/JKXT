package com.jkxt.security.repo;

import com.jkxt.security.domain.Role;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RoleRepository extends JpaRepository<Role, UUID> {

  Optional<Role> findByOrgIdAndCode(UUID orgId, String code);
}
