package dev.tallyforge.ledger.transfer;

import dev.tallyforge.ledger.common.ApiException;
import dev.tallyforge.ledger.common.RetryingTransactions;
import dev.tallyforge.ledger.transfer.LedgerService.TransferCommand;
import dev.tallyforge.ledger.transfer.TransferDtos.CreateTransferRequest;
import dev.tallyforge.ledger.transfer.TransferDtos.PostTransferRequest;
import dev.tallyforge.ledger.transfer.TransferDtos.RefundRequest;
import dev.tallyforge.ledger.transfer.TransferDtos.TransferResponse;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/transfers")
public class TransferController {

  private final LedgerService ledger;
  private final RetryingTransactions tx;
  private final TransferRepository transfers;

  public TransferController(
      LedgerService ledger, RetryingTransactions tx, TransferRepository transfers) {
    this.ledger = ledger;
    this.tx = tx;
    this.transfers = transfers;
  }

  @PostMapping
  public ResponseEntity<TransferResponse> create(@Valid @RequestBody CreateTransferRequest req) {
    var cmd =
        new TransferCommand(
            req.debitAccountId(),
            req.creditAccountId(),
            req.amount(),
            req.pending(),
            req.reference(),
            null);
    return ResponseEntity.status(HttpStatus.CREATED).body(tx.execute(() -> ledger.create(cmd)));
  }

  @GetMapping("/{id}")
  @Transactional(readOnly = true)
  public TransferResponse get(@PathVariable UUID id) {
    return TransferResponse.from(
        transfers.findById(id).orElseThrow(() -> ApiException.notFound("Transfer", id)));
  }

  @PostMapping("/{id}/post")
  public TransferResponse post(
      @PathVariable UUID id, @Valid @RequestBody(required = false) PostTransferRequest req) {
    Long amount = req == null ? null : req.amount();
    return tx.execute(() -> ledger.post(id, amount));
  }

  @PostMapping("/{id}/void")
  public TransferResponse voidTransfer(@PathVariable UUID id) {
    return tx.execute(() -> ledger.voidTransfer(id));
  }

  @PostMapping("/{id}/refunds")
  public ResponseEntity<TransferResponse> refund(
      @PathVariable UUID id, @Valid @RequestBody RefundRequest req) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(tx.execute(() -> ledger.refund(id, req.amount(), req.reference())));
  }
}
