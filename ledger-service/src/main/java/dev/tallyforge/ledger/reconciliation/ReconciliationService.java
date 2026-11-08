package dev.tallyforge.ledger.reconciliation;

import dev.tallyforge.ledger.common.ApiException;
import dev.tallyforge.ledger.outbox.OutboxService;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Independent check that the ledger still adds up. It does not trust the balance columns: it
 * recomputes them from the immutable journal and compares, inside one REPEATABLE READ snapshot so
 * in-flight transfers cannot produce false alarms.
 *
 * <ol>
 *   <li>{@code account_balance}: each balance column equals the sum of its journal lines
 *   <li>{@code ledger_balanced}: per currency and phase, total debits equal total credits
 *   <li>{@code transfer_entries}: each transfer's posted and pending lines match its amounts
 *   <li>{@code payment_vs_ledger}: captured and refunded totals match the payment's transfer
 * </ol>
 */
@Service
public class ReconciliationService {

  private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);

  private final JdbcTemplate jdbc;
  private final TransactionTemplate snapshotTx;
  private final OutboxService outbox;

  public ReconciliationService(
      JdbcTemplate jdbc, PlatformTransactionManager txManager, OutboxService outbox) {
    this.jdbc = jdbc;
    this.outbox = outbox;
    this.snapshotTx = new TransactionTemplate(txManager);
    this.snapshotTx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
  }

  public record Mismatch(String check, String subject, long expected, long actual) {}

  public record RunResponse(
      UUID id,
      String trigger,
      String status,
      int accountsChecked,
      int transfersChecked,
      int paymentsChecked,
      int mismatchCount,
      Instant startedAt,
      Instant finishedAt,
      List<Mismatch> mismatches) {}

  @Scheduled(cron = "${tallyforge.reconciliation.cron:0 0 2 * * *}", zone = "UTC")
  public void scheduledRun() {
    RunResponse run = run("SCHEDULED");
    log.info("Daily reconciliation {}: {} mismatches", run.status(), run.mismatchCount());
  }

  public RunResponse run(String trigger) {
    return snapshotTx.execute(status -> doRun(trigger));
  }

  private RunResponse doRun(String trigger) {
    Instant started = Instant.now();
    List<Mismatch> mismatches = new ArrayList<>();

    List<Map<String, Object>> accounts =
        jdbc.queryForList(
            "SELECT a.code, a.debits_pending, a.debits_posted, a.credits_pending, a.credits_posted,"
                + " COALESCE(SUM(j.amount) FILTER (WHERE j.side = 'DEBIT' AND j.phase = 'PENDING'), 0) AS j_dp,"
                + " COALESCE(SUM(j.amount) FILTER (WHERE j.side = 'DEBIT' AND j.phase = 'POSTED'), 0) AS j_dpo,"
                + " COALESCE(SUM(j.amount) FILTER (WHERE j.side = 'CREDIT' AND j.phase = 'PENDING'), 0) AS j_cp,"
                + " COALESCE(SUM(j.amount) FILTER (WHERE j.side = 'CREDIT' AND j.phase = 'POSTED'), 0) AS j_cpo"
                + " FROM accounts a LEFT JOIN journal_entries j ON j.account_id = a.id"
                + " GROUP BY a.id ORDER BY a.code");
    for (Map<String, Object> a : accounts) {
      String code = (String) a.get("code");
      compare(mismatches, "account_balance", code + ":debits_pending", a, "j_dp", "debits_pending");
      compare(mismatches, "account_balance", code + ":debits_posted", a, "j_dpo", "debits_posted");
      compare(
          mismatches, "account_balance", code + ":credits_pending", a, "j_cp", "credits_pending");
      compare(
          mismatches, "account_balance", code + ":credits_posted", a, "j_cpo", "credits_posted");
    }

    jdbc.query(
        "SELECT currency, phase,"
            + " COALESCE(SUM(amount) FILTER (WHERE side = 'DEBIT'), 0) AS debits,"
            + " COALESCE(SUM(amount) FILTER (WHERE side = 'CREDIT'), 0) AS credits"
            + " FROM journal_entries GROUP BY currency, phase ORDER BY currency, phase",
        rs -> {
          long debits = rs.getLong("debits");
          long credits = rs.getLong("credits");
          if (debits != credits) {
            mismatches.add(
                new Mismatch(
                    "ledger_balanced",
                    rs.getString("currency") + ":" + rs.getString("phase"),
                    debits,
                    credits));
          }
        });

    Integer transfers = jdbc.queryForObject("SELECT COUNT(*) FROM transfers", Integer.class);
    jdbc.query(
        "SELECT t.id, t.status, t.amount, t.posted_amount,"
            + " COALESCE(SUM(j.amount) FILTER (WHERE j.side = 'DEBIT' AND j.phase = 'POSTED'), 0) AS posted,"
            + " COALESCE(SUM(j.amount) FILTER (WHERE j.side = 'DEBIT' AND j.phase = 'PENDING'), 0) AS pending"
            + " FROM transfers t LEFT JOIN journal_entries j ON j.transfer_id = t.id"
            + " GROUP BY t.id"
            + " HAVING COALESCE(SUM(j.amount) FILTER (WHERE j.side = 'DEBIT' AND j.phase = 'POSTED'), 0)"
            + "   <> t.posted_amount"
            + " OR COALESCE(SUM(j.amount) FILTER (WHERE j.side = 'DEBIT' AND j.phase = 'PENDING'), 0)"
            + "   <> CASE WHEN t.status = 'PENDING' THEN t.amount ELSE 0 END",
        rs -> {
          String id = rs.getString("id");
          long expectedPending =
              "PENDING".equals(rs.getString("status")) ? rs.getLong("amount") : 0;
          if (rs.getLong("posted") != rs.getLong("posted_amount")) {
            mismatches.add(
                new Mismatch(
                    "transfer_entries",
                    id + ":posted",
                    rs.getLong("posted_amount"),
                    rs.getLong("posted")));
          }
          if (rs.getLong("pending") != expectedPending) {
            mismatches.add(
                new Mismatch(
                    "transfer_entries", id + ":pending", expectedPending, rs.getLong("pending")));
          }
        });

    Integer payments = jdbc.queryForObject("SELECT COUNT(*) FROM payments", Integer.class);
    jdbc.query(
        "SELECT p.id, p.captured_amount, p.refunded_amount, t.posted_amount, t.refunded_amount AS t_refunded"
            + " FROM payments p JOIN transfers t ON t.id = p.hold_transfer_id"
            + " WHERE p.captured_amount <> t.posted_amount OR p.refunded_amount <> t.refunded_amount",
        rs -> {
          String id = rs.getString("id");
          if (rs.getLong("captured_amount") != rs.getLong("posted_amount")) {
            mismatches.add(
                new Mismatch(
                    "payment_vs_ledger",
                    id + ":captured",
                    rs.getLong("captured_amount"),
                    rs.getLong("posted_amount")));
          }
          if (rs.getLong("refunded_amount") != rs.getLong("t_refunded")) {
            mismatches.add(
                new Mismatch(
                    "payment_vs_ledger",
                    id + ":refunded",
                    rs.getLong("refunded_amount"),
                    rs.getLong("t_refunded")));
          }
        });

    UUID runId = UUID.randomUUID();
    Instant finished = Instant.now();
    String status = mismatches.isEmpty() ? "OK" : "MISMATCH";
    jdbc.update(
        "INSERT INTO reconciliation_runs (id, trigger, status, accounts_checked, transfers_checked,"
            + " payments_checked, mismatch_count, started_at, finished_at)"
            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
        runId,
        trigger,
        status,
        accounts.size(),
        transfers,
        payments,
        mismatches.size(),
        Timestamp.from(started),
        Timestamp.from(finished));
    jdbc.batchUpdate(
        "INSERT INTO reconciliation_mismatches (run_id, check_name, subject, expected, actual)"
            + " VALUES (?, ?, ?, ?, ?)",
        mismatches.stream()
            .map(m -> new Object[] {runId, m.check(), m.subject(), m.expected(), m.actual()})
            .toList());
    RunResponse response =
        new RunResponse(
            runId,
            trigger,
            status,
            accounts.size(),
            transfers,
            payments,
            mismatches.size(),
            started,
            finished,
            mismatches);
    outbox.append(
        "reconciliation",
        runId,
        mismatches.isEmpty() ? "reconciliation.ok" : "reconciliation.mismatch",
        Map.of("runId", runId, "status", status, "mismatchCount", mismatches.size()));
    return response;
  }

  private static void compare(
      List<Mismatch> out,
      String check,
      String subject,
      Map<String, Object> row,
      String journalKey,
      String columnKey) {
    long expected = ((Number) row.get(journalKey)).longValue();
    long actual = ((Number) row.get(columnKey)).longValue();
    if (expected != actual) {
      out.add(new Mismatch(check, subject, expected, actual));
    }
  }

  public List<RunResponse> recent(int limit) {
    return jdbc.query(
        "SELECT * FROM reconciliation_runs ORDER BY started_at DESC LIMIT ?",
        (rs, i) ->
            new RunResponse(
                rs.getObject("id", UUID.class),
                rs.getString("trigger"),
                rs.getString("status"),
                rs.getInt("accounts_checked"),
                rs.getInt("transfers_checked"),
                rs.getInt("payments_checked"),
                rs.getInt("mismatch_count"),
                rs.getTimestamp("started_at").toInstant(),
                rs.getTimestamp("finished_at").toInstant(),
                List.of()),
        Math.min(Math.max(limit, 1), 200));
  }

  public RunResponse get(UUID id) {
    List<RunResponse> runs =
        jdbc.query(
            "SELECT * FROM reconciliation_runs WHERE id = ?",
            (rs, i) ->
                new RunResponse(
                    rs.getObject("id", UUID.class),
                    rs.getString("trigger"),
                    rs.getString("status"),
                    rs.getInt("accounts_checked"),
                    rs.getInt("transfers_checked"),
                    rs.getInt("payments_checked"),
                    rs.getInt("mismatch_count"),
                    rs.getTimestamp("started_at").toInstant(),
                    rs.getTimestamp("finished_at").toInstant(),
                    jdbc.query(
                        "SELECT check_name, subject, expected, actual"
                            + " FROM reconciliation_mismatches WHERE run_id = ? ORDER BY id",
                        (m, j) ->
                            new Mismatch(
                                m.getString("check_name"),
                                m.getString("subject"),
                                m.getLong("expected"),
                                m.getLong("actual")),
                        id)),
            id);
    if (runs.isEmpty()) {
      throw ApiException.notFound("Reconciliation run", id);
    }
    return runs.getFirst();
  }
}
