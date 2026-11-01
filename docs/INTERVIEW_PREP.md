# Interview prep

## Resume bullets

Fill in the bracketed values **only from your own runs** (see [TESTING.md](TESTING.md#recording-results)). Keep each
bullet under 25 words.

1. Built double-entry payments ledger (Java 21, Spring Boot, PostgreSQL, Kafka) with idempotent APIs; 1,000 parallel transfers: zero deadlocks, zero drift, [N]/s.
2. Implemented a transactional outbox, at-least-once Kafka relay and HMAC-signed webhooks with backoff retries and a DLQ; [N] tests, [X]% coverage, k6 p95 [Y] ms.

## 15 likely questions with model answers

**1. Walk me through what happens when a client calls `POST /v1/payments`.**
Two filters run first: one assigns a request id and one authenticates the API key. The executor hashes the
canonical body and claims the idempotency key in a short transaction (`INSERT ... ON CONFLICT DO NOTHING`). Then,
in one business transaction: lock the customer, merchant and fee accounts in id order; check funds; insert one
journal entry with three postings that sum to zero; update the cached balances; insert the payment row and an
outbox event; and mark the key COMPLETED with the response. At commit, a deferred trigger re-checks that the entry
balances. Later, the relay publishes the event to Kafka, and the dispatcher delivers a signed webhook.

**2. Why double-entry instead of just a `balance` column?**
Every movement is recorded as balanced debits and credits, so money can't be created or lost by a bug without the
books going out of balance, and the imbalance is detectable. Postings are append-only, which gives a full audit
trail, and corrections are reversing entries. The cached balance is only a read optimization, and reconciliation
proves it equals `SUM(postings)`.

**3. How does your idempotency work, and what if the server crashes halfway?**
A key is claimed as IN_PROGRESS with a 30-second lock in its own transaction. Completion (status plus stored response)
happens in the same transaction as the money movement, so they commit or roll back together. If we crash before
commit, nothing moved and the key is still IN_PROGRESS. After the lock expires, a retry takes it over with a
conditional `UPDATE ... WHERE locked_until <= now()`, so only one racer wins. A `lock_owner` token stops a slow
original request from also completing after a takeover.

**4. Two identical requests arrive at the same millisecond. What happens?**
Both try to `INSERT`. The unique constraint lets exactly one succeed. The other sees an IN_PROGRESS row with a
valid lock and gets 409 `request_in_progress`. When it retries after the first one completes, it gets the stored
response byte-for-byte. The test fires 10 concurrent requests with the same key and asserts exactly one payment.

**5. Why store business errors like `insufficient_funds` under the key, but not 500s?**
A deterministic error is the real answer to that request. Retrying with the same key should return the same
answer, even if the customer has been topped up since; a new attempt uses a new key. A 500 or a deadlock-after-retries
tells us nothing about the request, so we delete the key and the client can retry safely.

**6. Why pessimistic locks for balances but optimistic locks for metadata?**
Balance rows are hot and contended. With optimistic locking, all but one concurrent writer fails and redoes its
work, so throughput collapses and hot accounts can starve. Row locks queue the writers, and each does its work once.
Metadata updates (freezing an account, `refunded_minor`) are rare, so `@Version` detects the occasional conflict
without holding locks. Refunds use both: `FOR UPDATE` on the payment row, plus `@Version` as a safety net.

**7. How do you prevent deadlocks?**
A deadlock needs a cycle: T1 holds A and waits for B while T2 holds B and waits for A. Every transaction locks
accounts in ascending UUID order, so A→B and B→A both lock the smaller id first, and a cycle can't form. As a safety
net, deadlock, serialization and lock-timeout errors are retried up to three times with jitter, then returned as 409.

**8. What is the transactional outbox, and why not publish to Kafka directly?**
Committing to Postgres and publishing to Kafka are two systems with no shared transaction. A crash between them
either loses the event or announces money that never moved. Instead, the event is written as a row in the same
transaction as the postings. A relay polls with `FOR UPDATE SKIP LOCKED`, publishes, waits for the ack and marks the
row published. `SKIP LOCKED` lets several instances relay in parallel without blocking each other.

**9. Is your Kafka delivery exactly-once?**
No, it's at-least-once. If the relay crashes after Kafka acked but before it marked the rows published, it sends
them again. The idempotent producer only dedupes the producer's own retries within a session. So consumers dedupe by
event id: the dispatcher inserts deliveries with `ON CONFLICT (event_id, endpoint_id) DO NOTHING`, and it commits the
Kafka offset only after that insert commits. The *effect* is exactly-once.

**10. How do you keep events for one payment in order?**
The Kafka key is the aggregate id, and refund events use the payment as their aggregate. Kafka orders messages
within a partition, and the key picks the partition, so `payment.succeeded` comes before `refund.created`. The
caveat: if one send in a batch fails and a later one succeeds, they can be out of order. A per-aggregate sequence
number would fix that.

**11. How are webhooks secured?**
`Ledger-Signature: t=<ts>,v1=HMAC_SHA256(secret, "<ts>.<raw body>")`. The secret is 32 random bytes and is shown
once. The merchant recomputes the HMAC over the raw body, compares in constant time, and rejects timestamps older
than 5 minutes, which blocks replays because the timestamp is signed. Multiple `v1` values allow secret rotation.

**12. What happens when a merchant's endpoint is down?**
The delivery goes to RETRYING with exponential backoff (10s, 30s, 2m, 10m, 30m, 1h) and ±20% jitter, so a
recovering merchant isn't hit by everything at once. Workers claim deliveries with a lease: they push
`next_attempt_at` forward and commit, so no DB lock is held during the HTTP call, and a crashed worker's work is
picked up again. After the last attempt the delivery is DEAD and goes to `ledger.events.dlq`, and
`dlq_published_at` tracks whether Kafka acked it.

**13. How do partial refunds handle the fee, and the rounding?**
The fee is refunded proportionally on the cumulative amount:
`share = round(fee × (refunded + r) / amount) − round(fee × refunded / amount)`. The sum telescopes, so any split of a
full refund returns exactly the fee, and no share ever exceeds the refund itself. Two parallel refunds can't
over-refund, because the payment row is locked and the DB also has `CHECK (refunded_minor <= amount_minor)`.

**14. How do you know balances never drift?**
Three layers. Cached balance and postings are updated in the same transaction. A deferred trigger rejects any
unbalanced entry at commit, and append-only triggers block edits. The reconciliation endpoint recomputes every
balance from postings in a REPEATABLE READ snapshot and checks that every entry and every currency sums to zero.
The concurrency test asserts all of this after 1,000 parallel transfers.

**15. What would you change for production scale?**
Shard hot accounts such as `platform_fees` into sub-accounts, because every payment serializes on that one row
lock. Partition or archive the outbox and delivery tables. Add hashed, rotatable API keys with per-merchant
authorization. Encrypt webhook secrets with a KMS. Add per-aggregate sequence numbers to events, plus payouts and
reversals in the API. Add read replicas for statements, and tracing (OpenTelemetry) across HTTP → DB → Kafka →
webhook.

## Rapid-fire follow-ups

| Question | One-liner |
|---|---|
| Why `long` cents, not `BigDecimal` amounts? | Exact, fast and compact. `BigDecimal` is only for intermediate rounding. Currencies with 3 decimals still fit in minor units. |
| Why a deferred trigger, not `CHECK`? | A CHECK sees one row. The invariant spans all postings of an entry, which only exist together at commit. |
| Why `SKIP LOCKED` instead of `NOWAIT`? | NOWAIT errors out. SKIP LOCKED quietly takes the next free rows, which is the right behaviour for a work queue. |
| Why is `open-in-view` off? | Retries need a fresh persistence context, and lazy loading in views hides N+1 queries. |
| Why TEXT for `response_body`? | JSONB reorders keys, and replays must be byte-identical. |
| Why REPEATABLE READ for reconciliation? | One snapshot across several queries, so in-flight transfers don't create false mismatches. |
| What does `noRollbackFor` do on `LedgerService.post`? | It stops Spring marking the caller's transaction rollback-only, so `PaymentService` can catch insufficient funds and commit a FAILED payment. |
