package com.jkxt.security.repo;

import com.jkxt.security.domain.UserAccount;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserAccountRepository extends JpaRepository<UserAccount, UUID> {

  Optional<UserAccount> findByOrgIdAndUsername(UUID orgId, String username);

  Optional<UserAccount> findFirstByUsernameIgnoreCase(String username);
}
