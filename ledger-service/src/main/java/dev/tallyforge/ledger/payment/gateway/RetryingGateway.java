package dev.tallyforge.ledger.payment.gateway;

import dev.tallyforge.ledger.common.Backoff;
import java.time.Duration;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Retries transient gateway failures with exponential backoff and jitter. */
@Component
public class RetryingGateway {

  private static final Logger log = LoggerFactory.getLogger(RetryingGateway.class);

  private final int maxAttempts;
  private final Backoff backoff;

  public RetryingGateway(
      @Value("${tallyforge.gateway.max-attempts:3}") int maxAttempts,
      @Value("${tallyforge.gateway.retry-base-ms:20}") long baseMillis) {
    this.maxAttempts = maxAttempts;
    this.backoff = new Backoff(Duration.ofMillis(baseMillis), Duration.ofSeconds(2));
  }

  /** Runs {@code call}; throws the last {@link TransientGatewayException} when retries run out. */
  public GatewayResult call(String operation, Supplier<GatewayResult> call) {
    for (int attempt = 1; ; attempt++) {
      try {
        return call.get();
      } catch (TransientGatewayException e) {
        if (attempt >= maxAttempts) {
          log.warn("Gateway {} failed after {} attempts: {}", operation, attempt, e.getMessage());
          throw e;
        }
        try {
          Thread.sleep(backoff.withJitter(attempt));
        } catch (InterruptedException ie) {
          Thread.currentThread().interrupt();
          throw e;
        }
      }
    }
  }
}
