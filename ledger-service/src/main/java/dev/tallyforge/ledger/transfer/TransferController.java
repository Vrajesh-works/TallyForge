package dev.tallyforge.ledger.transfer;

import dev.tallyforge.ledger.common.ApiException;
import dev.tallyforge.ledger.idempotency.IdempotencyService;
import dev.tallyforge.ledger.transfer.LedgerService.TransferCommand;
import dev.tallyforge.ledger.transfer.TransferDtos.CreateTransferRequest;
import dev.tallyforge.ledger.transfer.TransferDtos.PostTransferRequest;
import dev.tallyforge.ledger.transfer.TransferDtos.RefundRequest;
import dev.tallyforge.ledger.transfer.TransferDtos.TransferPage;
import dev.tallyforge.ledger.transfer.TransferDtos.TransferResponse;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Money-moving endpoints. Every POST accepts an optional Idempotency-Key header. */
@RestController
@RequestMapping("/api/v1/transfers")
public class TransferController {

  static final int MAX_PAGE_SIZE = 200;
  static final int MAX_EXPORT_ROWS = 10_000;
  private static final Sort NEWEST_FIRST =
      Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));

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
            true);
    return idempotency.execute(
        "transfer.create", key, req, HttpStatus.CREATED, () -> ledger.create(cmd));
  }

  @GetMapping
  @Transactional(readOnly = true)
  public TransferPage list(
      @RequestParam(required = false) TransferStatus status,
      @RequestParam(required = false) TransferKind kind,
      @RequestParam(required = false) UUID accountId,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          Instant from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          Instant to,
      @RequestParam(required = false) Long minAmount,
      @RequestParam(required = false) Long maxAmount,
      @RequestParam(required = false) Boolean held,
      @RequestParam(required = false) String q,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size) {
    if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
      throw ApiException.badRequest(
          "invalid_page", "page must be >= 0 and size between 1 and " + MAX_PAGE_SIZE);
    }
    var filter =
        new TransferFilter(status, kind, accountId, from, to, minAmount, maxAmount, held, q);
    Page<Transfer> result =
        transfers.findAll(filter.toSpecification(), PageRequest.of(page, size, NEWEST_FIRST));
    return new TransferPage(
        result.map(TransferResponse::from).getContent(), page, size, result.getTotalElements());
  }

  /**
   * The same filters as the listing, as a CSV download capped at {@value #MAX_EXPORT_ROWS} rows.
   */
  @GetMapping("/export")
  @Transactional(readOnly = true)
  public void export(
      @RequestParam(required = false) TransferStatus status,
      @RequestParam(required = false) TransferKind kind,
      @RequestParam(required = false) UUID accountId,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          Instant from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          Instant to,
      @RequestParam(required = false) Long minAmount,
      @RequestParam(required = false) Long maxAmount,
      @RequestParam(required = false) Boolean held,
      @RequestParam(required = false) String q,
      HttpServletResponse response)
      throws IOException {
    var filter =
        new TransferFilter(status, kind, accountId, from, to, minAmount, maxAmount, held, q);
    List<TransferResponse> rows =
        transfers
            .findAll(filter.toSpecification(), PageRequest.of(0, MAX_EXPORT_ROWS, NEWEST_FIRST))
            .map(TransferResponse::from)
            .getContent();
    response.setContentType("text/csv");
    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
    response.setHeader(
        HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"tallyforge-transfers.csv\"");
    TransferCsv.write(rows, response.getWriter());
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
