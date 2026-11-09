package dev.tallyforge.ledger.account;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AccountRepository extends JpaRepository<Account, UUID> {

  Optional<Account> findByCode(String code);

  boolean existsByCode(String code);
}
