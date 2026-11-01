package com.omkar.ledger.transfer;

import static org.assertj.core.api.Assertions.assertThat;

import com.omkar.ledger.support.ApiResponse;
import com.omkar.ledger.support.IntegrationTestBase;
import com.omkar.ledger.support.TestIds;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * The headline test: 1,000 transfers fired in parallel over real HTTP against 10 accounts,
 * including opposite directions on the same pair (A->B and B->A), which is the classic deadlock
 * recipe if locks are taken in inconsistent order.
 */
class TransferConcurrencyIntegrationTest extends IntegrationTestBase {

    private static final int ACCOUNTS = 10;
    private static final int TRANSFERS = 1_000;
    private static final int THREADS = 50;
    private static final long INITIAL_BALANCE = 100_000;

    @Autowired
    private NamedParameterJdbcTemplate namedJdbc;

    @Test
    void thousandParallelTransfersKeepTheLedgerConsistent() throws Exception {
        List<UUID> accounts = new ArrayList<>();
        for (int i = 0; i < ACCOUNTS; i++) {
            accounts.add(api.createWallet(TestIds.unique("conc"), INITIAL_BALANCE));
        }

        Random random = new Random(20241031L);
        List<long[]> plan = new ArrayList<>(TRANSFERS);
        for (int i = 0; i < TRANSFERS; i++) {
            int from;
            int to;
            if (i < 200) {
                // Hot pair in alternating directions: A->B, B->A, A->B, ...
                from = i % 2;
                to = 1 - from;
            } else {
                from = random.nextInt(ACCOUNTS);
                do {
                    to = random.nextInt(ACCOUNTS);
                } while (to == from);
            }
            long amount = 1 + random.nextInt(5_000);
            plan.add(new long[]{from, to, amount});
        }

        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>(TRANSFERS);
        for (long[] step : plan) {
            UUID from = accounts.get((int) step[0]);
            UUID to = accounts.get((int) step[1]);
            long amount = step[2];
            results.add(pool.submit(() -> {
                start.await();
                ApiResponse r = api.transfer(from, to, amount);
                return r.status();
            }));
        }

        long startedAt = System.nanoTime();
        start.countDown();
        Map<Integer, Integer> statusCounts = new TreeMap<>();
        for (Future<Integer> result : results) {
            statusCounts.merge(result.get(2, TimeUnit.MINUTES), 1, Integer::sum);
        }
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
        pool.shutdown();

        int succeeded = statusCounts.getOrDefault(201, 0);
        int rejected = statusCounts.getOrDefault(422, 0);

        // 1. Nothing but success or a clean business rejection: no deadlock/409/500 leaked out.
        assertThat(statusCounts.keySet()).as("HTTP statuses %s", statusCounts).isSubsetOf(201, 422);
        assertThat(succeeded + rejected).isEqualTo(TRANSFERS);

        var params = new MapSqlParameterSource("ids", accounts);

        // 2. Money is conserved: transfers only move value around.
        Long totalSigned = namedJdbc.queryForObject(
                "SELECT SUM(balance_minor) FROM accounts WHERE id IN (:ids)", params, Long.class);
        assertThat(-totalSigned).isEqualTo(ACCOUNTS * INITIAL_BALANCE);

        // 3. Every cached balance equals the sum of its postings.
        Long mismatches = namedJdbc.queryForObject("""
                SELECT count(*) FROM accounts a
                WHERE a.id IN (:ids)
                  AND a.balance_minor <> (SELECT COALESCE(SUM(p.amount_minor), 0) FROM postings p
                                          WHERE p.account_id = a.id)
                """, params, Long.class);
        assertThat(mismatches).isZero();

        // 4. No wallet went negative (credit-normal: normal balance = -signed balance).
        Long negative = namedJdbc.queryForObject(
                "SELECT count(*) FROM accounts WHERE id IN (:ids) AND -balance_minor < 0", params, Long.class);
        assertThat(negative).isZero();

        // 5. Exactly one journal entry per successful transfer.
        Long transferEntries = namedJdbc.queryForObject("""
                SELECT count(DISTINCT j.id) FROM journal_entries j
                JOIN postings p ON p.journal_entry_id = j.id
                WHERE j.type = 'TRANSFER' AND p.account_id IN (:ids)
                """, params, Long.class);
        assertThat(transferEntries).isEqualTo(succeeded);

        double throughput = TRANSFERS * 1000.0 / Math.max(1, elapsedMillis);
        String report = String.format(
                "Concurrency test: %d transfers, %d threads, %d accounts%n"
                        + "  succeeded=%d rejected(insufficient_funds)=%d statuses=%s%n"
                        + "  elapsed=%d ms throughput=%.1f transfers/s%n",
                TRANSFERS, THREADS, ACCOUNTS, succeeded, rejected, statusCounts, elapsedMillis, throughput);
        System.out.print(report);
        writeReport(report);
    }

    private static void writeReport(String report) throws IOException {
        Path file = Path.of("build", "concurrency-test-result.txt");
        Files.createDirectories(file.getParent());
        Files.writeString(file, report);
    }
}
