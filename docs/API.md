# API reference

Base URL `http://localhost:8080`. Interactive docs are at **`/swagger-ui.html`**, and the OpenAPI JSON is at `/v3/api-docs`.

## Conventions

| Rule | Detail |
|---|---|
| Auth | Header `X-Api-Key: <key>`. Dev default: `sk_test_local_dev_key`. Missing or wrong → 401. `/v1/admin/**` needs an admin key → otherwise 403. |
| Idempotency | **Every POST** needs `Idempotency-Key: <1–255 chars>` (a UUID is ideal). Keys are scoped to *(API key's client, key, method + path)* and kept for 24h. |
| Replay | A repeated key with the same body returns the original status and body byte-for-byte, plus `Idempotent-Replayed: true`. |
| Money | Integers in **minor units** (`amount_minor: 1000` = $10.00) plus an ISO 4217 `currency`. Never floats. |
| JSON | `snake_case`. Unknown fields are rejected (400). Enum values are case-insensitive. |
| Tracing | Optional `X-Request-Id` header. It is echoed back and included in error bodies and logs. |
| Pagination | `limit` (1–100, default 20) and `starting_after=<id of last item>`. Responses are `{ "object": "list", "data": [...], "has_more": bool }`. |

Set up a shell for the examples:

```bash
export BASE=http://localhost:8080
export KEY=sk_test_local_dev_key
alias lpost='curl -s -X POST -H "X-Api-Key: $KEY" -H "Content-Type: application/json"'
alias lget='curl -s -H "X-Api-Key: $KEY"'
```

---

## Accounts

### `POST /v1/accounts` — create an account

```bash
lpost $BASE/v1/accounts -H "Idempotency-Key: $(uuidgen)" \
  -d '{"code":"customer_funds:c_123","type":"LIABILITY","currency":"USD","allow_negative":false}'
```

| Field | Type | Notes |
|---|---|---|
| `code` | string, required | unique, `[A-Za-z0-9_.:-]{1,128}`. Conventions: `customer_funds:<id>`, `merchant_balance:<id>` |
| `type` | `ASSET` \| `LIABILITY` \| `REVENUE` \| `EXPENSE` \| `EQUITY` | determines the normal side |
| `currency` | ISO 4217 | |
| `allow_negative` | boolean, default false | if false, money movements that would take it below zero fail with `insufficient_funds` |

`201`:

```json
{
  "id": "0d1c…", "object": "account", "code": "customer_funds:c_123", "type": "LIABILITY",
  "normal_side": "CREDIT", "currency": "USD", "balance_minor": 0, "signed_balance_minor": 0,
  "allow_negative": false, "status": "ACTIVE",
  "created_at": "2026-10-31T10:00:00.123456Z", "updated_at": "2026-10-31T10:00:00.123456Z"
}
```

`balance_minor` is the balance on the account's normal side (a funded wallet is positive).
`signed_balance_minor` is the raw ledger sum (debit +, credit −).

Errors: `400 invalid_currency | parameter_invalid | parameter_missing`, `422 account_code_taken`.

### `GET /v1/accounts/{id}`

```bash
lget $BASE/v1/accounts/<account_id>
```

Errors: `404 account_not_found`, `400 parameter_invalid` (malformed UUID).

### `GET /v1/accounts/{id}/postings?limit=&starting_after=` — statement

Newest first.

```bash
lget "$BASE/v1/accounts/<account_id>/postings?limit=2"
lget "$BASE/v1/accounts/<account_id>/postings?limit=2&starting_after=<last posting id>"
```

```json
{
  "object": "list",
  "data": [
    { "id": "…", "object": "posting", "journal_entry_id": "…", "account_id": "…",
      "amount_minor": -10000, "direction": "CREDIT", "entry_type": "FUNDING",
      "description": "top-up", "created_at": "…" }
  ],
  "has_more": true
}
```

Errors: `400 parameter_invalid` (limit out of 1–100), `400 invalid_cursor`, `404 account_not_found`.

### `POST /v1/accounts/{id}/freeze` and `/unfreeze`

```bash
lpost $BASE/v1/accounts/<account_id>/freeze -H "Idempotency-Key: $(uuidgen)"
```

`200` with the account. While an account is frozen, any money movement touching it fails with `422 account_frozen`.
These endpoints use optimistic locking (`@Version`).

---

## Fundings

### `POST /v1/fundings` — money enters from `external_settlement:<CUR>`

```bash
lpost $BASE/v1/fundings -H "Idempotency-Key: $(uuidgen)" \
  -d '{"account_id":"<wallet id>","amount_minor":10000,"currency":"USD","description":"card top-up"}'
```

`201`:

```json
{ "id": "<journal entry id>", "object": "funding", "account_id": "…", "settlement_account_id": "…",
  "amount_minor": 10000, "currency": "USD", "description": "card top-up", "created_at": "…" }
```

Errors: `404 account_not_found`, `422 invalid_funding_target` (target must be credit-normal, e.g. LIABILITY),
`422 unsupported_currency` (no settlement account for it), `422 currency_mismatch`, `422 account_frozen`.

---

## Transfers

### `POST /v1/transfers`

```bash
lpost $BASE/v1/transfers -H "Idempotency-Key: $(uuidgen)" \
  -d '{"from_account_id":"<A>","to_account_id":"<B>","amount_minor":2500,"currency":"USD","description":"rent split"}'
```

`201`:

```json
{ "id": "<journal entry id>", "object": "transfer", "from_account_id": "<A>", "to_account_id": "<B>",
  "amount_minor": 2500, "currency": "USD", "description": "rent split", "created_at": "…" }
```

| Error | When |
|---|---|
| `400 same_account` | from == to |
| `400 parameter_invalid` / `parameter_missing` | amount ≤ 0, missing fields |
| `404 account_not_found` | either account missing |
| `422 insufficient_funds` | source would go below zero and `allow_negative` is false |
| `422 currency_mismatch` | an account isn't in `currency` |
| `422 incompatible_accounts` | the two accounts have different normal sides (e.g. LIABILITY → ASSET) |
| `422 account_frozen` | either account frozen |
| `409 concurrent_modification` | still conflicting after 3 deadlock/lock retries (rare). Safe to retry with the same key. |

---

## Payments

### `POST /v1/payments`

The accounts `customer_funds:<customer_id>` and `merchant_balance:<merchant_id>` must already exist.

```bash
lpost $BASE/v1/payments -H "Idempotency-Key: order-42" \
  -d '{"customer_id":"c_123","merchant_id":"m_456","amount_minor":1000,"currency":"USD"}'
```

`201`:

```json
{ "id": "…", "object": "payment", "merchant_id": "m_456", "customer_id": "c_123",
  "amount_minor": 1000, "fee_minor": 59, "net_minor": 941, "currency": "USD", "status": "SUCCEEDED",
  "refunded_minor": 0, "journal_entry_id": "…", "created_at": "…" }
```

Fee: `round_half_up(amount × 2.9%) + 30` (configurable).

| Error | When |
|---|---|
| `422 insufficient_funds` | the customer can't cover it. A **FAILED payment row is still stored**, and the same key always returns this 422. |
| `422 amount_too_small` | amount ≤ fee |
| `404 customer_account_not_found` / `merchant_account_not_found` | |
| `422 unsupported_currency` | no `platform_fees:<CUR>` account |
| `422 idempotency_key_reused` | same key, different body |
| `409 request_in_progress` | a request with the same key is still running |

### `GET /v1/payments/{id}`

```bash
lget $BASE/v1/payments/<payment_id>
```

---

## Refunds

### `POST /v1/payments/{id}/refunds`

Leave out `amount_minor`, or send no body at all, to refund everything that's left.

```bash
lpost $BASE/v1/payments/<payment_id>/refunds -H "Idempotency-Key: $(uuidgen)" -d '{"amount_minor":300}'
lpost $BASE/v1/payments/<payment_id>/refunds -H "Idempotency-Key: $(uuidgen)"          # full remaining
```

`201`:

```json
{ "id": "…", "object": "refund", "payment_id": "…", "merchant_id": "m_456", "amount_minor": 300,
  "fee_refund_minor": 18, "currency": "USD", "status": "SUCCEEDED", "journal_entry_id": "…", "created_at": "…" }
```

Errors: `404 payment_not_found`, `422 payment_not_refundable` (payment FAILED), `422 payment_fully_refunded`,
`422 refund_exceeds_payment`, `422 insufficient_funds` (merchant already paid out the money).

### `GET /v1/refunds/{id}`

```bash
lget $BASE/v1/refunds/<refund_id>
```

---

## Webhooks

### `POST /v1/webhook_endpoints`

```bash
lpost $BASE/v1/webhook_endpoints -H "Idempotency-Key: $(uuidgen)" \
  -d '{"merchant_id":"m_456","url":"https://merchant.example/hooks","enabled_events":["payment.succeeded","refund.created"]}'
```

`201`. **`secret` is only returned here, so store it.**

```json
{ "id": "…", "object": "webhook_endpoint", "merchant_id": "m_456", "url": "https://merchant.example/hooks",
  "enabled_events": ["payment.succeeded", "refund.created"], "status": "ENABLED",
  "secret": "q3V1…base64…=", "created_at": "…" }
```

`enabled_events` can contain `payment.succeeded`, `refund.created` or `*`. Errors: `400 invalid_url`, `400 unknown_event_type`.

**What the merchant receives:**

```
POST /hooks
Content-Type: application/json
Ledger-Signature: t=1730371200,v1=5f2b…(64 hex chars)
Ledger-Event-Id: 6f1c0e4e-…
Ledger-Delivery-Attempt: 1

{"id":"6f1c0e4e-…","type":"payment.succeeded","created":1730371200,"data":{…payment…}}
```

Respond with any `2xx` within 5 seconds. Anything else is retried after 10s, 30s, 2m, 10m, 30m and 1h (±20%).
After the 7th failed attempt the delivery is `DEAD` and is published to `ledger.events.dlq`. Dedupe on `id`,
because the same event can arrive more than once.

### `GET /v1/events/{id}`

Re-fetch an event from the source of truth, for example to double-check a webhook.

```bash
lget $BASE/v1/events/<event_id>
```

---

## Admin

### `GET /v1/admin/reconciliation`

```bash
lget $BASE/v1/admin/reconciliation
```

```json
{ "balanced": true, "checked_at": "…", "accounts_checked": 42, "journal_entries_checked": 120,
  "account_mismatches": [], "unbalanced_entries": [], "currency_imbalances": [] }
```

A mismatch entry looks like
`{"account_id":"…","code":"…","cached_balance_minor":1007,"postings_sum_minor":1000}`.

---

## Idempotency walkthrough

```bash
K=$(uuidgen)
BODY='{"customer_id":"c_123","merchant_id":"m_456","amount_minor":1000,"currency":"USD"}'

lpost -i $BASE/v1/payments -H "Idempotency-Key: $K" -d "$BODY"   # 201, Idempotent-Replayed: false
lpost -i $BASE/v1/payments -H "Idempotency-Key: $K" -d "$BODY"   # 201, Idempotent-Replayed: true, same body
lpost $BASE/v1/payments -H "Idempotency-Key: $K" \
  -d '{"customer_id":"c_123","merchant_id":"m_456","amount_minor":2000,"currency":"USD"}'
# 422 {"error":{"type":"idempotency_error","code":"idempotency_key_reused",…}}
```
