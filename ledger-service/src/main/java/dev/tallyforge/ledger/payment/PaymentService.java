package dev.tallyforge.ledger.payment;

import dev.tallyforge.ledger.account.Account;
import dev.tallyforge.ledger.account.AccountRepository;
import dev.tallyforge.ledger.common.ApiException;
import dev.tallyforge.ledger.outbox.OutboxService;
import dev.tallyforge.ledger.payment.PaymentDtos.CreatePaymentRequest;
import dev.tallyforge.ledger.payment.PaymentDtos.PaymentResponse;
import dev.tallyforge.ledger.payment.gateway.GatewayResult;
import dev.tallyforge.ledger.payment.gateway.PaymentGateway;
import dev.tallyforge.ledger.payment.gateway.RetryingGateway;
import dev.tallyforge.ledger.payment.gateway.TransientGatewayException;
import dev.tallyforge.ledger.transfer.LedgerService;
import dev.tallyforge.ledger.transfer.LedgerService.TransferCommand;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Drives a payment through its state machine. Each step calls the gateway first (with a stable
 * request id, so a replayed transaction is safe) and then records the matching ledger movement:
 *
 * <ul>
 *   <li>authorize: pending transfer from card clearing to the merchant (a hold)
 *   <li>capture: post the hold, fully or partially
 *   <li>void: void the hold
 *   <li>refund: reverse part of the posted transfer
 * </ul>
 */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class PaymentService {

  private final PaymentRepository payments;
  private final PaymentEventRepository events;
  private final AccountRepository accounts;
  private final LedgerService ledger;
  private final PaymentGateway gateway;
  private final RetryingGateway retry;
  private final OutboxService outbox;

  public PaymentService(
      PaymentRepository payments,
      PaymentEventRepository events,
      AccountRepository accounts,
      LedgerService ledger,
      PaymentGateway gateway,
      RetryingGateway retry,
      OutboxService outbox) {
    this.payments = payments;
    this.events = events;
    this.accounts = accounts;
    this.ledger = ledger;
    this.gateway = gateway;
    this.retry = retry;
    this.outbox = outbox;
  }

  public PaymentResponse create(UUID paymentId, CreatePaymentRequest req) {
    Account merchant =
        accounts
            .findById(req.merchantAccountId())
            .orElseThrow(() -> ApiException.notFound("Account", req.merchantAccountId()));
    if (!merchant.getCurrency().equals(req.currency())) {
      throw ApiException.unprocessable(
          "currency_mismatch", "merchant account currency is " + merchant.getCurrency());
    }
    Account clearing = clearingAccount(req.currency());
    String last4 = req.cardNumber().substring(req.cardNumber().length() - 4);
    Payment payment =
        new Payment(
            paymentId,
            merchant.getId(),
            req.amount(),
            req.currency(),
            last4,
            gateway.name(),
            req.reference());
    payments.save(payment);
    record(payment, null, PaymentStatus.CREATED, null);

    GatewayResult auth;
    try {
      auth =
          retry.call(
              "authorize",
              () ->
                  gateway.authorize(
                      paymentId.toString(), req.cardNumber(), req.amount(), req.currency()));
    } catch (TransientGatewayException e) {
      return fail(payment, "gateway_unavailable");
    }
    if (!auth.approved()) {
      return fail(payment, auth.declineCode());
    }

    var hold =
        ledger.create(
            new TransferCommand(
                clearing.getId(),
                merchant.getId(),
                req.amount(),
                true,
                "payment:" + paymentId,
                null));
    payment.authorized(auth.reference(), hold.id());
    transition(payment, PaymentStatus.AUTHORIZED, auth.reference());
    if (req.capture()) {
      return capture(paymentId, null);
    }
    return publish(payment, "payment.authorized");
  }

  public PaymentResponse capture(UUID paymentId, Long amount) {
    Payment payment = load(paymentId);
    payment.requireStatus(PaymentStatus.AUTHORIZED);
    long toCapture = amount == null ? payment.getAmount() : amount;
    if (toCapture <= 0 || toCapture > payment.getAmount()) {
      throw ApiException.unprocessable(
          "invalid_capture_amount", "capture amount must be between 1 and the authorized amount");
    }
    callGateway(
        "capture",
        () -> gateway.capture(paymentId + ":capture", payment.getGatewayReference(), toCapture));
    ledger.post(payment.getHoldTransferId(), toCapture);
    payment.captured(toCapture);
    transition(payment, PaymentStatus.CAPTURED, "captured " + toCapture);
    return publish(payment, "payment.captured");
  }

  public PaymentResponse voidPayment(UUID paymentId) {
    Payment payment = load(paymentId);
    payment.requireStatus(PaymentStatus.AUTHORIZED, PaymentStatus.REQUIRES_REVIEW);
    callGateway(
        "void",
        () -> gateway.voidAuthorization(paymentId + ":void", payment.getGatewayReference()));
    ledger.voidTransfer(payment.getHoldTransferId());
    transition(payment, PaymentStatus.VOIDED, null);
    return publish(payment, "payment.voided");
  }

  public PaymentResponse refund(UUID paymentId, long amount, String operationId) {
    Payment payment = load(paymentId);
    payment.requireStatus(PaymentStatus.CAPTURED, PaymentStatus.PARTIALLY_REFUNDED);
    long refundable = payment.getCapturedAmount() - payment.getRefundedAmount();
    if (amount <= 0 || amount > refundable) {
      throw ApiException.unprocessable(
          "refund_exceeds_captured", "refund must be between 1 and " + refundable);
    }
    callGateway(
        "refund",
        () ->
            gateway.refund(
                paymentId + ":refund:" + operationId, payment.getGatewayReference(), amount));
    ledger.refund(payment.getHoldTransferId(), amount, "payment-refund:" + paymentId);
    payment.refunded(amount);
    PaymentStatus next =
        payment.getRefundedAmount() == payment.getCapturedAmount()
            ? PaymentStatus.REFUNDED
            : PaymentStatus.PARTIALLY_REFUNDED;
    transition(payment, next, "refunded " + amount);
    return publish(payment, "payment.refunded");
  }

  @Transactional(readOnly = true, propagation = Propagation.REQUIRED)
  public PaymentResponse get(UUID paymentId) {
    return view(load(paymentId));
  }

  PaymentResponse view(Payment payment) {
    return PaymentResponse.from(payment, events.findByPaymentIdOrderById(payment.getId()));
  }

  private void callGateway(String operation, java.util.function.Supplier<GatewayResult> call) {
    GatewayResult result;
    try {
      result = retry.call(operation, call);
    } catch (TransientGatewayException e) {
      throw new ApiException(
          HttpStatus.BAD_GATEWAY, "gateway_unavailable", "payment processor unavailable");
    }
    if (!result.approved()) {
      throw ApiException.unprocessable("gateway_declined", result.declineCode());
    }
  }

  private PaymentResponse fail(Payment payment, String reason) {
    payment.failed(reason);
    transition(payment, PaymentStatus.FAILED, reason);
    return publish(payment, "payment.failed");
  }

  void transition(Payment payment, PaymentStatus next, String detail) {
    PaymentStatus previous = payment.transitionTo(next);
    record(payment, previous, next, detail);
  }

  private void record(Payment payment, PaymentStatus from, PaymentStatus to, String detail) {
    events.save(new PaymentEvent(payment.getId(), from, to, detail));
  }

  PaymentResponse publish(Payment payment, String eventType) {
    PaymentResponse response = view(payment);
    outbox.append("payment", payment.getId(), eventType, response);
    return response;
  }

  private Account clearingAccount(String currency) {
    return accounts
        .findByCode("sys.card_clearing." + currency)
        .orElseThrow(() ->
            ApiException.unprocessable(
                "unsupported_currency", "no card clearing account for " + currency));
  }

  private Payment load(UUID id) {
    return payments.findById(id).orElseThrow(() -> ApiException.notFound("Payment", id));
  }
}
