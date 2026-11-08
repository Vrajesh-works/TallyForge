package dev.tallyforge.ledger.payment.gateway;

/** A retryable processor failure such as a timeout or a 5xx response. */
public class TransientGatewayException extends RuntimeException {

  public TransientGatewayException(String message) {
    super(message);
  }
}
