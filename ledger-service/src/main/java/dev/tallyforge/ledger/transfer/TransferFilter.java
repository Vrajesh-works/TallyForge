package dev.tallyforge.ledger.transfer;

import jakarta.persistence.criteria.Predicate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.data.jpa.domain.Specification;

/**
 * Optional filters for listing transfers. Every field may be null; a null field does not narrow the
 * result. {@code accountId} matches either side of the transfer, and {@code q} is a
 * case-insensitive substring match on the client reference.
 */
public record TransferFilter(
    TransferStatus status,
    TransferKind kind,
    UUID accountId,
    Instant from,
    Instant to,
    Long minAmount,
    Long maxAmount,
    Boolean held,
    String q) {

  public Specification<Transfer> toSpecification() {
    return (root, query, cb) -> {
      List<Predicate> where = new ArrayList<>();
      if (status != null) {
        where.add(cb.equal(root.get("status"), status));
      }
      if (kind != null) {
        where.add(cb.equal(root.get("kind"), kind));
      }
      if (accountId != null) {
        where.add(
            cb.or(
                cb.equal(root.get("debitAccountId"), accountId),
                cb.equal(root.get("creditAccountId"), accountId)));
      }
      if (from != null) {
        where.add(cb.greaterThanOrEqualTo(root.get("createdAt"), from));
      }
      if (to != null) {
        where.add(cb.lessThan(root.get("createdAt"), to));
      }
      if (minAmount != null) {
        where.add(cb.greaterThanOrEqualTo(root.get("amount"), minAmount));
      }
      if (maxAmount != null) {
        where.add(cb.lessThanOrEqualTo(root.get("amount"), maxAmount));
      }
      if (held != null) {
        where.add(held ? cb.isNotNull(root.get("holdReason")) : cb.isNull(root.get("holdReason")));
      }
      if (q != null && !q.isBlank()) {
        String pattern = "%" + escapeLike(q.trim().toLowerCase(Locale.ROOT)) + "%";
        where.add(cb.like(cb.lower(root.get("reference")), pattern, '\\'));
      }
      return cb.and(where.toArray(Predicate[]::new));
    };
  }

  private static String escapeLike(String s) {
    return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
  }
}
