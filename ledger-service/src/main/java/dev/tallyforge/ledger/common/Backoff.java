package dev.tallyforge.ledger.common;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

/** Exponential backoff with a cap and optional "full jitter" (AWS architecture blog style). */
public record Backoff(Duration base, Duration max) {

  /** Upper bound of the delay before retry number {@code attempt} (1-based). */
  public Duration ceiling(int attempt) {
    if (attempt < 1) {
      throw new IllegalArgumentException("attempt must be >= 1");
    }
    int shift = Math.min(attempt - 1, 30);
    long millis = base.toMillis() << shift;
    if (millis <= 0 || millis > max.toMillis()) {
      millis = max.toMillis();
    }
    return Duration.ofMillis(millis);
  }

  /** A random delay between half the ceiling and the ceiling, to spread out retry storms. */
  public Duration withJitter(int attempt) {
    long ceiling = ceiling(attempt).toMillis();
    long floor = ceiling / 2;
    return Duration.ofMillis(floor + ThreadLocalRandom.current().nextLong(ceiling - floor + 1));
  }
}
