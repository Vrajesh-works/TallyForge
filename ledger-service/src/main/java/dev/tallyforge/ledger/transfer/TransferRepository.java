package dev.tallyforge.ledger.transfer;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface TransferRepository
    extends JpaRepository<Transfer, UUID>, JpaSpecificationExecutor<Transfer> {}
