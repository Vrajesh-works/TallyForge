package dev.tallyforge.ledger.payment.gateway;

public record GatewayResult(boolean approved, String reference, String declineCode) {

  public static GatewayResult approved(String reference) {
    return new GatewayResult(true, reference, null);
  }

  public static GatewayResult declined(String code) {
    return new GatewayResult(false, null, code);
  }
}
