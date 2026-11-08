package dev.tallyforge.ledger.payment.gateway;

/**
 * Adapter boundary to a card processor (the plugin idea from Kill Bill). Every call carries a
 * request id that the processor must treat as an idempotency key, so a retried database transaction
 * can call the gateway again without authorizing or refunding twice.
 *
 * <p>Implementations throw {@link TransientGatewayException} for errors worth retrying (timeouts,
 * 5xx) and return a declined {@link GatewayResult} for final business declines.
 */
public interface PaymentGateway {

  String name();

  GatewayResult authorize(String requestId, String cardNumber, long amount, String currency);

  GatewayResult capture(String requestId, String gatewayReference, long amount);

  GatewayResult voidAuthorization(String requestId, String gatewayReference);

  GatewayResult refund(String requestId, String gatewayReference, long amount);
}
