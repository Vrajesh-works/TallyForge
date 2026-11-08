package dev.tallyforge.ledger.support;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tallyforge.ledger.account.AccountDtos.AccountResponse;
import dev.tallyforge.ledger.account.AccountDtos.CreateAccountRequest;
import dev.tallyforge.ledger.account.AccountFlags;
import dev.tallyforge.ledger.transfer.TransferDtos.CreateTransferRequest;
import dev.tallyforge.ledger.transfer.TransferDtos.TransferResponse;
import java.util.UUID;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** Small typed client over the REST API for integration tests. */
public class LedgerApi {

  private final TestRestTemplate http;

  public LedgerApi(TestRestTemplate http) {
    this.http = http;
  }

  public static String unique(String prefix) {
    return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
  }

  public AccountResponse account(String prefix, AccountFlags flags) {
    var req = new CreateAccountRequest(unique(prefix), prefix + " account", "USD", flags);
    var res = http.postForEntity("/api/v1/accounts", req, AccountResponse.class);
    assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    return res.getBody();
  }

  /** A wallet that cannot go negative, funded from a fresh unconstrained settlement account. */
  public AccountResponse fundedWallet(String prefix, long amount) {
    AccountResponse wallet = account(prefix, AccountFlags.DEBITS_MUST_NOT_EXCEED_CREDITS);
    if (amount > 0) {
      AccountResponse bank = account("bank", AccountFlags.NONE);
      var res = transfer(bank.id(), wallet.id(), amount, false, null);
      assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }
    return get(wallet.id());
  }

  public AccountResponse get(UUID id) {
    return http.getForObject("/api/v1/accounts/" + id, AccountResponse.class);
  }

  public ResponseEntity<TransferResponse> transfer(
      UUID debit, UUID credit, long amount, boolean pending, String idempotencyKey) {
    HttpHeaders headers = new HttpHeaders();
    if (idempotencyKey != null) {
      headers.set("Idempotency-Key", idempotencyKey);
    }
    var body = new CreateTransferRequest(debit, credit, amount, pending, null);
    return http.exchange(
        "/api/v1/transfers",
        HttpMethod.POST,
        new HttpEntity<>(body, headers),
        TransferResponse.class);
  }

  public <T> ResponseEntity<T> post(String path, Object body, Class<T> type) {
    return http.exchange(path, HttpMethod.POST, new HttpEntity<>(body), type);
  }
}
