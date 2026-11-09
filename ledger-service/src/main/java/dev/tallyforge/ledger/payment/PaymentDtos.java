package dev.tallyforge.ledger.payment;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class PaymentDtos {

  private PaymentDtos() {}

  public record CreatePaymentRequest(
      @NotNull UUID merchantAccountId,
      @Positive long amount,
      @NotBlank @Pattern(regexp = "[A-Z]{3}") String currency,
      @NotBlank @Pattern(regexp = "\\d{12,19}") String cardNumber,
      boolean capture,
      @Size(max = 200) String reference) {

    /** Never let the card number reach logs or the idempotency fingerprint in clear text. */
    @Override
    public String toString() {
      return "CreatePaymentRequest[merchant="
          + merchantAccountId
          + ", amount="
          + amount
          + ", currency="
          + currency
          + "]";
    }
  }

  public record CaptureRequest(@Positive Long amount) {}

  public record PaymentRefundRequest(@Positive long amount) {}

  public record PaymentEventResponse(
      PaymentStatus from, PaymentStatus to, String detail, Instant at) {}

  public record PaymentResponse(
      UUID id,
      UUID merchantAccountId,
      long amount,
      long capturedAmount,
      long refundedAmount,
      String currency,
      PaymentStatus status,
      String cardLast4,
      String gateway,
      String gatewayReference,
      UUID holdTransferId,
      String failureReason,
      String reviewReason,
      String reference,
      Instant createdAt,
      Instant updatedAt,
      List<PaymentEventResponse> events) {

    public static PaymentResponse from(Payment p, List<PaymentEvent> events) {
      return new PaymentResponse(
          p.getId(),
          p.getMerchantAccountId(),
          p.getAmount(),
          p.getCapturedAmount(),
          p.getRefundedAmount(),
          p.getCurrency(),
          p.getStatus(),
          p.getCardLast4(),
          p.getGateway(),
          p.getGatewayReference(),
          p.getHoldTransferId(),
          p.getFailureReason(),
          p.getReviewReason(),
          p.getReference(),
          p.getCreatedAt(),
          p.getUpdatedAt(),
          events.stream()
              .map(e ->
                  new PaymentEventResponse(
                      e.getFromStatus(), e.getToStatus(), e.getDetail(), e.getCreatedAt()))
              .toList());
    }
  }
}
