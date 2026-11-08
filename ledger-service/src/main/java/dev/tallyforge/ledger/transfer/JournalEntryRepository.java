package dev.tallyforge.ledger.transfer;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JournalEntryRepository extends JpaRepository<JournalEntry, Long> {

  List<JournalEntry> findByTransferIdOrderById(UUID transferId);
}
