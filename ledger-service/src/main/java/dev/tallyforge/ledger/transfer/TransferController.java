package dev.tallyforge.ledger.transfer;

import dev.tallyforge.ledger.common.ApiException;
import dev.tallyforge.ledger.idempotency.IdempotencyService;
import dev.tallyforge.ledger.transfer.LedgerService.TransferCommand;
import dev.tallyforge.ledger.transfer.TransferDtos.CreateTransferRequest;
import dev.tallyforge.ledger.transfer.TransferDtos.PostTransferRequest;
import dev.tallyforge.ledger.transfer.TransferDtos.RefundRequest;
import dev.tallyforge.ledger.transfer.TransferDtos.TransferResponse;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Money-moving endpoints. Every POST accepts an optional Idempotency-Key header. */
@RestController
@RequestMapping("/api/v1/transfers")
public class TransferController {

  private final LedgerService ledger;
  private final IdempotencyService idempotency;
  private final TransferRepository transfers;

  public TransferController(
      LedgerService ledger, IdempotencyService idempotency, TransferRepository transfers) {
    this.ledger = ledger;
    this.idempotency = idempotency;
    this.transfers = transfers;
  }

  @PostMapping
  public ResponseEntity<Object> create(
      @RequestHeader(name = IdempotencyService.HEADER, required = false) String key,
      @Valid @RequestBody CreateTransferRequest req) {
    var cmd =
        new TransferCommand(
            req.debitAccountId(),
            req.creditAccountId(),
            req.amount(),
            req.pending(),
            req.reference(),
            null);
    return idempotency.execute(
        "transfer.create", key, req, HttpStatus.CREATED, () -> ledger.create(cmd));
  }

  @GetMapping("/{id}")
  @Transactional(readOnly = true)
  public TransferResponse get(@PathVariable UUID id) {
    return TransferResponse.from(
        transfers.findById(id).orElseThrow(() -> ApiException.notFound("Transfer", id)));
  }

  @PostMapping("/{id}/post")
  public ResponseEntity<Object> post(
      @RequestHeader(name = IdempotencyService.HEADER, required = false) String key,
      @PathVariable UUID id,
      @Valid @RequestBody(required = false) PostTransferRequest req) {
    Long amount = req == null ? null : req.amount();
    return idempotency.execute(
        "transfer.post",
        key,
        List.of(id, String.valueOf(amount)),
        HttpStatus.OK,
        () -> ledger.post(id, amount));
  }

  @PostMapping("/{id}/void")
  public ResponseEntity<Object> voidTransfer(
      @RequestHeader(name = IdempotencyService.HEADER, required = false) String key,
      @PathVariable UUID id) {
    return idempotency.execute(
        "transfer.void", key, id, HttpStatus.OK, () -> ledger.voidTransfer(id));
  }

  @PostMapping("/{id}/refunds")
  public ResponseEntity<Object> refund(
      @RequestHeader(name = IdempotencyService.HEADER, required = false) String key,
      @PathVariable UUID id,
      @Valid @RequestBody RefundRequest req) {
    return idempotency.execute(
        "transfer.refund",
        key,
        List.of(id, req),
        HttpStatus.CREATED,
        () -> ledger.refund(id, req.amount(), req.reference()));
  }
}
