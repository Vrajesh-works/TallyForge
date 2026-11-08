package dev.tallyforge.ledger.payment;

import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentRepository extends JpaRepository<Payment, UUID> {

  List<Payment> findAllByOrderByCreatedAtDesc(Pageable page);

  List<Payment> findByStatusOrderByCreatedAtDesc(PaymentStatus status, Pageable page);
}
