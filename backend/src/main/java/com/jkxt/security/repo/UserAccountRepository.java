package com.jkxt.security.repo;

import com.jkxt.security.domain.UserAccount;
import java.util.Optional;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import jakarta.persistence.LockModeType;

public interface UserAccountRepository extends JpaRepository<UserAccount, UUID> {

  Optional<UserAccount> findByOrgIdAndUsername(UUID orgId, String username);

  Optional<UserAccount> findFirstByUsernameIgnoreCase(String username);

  List<UserAccount> findAllByUsernameIgnoreCase(String username);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select u from UserAccount u where u.id = :id")
  Optional<UserAccount> findForUpdateById(UUID id);
}
