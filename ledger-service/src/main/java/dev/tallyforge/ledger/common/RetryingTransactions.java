package dev.tallyforge.ledger.common;

import java.time.Duration;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Runs a unit of work in a fresh transaction and retries it when the database reports a concurrency
 * conflict (optimistic lock version mismatch, serialization failure, lock timeout). The whole
 * transaction is replayed, so the work must not have side effects outside the database unless those
 * side effects are themselves idempotent.
 */
@Component
public class RetryingTransactions {

  private static final Logger log = LoggerFactory.getLogger(RetryingTransactions.class);

  private final TransactionTemplate tx;
  private final int maxAttempts;
  private final Backoff backoff;

  public RetryingTransactions(
      TransactionTemplate tx,
      @Value("${tallyforge.ledger.max-attempts:30}") int maxAttempts,
      @Value("${tallyforge.ledger.retry-base-ms:2}") long baseMillis) {
    this.tx = tx;
    this.maxAttempts = maxAttempts;
    this.backoff = new Backoff(Duration.ofMillis(baseMillis), Duration.ofMillis(100));
  }

  public <T> T execute(Supplier<T> work) {
    for (int attempt = 1; ; attempt++) {
      try {
        return tx.execute(status -> work.get());
      } catch (ConcurrencyFailureException e) {
        if (attempt >= maxAttempts) {
          log.warn("Giving up after {} attempts on concurrency conflict", attempt);
          throw new ApiException(
              HttpStatus.CONFLICT,
              "concurrent_modification",
              "Too much contention on the same accounts, retry the request");
        }
        sleep(backoff.withJitter(attempt));
      }
    }
  }

  private static void sleep(Duration d) {
    try {
      Thread.sleep(d);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while backing off", e);
    }
  }
}
