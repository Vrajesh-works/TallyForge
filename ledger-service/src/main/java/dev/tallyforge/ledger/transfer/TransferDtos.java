package dev.tallyforge.ledger.transfer;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class TransferDtos {

  private TransferDtos() {}

  public record CreateTransferRequest(
      @NotNull UUID debitAccountId,
      @NotNull UUID creditAccountId,
      @Positive long amount,
      boolean pending,
      @Size(max = 200) String reference) {}

  public record PostTransferRequest(@Positive Long amount) {}

  public record RefundRequest(@Positive long amount, @Size(max = 200) String reference) {}

  public record TransferResponse(
      UUID id,
      UUID debitAccountId,
      UUID creditAccountId,
      long amount,
      long postedAmount,
      long refundedAmount,
      String currency,
      TransferKind kind,
      TransferStatus status,
      UUID refundOf,
      String holdReason,
      String reference,
      Instant createdAt,
      Instant updatedAt) {

    public static TransferResponse from(Transfer t) {
      return new TransferResponse(
          t.getId(),
          t.getDebitAccountId(),
          t.getCreditAccountId(),
          t.getAmount(),
          t.getPostedAmount(),
          t.getRefundedAmount(),
          t.getCurrency(),
          t.getKind(),
          t.getStatus(),
          t.getRefundOf(),
          t.getHoldReason(),
          t.getReference(),
          t.getCreatedAt(),
          t.getUpdatedAt());
    }
  }

  /** One page of a transfer listing, newest first. */
  public record TransferPage(List<TransferResponse> items, int page, int size, long total) {}
}
