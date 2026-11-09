package dev.tallyforge.ledger.payment;

import dev.tallyforge.ledger.idempotency.IdempotencyService;
import dev.tallyforge.ledger.idempotency.RequestHasher;
import dev.tallyforge.ledger.payment.PaymentDtos.CaptureRequest;
import dev.tallyforge.ledger.payment.PaymentDtos.CreatePaymentRequest;
import dev.tallyforge.ledger.payment.PaymentDtos.PaymentRefundRequest;
import dev.tallyforge.ledger.payment.PaymentDtos.PaymentResponse;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
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

@RestController
@RequestMapping("/api/v1/payments")
public class PaymentController {

  private final PaymentService service;
  private final PaymentRepository payments;
  private final IdempotencyService idempotency;

  public PaymentController(
      PaymentService service, PaymentRepository payments, IdempotencyService idempotency) {
    this.service = service;
    this.payments = payments;
    this.idempotency = idempotency;
  }

  @PostMapping
  public ResponseEntity<Object> create(
      @RequestHeader(name = IdempotencyService.HEADER, required = false) String key,
      @Valid @RequestBody CreatePaymentRequest req) {
    // Generated outside the retried transaction: it doubles as the gateway idempotency key.
    UUID paymentId = UUID.randomUUID();
    Object fingerprint =
        List.of(
            req.merchantAccountId(),
            req.amount(),
            req.currency(),
            RequestHasher.sha256Hex(req.cardNumber()),
            req.capture(),
            String.valueOf(req.reference()));
    return idempotency.execute(
        "payment.create",
        key,
        fingerprint,
        HttpStatus.CREATED,
        () -> service.create(paymentId, req));
  }

  @GetMapping
  @Transactional(readOnly = true)
  public List<PaymentResponse> list(
      @RequestParam(required = false) PaymentStatus status,
      @RequestParam(defaultValue = "100") int limit) {
    var page = PageRequest.of(0, Math.min(Math.max(limit, 1), 500));
    var rows =
        status == null
            ? payments.findAllByOrderByCreatedAtDesc(page)
            : payments.findByStatusOrderByCreatedAtDesc(status, page);
    return rows.stream().map(service::view).toList();
  }

  @GetMapping("/{id}")
  public PaymentResponse get(@PathVariable UUID id) {
    return service.get(id);
  }

  @PostMapping("/{id}/capture")
  public ResponseEntity<Object> capture(
      @RequestHeader(name = IdempotencyService.HEADER, required = false) String key,
      @PathVariable UUID id,
      @Valid @RequestBody(required = false) CaptureRequest req) {
    Long amount = req == null ? null : req.amount();
    return idempotency.execute(
        "payment.capture",
        key,
        List.of(id, String.valueOf(amount)),
        HttpStatus.OK,
        () -> service.capture(id, amount));
  }

  @PostMapping("/{id}/approve")
  public ResponseEntity<Object> approve(
      @RequestHeader(name = IdempotencyService.HEADER, required = false) String key,
      @PathVariable UUID id) {
    return idempotency.execute(
        "payment.approve", key, id, HttpStatus.OK, () -> service.approve(id));
  }

  @PostMapping("/{id}/void")
  public ResponseEntity<Object> voidPayment(
      @RequestHeader(name = IdempotencyService.HEADER, required = false) String key,
      @PathVariable UUID id) {
    return idempotency.execute(
        "payment.void", key, id, HttpStatus.OK, () -> service.voidPayment(id));
  }

  @PostMapping("/{id}/refunds")
  public ResponseEntity<Object> refund(
      @RequestHeader(name = IdempotencyService.HEADER, required = false) String key,
      @PathVariable UUID id,
      @Valid @RequestBody PaymentRefundRequest req) {
    String operationId = key != null ? key : UUID.randomUUID().toString();
    return idempotency.execute(
        "payment.refund",
        key,
        List.of(id, req.amount()),
        HttpStatus.CREATED,
        () -> service.refund(id, req.amount(), operationId));
  }
}
