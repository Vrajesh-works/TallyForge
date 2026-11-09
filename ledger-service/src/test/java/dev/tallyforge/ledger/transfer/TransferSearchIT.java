package dev.tallyforge.ledger.transfer;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tallyforge.ledger.IntegrationTest;
import dev.tallyforge.ledger.account.AccountFlags;
import dev.tallyforge.ledger.common.ApiError;
import dev.tallyforge.ledger.support.LedgerApi;
import dev.tallyforge.ledger.transfer.TransferDtos.RefundRequest;
import dev.tallyforge.ledger.transfer.TransferDtos.TransferPage;
import dev.tallyforge.ledger.transfer.TransferDtos.TransferResponse;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

class TransferSearchIT extends IntegrationTest {

  LedgerApi api;
  UUID bank;
  UUID wallet;
  TransferResponse posted;

  @BeforeEach
  void setUp() {
    api = new LedgerApi(http);
    bank = api.account("bank", AccountFlags.NONE).id();
    wallet = api.account("wallet", AccountFlags.NONE).id();
    posted = api.transfer(bank, wallet, 1_000, false, null).getBody();
    api.transfer(bank, wallet, 2_000, true, null);
    api.transfer(wallet, bank, 300, false, null);
    api.post(
        "/api/v1/transfers/" + posted.id() + "/refunds",
        new RefundRequest(250, "partial"),
        TransferResponse.class);
  }

  private TransferPage search(String query) {
    return http.getForObject("/api/v1/transfers?accountId=" + wallet + query, TransferPage.class);
  }

  @Test
  void listsAnAccountsTransfersNewestFirst() {
    TransferPage page = search("");

    assertThat(page.total()).isEqualTo(4);
    assertThat(page.items()).hasSize(4);
    assertThat(page.items().get(0).kind()).isEqualTo(TransferKind.REFUND);
    assertThat(page.items()).isSortedAccordingTo((a, b) -> b.createdAt().compareTo(a.createdAt()));
  }

  @Test
  void filtersCombine() {
    assertThat(search("&status=PENDING").items())
        .singleElement()
        .extracting(TransferResponse::amount)
        .isEqualTo(2_000L);
    assertThat(search("&kind=REFUND").items())
        .singleElement()
        .extracting(TransferResponse::refundOf)
        .isEqualTo(posted.id());
    assertThat(search("&minAmount=500&maxAmount=1500").items())
        .extracting(TransferResponse::amount)
        .containsExactly(1_000L);
    assertThat(search("&q=PART").items()).hasSize(1);
    assertThat(search("&kind=TRANSFER&status=POSTED").total()).isEqualTo(2);
  }

  @Test
  void pagesThroughResults() {
    TransferPage first = search("&size=3&page=0");
    TransferPage second = search("&size=3&page=1");

    assertThat(first.items()).hasSize(3);
    assertThat(second.items()).hasSize(1);
    assertThat(first.total()).isEqualTo(4);
  }

  @Test
  void rejectsOversizedPages() {
    var res = http.getForEntity("/api/v1/transfers?size=1000", ApiError.class);
    assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
  }

  @Test
  void exportsFilteredTransfersAsCsv() {
    var res =
        http.getForEntity(
            "/api/v1/transfers/export?accountId=" + wallet + "&kind=TRANSFER", String.class);

    assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(res.getHeaders().getContentType().isCompatibleWith(MediaType.valueOf("text/csv")))
        .isTrue();
    assertThat(res.getHeaders().getContentDisposition().getFilename())
        .isEqualTo("tallyforge-transfers.csv");
    String[] lines = res.getBody().split("\r\n");
    assertThat(lines).hasSize(4);
    assertThat(lines[0]).isEqualTo(TransferCsv.HEADER);
  }
}
