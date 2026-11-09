package dev.tallyforge.ledger.payment.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class MockCardProcessorTest {

  private final MockCardProcessor processor = new MockCardProcessor();

  @Test
  void approvesValidCardAndIsIdempotentPerRequestId() {
    GatewayResult first = processor.authorize("req-1", "4242424242424242", 100, "USD");
    GatewayResult again = processor.authorize("req-1", "4242424242424242", 100, "USD");
    assertThat(first.approved()).isTrue();
    assertThat(again).isEqualTo(first);
    assertThat(processor.authorize("req-2", "4242424242424242", 100, "USD").reference())
        .isNotEqualTo(first.reference());
  }

  @Test
  void declinesTestCardsAndBadNumbers() {
    assertThat(processor.authorize("a", MockCardProcessor.DECLINE, 1, "USD").declineCode())
        .isEqualTo("card_declined");
    assertThat(
            processor.authorize("b", MockCardProcessor.INSUFFICIENT_FUNDS, 1, "USD").declineCode())
        .isEqualTo("insufficient_funds");
    assertThat(processor.authorize("c", "4242424242424241", 1, "USD").declineCode())
        .isEqualTo("invalid_number");
  }

  @Test
  void flakyCardFailsOnceThenSucceedsThroughRetry() {
    RetryingGateway retry = new RetryingGateway(3, 1);
    AtomicInteger calls = new AtomicInteger();
    GatewayResult result =
        retry.call(
            "authorize",
            () -> {
              calls.incrementAndGet();
              return processor.authorize("flaky", MockCardProcessor.FLAKY_ONCE, 1, "USD");
            });
    assertThat(result.approved()).isTrue();
    assertThat(calls).hasValue(2);
  }

  @Test
  void unavailableProcessorExhaustsRetries() {
    RetryingGateway retry = new RetryingGateway(3, 1);
    AtomicInteger calls = new AtomicInteger();
    assertThatThrownBy(() ->
            retry.call(
                "authorize",
                () -> {
                  calls.incrementAndGet();
                  return processor.authorize(
                      "down", MockCardProcessor.ALWAYS_UNAVAILABLE, 1, "USD");
                }))
        .isInstanceOf(TransientGatewayException.class);
    assertThat(calls).hasValue(3);
  }

  @Test
  void luhn() {
    assertThat(MockCardProcessor.luhnValid("4242424242424242")).isTrue();
    assertThat(MockCardProcessor.luhnValid("4000000000000002")).isTrue();
    assertThat(MockCardProcessor.luhnValid("1234")).isFalse();
    assertThat(MockCardProcessor.luhnValid(null)).isFalse();
  }
}
