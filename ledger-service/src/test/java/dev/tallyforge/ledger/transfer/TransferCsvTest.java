package dev.tallyforge.ledger.transfer;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tallyforge.ledger.transfer.TransferDtos.TransferResponse;
import java.io.StringWriter;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TransferCsvTest {

  @Test
  void plainTextIsLeftAlone() {
    assertThat(TransferCsv.text("order-42")).isEqualTo("order-42");
    assertThat(TransferCsv.text(null)).isEmpty();
  }

  @Test
  void commasAndQuotesAreQuoted() {
    assertThat(TransferCsv.text("a,b")).isEqualTo("\"a,b\"");
    assertThat(TransferCsv.text("say \"hi\"")).isEqualTo("\"say \"\"hi\"\"\"");
    assertThat(TransferCsv.text("two\nlines")).isEqualTo("\"two\nlines\"");
  }

  @Test
  void formulaLikeCellsAreNeutralised() {
    assertThat(TransferCsv.text("=HYPERLINK(\"x\")")).isEqualTo("\"'=HYPERLINK(\"\"x\"\")\"");
    assertThat(TransferCsv.text("+1")).isEqualTo("'+1");
    assertThat(TransferCsv.text("@SUM")).isEqualTo("'@SUM");
  }

  @Test
  void writesHeaderAndOneLinePerTransfer() {
    UUID id = UUID.fromString("00000000-0000-0000-0000-000000000001");
    UUID debit = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    UUID credit = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    Instant at = Instant.parse("2026-11-08T12:00:00Z");
    var t =
        new TransferResponse(
            id,
            debit,
            credit,
            500,
            500,
            100,
            "USD",
            TransferKind.TRANSFER,
            TransferStatus.POSTED,
            null,
            null,
            "rent, november",
            at,
            at);

    StringWriter out = new StringWriter();
    TransferCsv.write(List.of(t), out);

    assertThat(out.toString().split("\r\n"))
        .containsExactly(
            TransferCsv.HEADER,
            id
                + ",2026-11-08T12:00:00Z,TRANSFER,POSTED,"
                + debit
                + ","
                + credit
                + ",500,500,100,USD,,,\"rent, november\"");
  }
}
