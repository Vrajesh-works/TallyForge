package dev.tallyforge.ledger.payment.gateway;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * In-process stand-in for a card processor, driven by test card numbers:
 *
 * <ul>
 *   <li>{@code 4000000000000002}: declined, {@code card_declined}
 *   <li>{@code 4000000000009995}: declined, {@code insufficient_funds}
 *   <li>{@code 4000000000000119}: processor always unavailable (transient)
 *   <li>{@code 4000000000000259}: transient failure on the first attempt, then approved
 *   <li>any other number passing the Luhn check: approved
 * </ul>
 *
 * Results are remembered per request id, mirroring how real processors honour idempotency keys.
 */
@Component
public class MockCardProcessor implements PaymentGateway {

  public static final String DECLINE = "4000000000000002";
  public static final String INSUFFICIENT_FUNDS = "4000000000009995";
  public static final String ALWAYS_UNAVAILABLE = "4000000000000119";
  public static final String FLAKY_ONCE = "4000000000000259";

  private final Map<String, GatewayResult> results = new ConcurrentHashMap<>();
  private final Map<String, Boolean> flakyFailed = new ConcurrentHashMap<>();

  @Override
  public String name() {
    return "mock";
  }

  @Override
  public GatewayResult authorize(
      String requestId, String cardNumber, long amount, String currency) {
    GatewayResult cached = results.get(requestId);
    if (cached != null) {
      return cached;
    }
    if (ALWAYS_UNAVAILABLE.equals(cardNumber)) {
      throw new TransientGatewayException("processor timeout");
    }
    if (FLAKY_ONCE.equals(cardNumber) && flakyFailed.putIfAbsent(requestId, true) == null) {
      throw new TransientGatewayException("processor returned 503");
    }
    GatewayResult result;
    if (!luhnValid(cardNumber)) {
      result = GatewayResult.declined("invalid_number");
    } else if (DECLINE.equals(cardNumber)) {
      result = GatewayResult.declined("card_declined");
    } else if (INSUFFICIENT_FUNDS.equals(cardNumber)) {
      result = GatewayResult.declined("insufficient_funds");
    } else {
      result = GatewayResult.approved("mock_auth_" + UUID.randomUUID());
    }
    GatewayResult existing = results.putIfAbsent(requestId, result);
    return existing != null ? existing : result;
  }

  @Override
  public GatewayResult capture(String requestId, String gatewayReference, long amount) {
    return results.computeIfAbsent(
        requestId, k -> GatewayResult.approved("mock_cap_" + UUID.randomUUID()));
  }

  @Override
  public GatewayResult voidAuthorization(String requestId, String gatewayReference) {
    return results.computeIfAbsent(
        requestId, k -> GatewayResult.approved("mock_void_" + UUID.randomUUID()));
  }

  @Override
  public GatewayResult refund(String requestId, String gatewayReference, long amount) {
    return results.computeIfAbsent(
        requestId, k -> GatewayResult.approved("mock_ref_" + UUID.randomUUID()));
  }

  static boolean luhnValid(String number) {
    if (number == null || !number.matches("\\d{12,19}")) {
      return false;
    }
    int sum = 0;
    boolean dbl = false;
    for (int i = number.length() - 1; i >= 0; i--) {
      int d = number.charAt(i) - '0';
      if (dbl) {
        d *= 2;
        if (d > 9) {
          d -= 9;
        }
      }
      sum += d;
      dbl = !dbl;
    }
    return sum % 10 == 0;
  }
}
