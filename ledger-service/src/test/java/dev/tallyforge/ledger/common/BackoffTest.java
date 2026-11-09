package dev.tallyforge.ledger.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

class BackoffTest {

  private final Backoff backoff = new Backoff(Duration.ofSeconds(1), Duration.ofSeconds(60));

  @Test
  void ceilingDoublesAndIsCapped() {
    assertThat(backoff.ceiling(1)).isEqualTo(Duration.ofSeconds(1));
    assertThat(backoff.ceiling(2)).isEqualTo(Duration.ofSeconds(2));
    assertThat(backoff.ceiling(4)).isEqualTo(Duration.ofSeconds(8));
    assertThat(backoff.ceiling(7)).isEqualTo(Duration.ofSeconds(60));
    assertThat(backoff.ceiling(200)).isEqualTo(Duration.ofSeconds(60));
  }

  @RepeatedTest(20)
  void jitterStaysBetweenHalfCeilingAndCeiling() {
    Duration d = backoff.withJitter(3);
    assertThat(d).isBetween(Duration.ofSeconds(2), Duration.ofSeconds(4));
  }

  @Test
  void rejectsNonPositiveAttempt() {
    assertThatThrownBy(() -> backoff.ceiling(0)).isInstanceOf(IllegalArgumentException.class);
  }
}
