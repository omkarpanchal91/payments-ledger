# Running the Payments Ledger — step by step

This guide assumes a fresh macOS machine (Linux notes are included). Follow it top to bottom the first time.

- [1. Prerequisites](#1-prerequisites)
- [2. One-time project setup (Gradle wrapper)](#2-one-time-project-setup-gradle-wrapper)
- [3. Option A — run everything with Docker Compose](#3-option-a--run-everything-with-docker-compose)
- [4. Option B — run the app from your IDE / Gradle, infra in Docker](#4-option-b--run-the-app-locally-infra-in-docker)
- [5. Try the API](#5-try-the-api)
- [6. Run the tests](#6-run-the-tests)
- [7. Run the load test (k6)](#7-run-the-load-test-k6)
- [8. Demo: Kafka is down but the API keeps working](#8-demo-kafka-is-down-but-the-api-keeps-working)
- [9. Watching webhooks](#9-watching-webhooks)
- [10. Observability](#10-observability)
- [11. Configuration reference](#11-configuration-reference)
- [12. Useful SQL while exploring](#12-useful-sql-while-exploring)
- [13. Troubleshooting](#13-troubleshooting)
- [14. Push to GitHub and turn on CI](#14-push-to-github-and-turn-on-ci)

---

## 1. Prerequisites

| Tool | Version | Install (macOS) | Check |
|---|---|---|---|
| Java JDK | 21 | `brew install openjdk@21` then follow the brew hint to symlink, or use [SDKMAN](https://sdkman.io): `sdk install java 21.0.5-tem` | `java -version` shows 21 |
| Gradle | 8.x (only to generate the wrapper once) | `brew install gradle` | `gradle -v` |
| Docker Desktop | recent | https://www.docker.com/products/docker-desktop | `docker info` works |
| jq (for the demo script) | any | `brew install jq` | `jq --version` |
| IntelliJ IDEA (optional) | Community is fine | `brew install --cask intellij-idea-ce` | |

> PyCharm is a JetBrains IDE too, but its Java support is limited. Use **IntelliJ IDEA** for this project:
> it understands Gradle, Spring and JUnit natively.

Give Docker Desktop at least **4 CPUs and 6 GB RAM** (Settings → Resources). Kafka and PostgreSQL run in
containers, and the tests start their own copies.

## 2. One-time project setup (Gradle wrapper)

The Gradle wrapper (`gradlew` plus a small jar) pins the Gradle version for everyone. It's a binary file, so
generate it once:

```bash
cd "/Users/omkarpanchal/PycharmProjects/Idempotent Payments Ledger Service"
gradle wrapper --gradle-version 8.10.2
chmod +x gradlew
./gradlew --version          # should print Gradle 8.10.2 and JVM 21
```

Commit `gradlew`, `gradlew.bat` and the `gradle/` folder. CI runs `./gradlew build`.

> **Python analogy:** the wrapper is like committing a pinned `poetry`/`pip-tools` setup: anyone who runs
> `./gradlew` gets exactly the same build tool version, which downloads itself on first use.

First build, which downloads dependencies into `~/.gradle`. This is like `pip install -r requirements.txt`:

```bash
./gradlew compileJava compileTestJava
```

## 3. Option A — run everything with Docker Compose

```bash
docker compose up --build
```

This starts four containers:

| Service | Port | What it is |
|---|---|---|
| `postgres` | 5432 | PostgreSQL 16, db/user/password = `ledger` |
| `kafka` | 9094 (host), 9092 (inside Docker) | Apache Kafka 3.8 in KRaft mode (no ZooKeeper) |
| `app` | 8080 | The ledger service. Runs the Flyway migrations on startup. |
| `webhook-echo` | 8081 | Prints every HTTP request it receives, so you can watch webhook deliveries |

Wait for this log line (the logs are JSON):
`"message":"Started LedgerApplication in ... seconds"`. Then:

```bash
curl -s localhost:8080/actuator/health | jq
# {"status":"UP","components":{"db":{"status":"UP",...},...}}
```

Stop with `Ctrl+C`. Use `docker compose down -v` to also delete the database volume and start fresh.

## 4. Option B — run the app locally, infra in Docker

This gives faster iteration and lets you use the debugger.

```bash
docker compose up -d postgres kafka         # only infrastructure
./gradlew bootRun --args='--spring.profiles.active=local'
```

- The `local` profile gives human-readable logs and DEBUG logging for `com.omkar.ledger`.
- The defaults in `application.yml` already point to `localhost:5432` and `localhost:9094`.

**In IntelliJ:** open the folder and accept "Load Gradle project". Then open `LedgerApplication.java`, click
the green ▶ next to `main`, and choose *Modify Run Configuration → Active profiles: `local`*.

## 5. Try the API

Swagger UI: **http://localhost:8080/swagger-ui.html**. Click *Authorize* and enter `sk_test_local_dev_key`.

Or run the scripted end-to-end demo:

```bash
./scripts/demo.sh
```

It creates a customer and a merchant and registers a webhook. Then it funds the customer, makes a payment
(replayed with the same key), shows the 422 for a reused key with a different body, makes a partial refund,
and prints the balances, the statement and the reconciliation.

All endpoints, with `curl` examples, are in [API.md](API.md).

## 6. Run the tests

Docker must be running: the integration tests start real PostgreSQL and Kafka containers with Testcontainers.

```bash
./gradlew test                    # all tests (unit + integration), then JaCoCo report
./gradlew test --tests '*FeeCalculatorTest'                        # one class
./gradlew test --tests '*TransferConcurrencyIntegrationTest'       # the headline concurrency test
./gradlew build                   # compile + test + package (what CI runs)
```

Reports:

| Report | Path |
|---|---|
| Test results (HTML) | `build/reports/tests/test/index.html` |
| Coverage (HTML) | `build/reports/jacoco/test/html/index.html` |
| Coverage (XML, for tools) | `build/reports/jacoco/test/jacocoTestReport.xml` |
| Concurrency test numbers | `build/concurrency-test-result.txt` |

The first run pulls the `postgres:16-alpine`, `apache/kafka:3.8.0` and Testcontainers' `ryuk` images, so it is slower.
Later runs reuse them. The containers start **once per test JVM** and are shared by all test classes. See
[TESTING.md](TESTING.md).

## 7. Run the load test (k6)

With the app running (Option A or B):

```bash
# macOS (Docker Desktop)
docker run --rm -i -e BASE_URL=http://host.docker.internal:8080 grafana/k6:0.54.0 run - < loadtest/k6-transfers.js

# Linux
docker run --rm -i --network host -e BASE_URL=http://localhost:8080 grafana/k6:0.54.0 run - < loadtest/k6-transfers.js

# Tune it
docker run --rm -i -e BASE_URL=http://host.docker.internal:8080 -e VUS=100 -e DURATION=2m -e WALLETS=50 \
  grafana/k6:0.54.0 run - < loadtest/k6-transfers.js
```

Read these lines from the k6 summary and copy them into the README's *Measured results* table:

- `http_reqs ...... <N> <rate>/s`: throughput
- `http_req_duration ... p(95)=<X>ms`: p95 latency
- `transfers_unexpected`: must be 0
- `✓ ledger balanced after load`: the teardown runs reconciliation

Record your hardware and Docker resource limits next to the numbers.

## 8. Demo: Kafka is down but the API keeps working

This demonstrates the transactional outbox.

```bash
docker compose up -d                       # everything running
docker compose stop kafka                  # take Kafka away

# Make a few payments/transfers. They still return 201.
./scripts/demo.sh

# Events pile up unpublished in the outbox:
docker compose exec postgres psql -U ledger -c \
  "SELECT count(*) AS unpublished FROM outbox_events WHERE published_at IS NULL;"

docker compose start kafka                 # bring Kafka back
sleep 15
docker compose exec postgres psql -U ledger -c \
  "SELECT count(*) AS unpublished FROM outbox_events WHERE published_at IS NULL;"   # -> 0
```

While Kafka is down, the app logs `Outbox relay run failed` / `Failed to publish outbox event` warnings.
Each failed send gives up quickly (`max.block.ms=5000`), and the relay stops the batch at the first failure.

## 9. Watching webhooks

`demo.sh` registers `http://webhook-echo:8080/hooks` for its merchant. Watch the deliveries:

```bash
docker compose logs -f webhook-echo
```

Each request carries `Ledger-Signature: t=...,v1=...`, `Ledger-Event-Id` and `Ledger-Delivery-Attempt`.
To verify a signature yourself, see `WebhookSignatureVerifier` (Java) or this shell one-liner:

```bash
SECRET='<secret from the registration response>'; T='<t value>'; BODY='<raw body>'
printf '%s.%s' "$T" "$BODY" | openssl dgst -sha256 -hmac "$SECRET"
```

Delivery state lives in the DB:

```bash
docker compose exec postgres psql -U ledger -c \
  "SELECT status, attempt_count, last_response_code, next_attempt_at FROM webhook_deliveries ORDER BY created_at DESC LIMIT 10;"
```

## 10. Observability

| URL | What |
|---|---|
| `/actuator/health` | Liveness/readiness, including the DB |
| `/actuator/metrics` | List of metric names |
| `/actuator/metrics/ledger.payments.created` | Custom counter (tag `status=succeeded/failed`) |
| `/actuator/metrics/ledger.idempotency.replays` | Requests served from a stored idempotent response |
| `/actuator/metrics/ledger.transactions.retries` | Business transactions retried after a deadlock/lock/version conflict |
| `/actuator/metrics/ledger.webhooks.deliveries` | Tag `outcome=success/failure/dead` |
| `/actuator/metrics/ledger.outbox.published` | Events acknowledged by Kafka |
| `/actuator/prometheus` | Everything in Prometheus text format |

Every log line carries `request_id` (and `client_id` for API calls). Send your own id with
`-H "X-Request-Id: my-trace-1"`. It is echoed in the response header and in error bodies.

## 11. Configuration reference

All settings live in `src/main/resources/application.yml`. Every one can be overridden by an environment variable:
Spring maps `ledger.fees.percent-bps` to `LEDGER_FEES_PERCENT_BPS`.

| Env var | Default | Meaning |
|---|---|---|
| `DB_URL` / `DB_USERNAME` / `DB_PASSWORD` | `jdbc:postgresql://localhost:5432/ledger`, `ledger`, `ledger` | Database |
| `DB_POOL_SIZE` | 20 | HikariCP max connections |
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9094` | Kafka |
| `LEDGER_API_KEY` / `LEDGER_CLIENT_ID` | `sk_test_local_dev_key` / `local-dev` | The single configured admin API key. **Change it outside your laptop.** |
| `LEDGER_FEES_PERCENT_BPS` / `LEDGER_FEES_FIXED_MINOR` | 290 / 30 | Fee = 2.90% + 30 minor units |
| `LEDGER_IDEMPOTENCY_LOCK_TIMEOUT` | 30s | How long an IN_PROGRESS key is protected before takeover |
| `LEDGER_IDEMPOTENCY_RETENTION` | 24h | Key lifetime |
| `LEDGER_TRANSACTIONS_MAX_RETRIES` | 3 | Deadlock/serialization retries before 409 |
| `LEDGER_OUTBOX_RELAY_INTERVAL_MS` | 200 | Relay poll interval |
| `LEDGER_OUTBOX_BATCH_SIZE` | 100 | Rows per relay batch |
| `LEDGER_WEBHOOKS_RETRY_SCHEDULE` | `10s,30s,2m,10m,30m,1h` | Delay after attempt 1, 2, ... (7 attempts total) |
| `LEDGER_WEBHOOKS_JITTER_RATIO` | 0.2 | ±20% randomization of each delay |
| `LEDGER_WEBHOOKS_REQUEST_TIMEOUT` | 5s | Per-delivery HTTP timeout |
| `LEDGER_WEBHOOKS_DISPATCHER_ENABLED` | true | Turn the Kafka consumer and retry worker off |

## 12. Useful SQL while exploring

```bash
docker compose exec postgres psql -U ledger
```

```sql
-- balances, human-readable
SELECT code, type, CASE WHEN type IN ('ASSET','EXPENSE') THEN balance_minor ELSE -balance_minor END AS balance
FROM accounts ORDER BY created_at DESC LIMIT 20;

-- a journal entry and its postings
SELECT j.type, j.description, a.code, p.amount_minor
FROM journal_entries j JOIN postings p ON p.journal_entry_id = j.id JOIN accounts a ON a.id = p.account_id
ORDER BY j.created_at DESC LIMIT 12;

-- every entry sums to zero (should return no rows)
SELECT journal_entry_id, SUM(amount_minor) FROM postings GROUP BY 1 HAVING SUM(amount_minor) <> 0;

-- idempotency keys
SELECT idempotency_key, endpoint, status, response_status, locked_until FROM idempotency_keys ORDER BY created_at DESC LIMIT 10;

-- try to break immutability (this fails on purpose)
UPDATE postings SET amount_minor = 1;
```

## 13. Troubleshooting

| Symptom | Fix |
|---|---|
| `./gradlew: No such file or directory` | Run step 2 (`gradle wrapper --gradle-version 8.10.2`). |
| `Unsupported class file major version 65` / toolchain errors | Gradle is running on an older JDK. `export JAVA_HOME=$(/usr/libexec/java_home -v 21)` |
| Tests fail with `Could not find a valid Docker environment` | Start Docker Desktop. On macOS with a non-default socket (Colima, Rancher), set `DOCKER_HOST` and `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock`. |
| App can't connect to Kafka from the host (`localhost:9094`) | Make sure the `kafka` container is healthy: `docker compose ps`. The host listener is `EXTERNAL://localhost:9094`. |
| `port 5432 already in use` | A local PostgreSQL is running. Stop it (`brew services stop postgresql`) or change the compose port mapping. |
| Flyway `checksum mismatch` after you edited a migration | Never edit an applied migration. Add a new `V8__...sql`. For local dev only: `docker compose down -v`. |
| Every POST returns 400 `missing_header` | Add `-H "Idempotency-Key: $(uuidgen)"`. |
| 409 `request_in_progress` | Another request with the same key is still running (or crashed less than 30s ago). Retry after a moment. |
| Webhooks never arrive at `webhook-echo` | The registered URL must be reachable **from the app container**: use `http://webhook-echo:8080/...`, not `localhost`. |

## 14. Push to GitHub and turn on CI

```bash
git init
git add .
git commit -m "Idempotent payments ledger service"
git branch -M main
git remote add origin git@github.com:<your-github-user>/payments-ledger.git
git push -u origin main
```

`.github/workflows/ci.yml` runs on every push and PR. It sets up Java 21, caches Gradle and runs `./gradlew build`
(Docker is available on `ubuntu-latest`, so Testcontainers works). It then uploads the JaCoCo and test reports as
build artifacts. Update the badge URL at the top of the README with your GitHub username.
