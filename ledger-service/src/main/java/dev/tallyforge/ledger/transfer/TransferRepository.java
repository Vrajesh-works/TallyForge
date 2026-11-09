package dev.tallyforge.ledger.transfer;

import java.time.Instant;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface TransferRepository
    extends JpaRepository<Transfer, UUID>, JpaSpecificationExecutor<Transfer> {

  long countByDebitAccountIdAndCreatedAtAfter(UUID debitAccountId, Instant after);
}
