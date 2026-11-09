package dev.tallyforge.ledger.transfer;

import dev.tallyforge.ledger.transfer.TransferDtos.TransferResponse;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.util.Objects;

/**
 * Writes transfers as RFC 4180 CSV. Text cells that a spreadsheet would treat as a formula are
 * prefixed with a single quote so an exported reference cannot run as a formula when opened.
 */
public final class TransferCsv {

  static final String HEADER =
      "id,created_at,kind,status,debit_account_id,credit_account_id,amount,posted_amount,"
          + "refunded_amount,currency,hold_reason,refund_of,reference";

  private TransferCsv() {}

  public static void write(Iterable<TransferResponse> rows, Writer out) {
    try {
      out.write(HEADER);
      out.write("\r\n");
      for (TransferResponse t : rows) {
        out.write(row(t));
        out.write("\r\n");
      }
      out.flush();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  static String row(TransferResponse t) {
    return String.join(
        ",",
        str(t.id()),
        str(t.createdAt()),
        str(t.kind()),
        str(t.status()),
        str(t.debitAccountId()),
        str(t.creditAccountId()),
        Long.toString(t.amount()),
        Long.toString(t.postedAmount()),
        Long.toString(t.refundedAmount()),
        text(t.currency()),
        text(t.holdReason()),
        str(t.refundOf()),
        text(t.reference()));
  }

  private static String str(Object value) {
    return Objects.toString(value, "");
  }

  static String text(String value) {
    if (value == null || value.isEmpty()) {
      return "";
    }
    String v = value;
    if ("=+-@\t\r".indexOf(v.charAt(0)) >= 0) {
      v = "'" + v;
    }
    if (v.contains(",") || v.contains("\"") || v.contains("\n") || v.contains("\r")) {
      v = "\"" + v.replace("\"", "\"\"") + "\"";
    }
    return v;
  }
}
