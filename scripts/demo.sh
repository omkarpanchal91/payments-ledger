#!/usr/bin/env bash
# End-to-end demo against a running instance (docker compose up).
# Requires: curl, jq.   Usage: ./scripts/demo.sh [base_url]
set -euo pipefail

BASE="${1:-http://localhost:8080}"
KEY="${LEDGER_API_KEY:-sk_test_local_dev_key}"
RUN="$(date +%s)"

post() { # path json [idempotency-key]
  local key="${3:-$(uuidgen)}"
  curl -sS -X POST "$BASE$1" -H "X-Api-Key: $KEY" -H "Content-Type: application/json" \
       -H "Idempotency-Key: $key" -d "$2"
}
get() { curl -sS "$BASE$1" -H "X-Api-Key: $KEY"; }

echo "== 1. Create customer wallet and merchant account"
CUSTOMER=$(post /v1/accounts "{\"code\":\"customer_funds:c_$RUN\",\"type\":\"LIABILITY\",\"currency\":\"USD\"}" | jq -r .id)
MERCHANT=$(post /v1/accounts "{\"code\":\"merchant_balance:m_$RUN\",\"type\":\"LIABILITY\",\"currency\":\"USD\"}" | jq -r .id)
echo "customer=$CUSTOMER merchant=$MERCHANT"

echo "== 2. Register a webhook endpoint (see deliveries with: docker compose logs -f webhook-echo)"
post /v1/webhook_endpoints "{\"merchant_id\":\"m_$RUN\",\"url\":\"http://webhook-echo:8080/hooks\",\"enabled_events\":[\"*\"]}" | jq .

echo "== 3. Fund the customer with \$100.00"
post /v1/fundings "{\"account_id\":\"$CUSTOMER\",\"amount_minor\":10000,\"currency\":\"USD\"}" | jq .

echo "== 4. Pay \$10.00 twice with the SAME Idempotency-Key (second call is a replay)"
PAY_KEY="pay-$RUN"
BODY="{\"customer_id\":\"c_$RUN\",\"merchant_id\":\"m_$RUN\",\"amount_minor\":1000,\"currency\":\"USD\"}"
PAYMENT=$(post /v1/payments "$BODY" "$PAY_KEY")
echo "$PAYMENT" | jq .
curl -sS -i -X POST "$BASE/v1/payments" -H "X-Api-Key: $KEY" -H "Content-Type: application/json" \
     -H "Idempotency-Key: $PAY_KEY" -d "$BODY" | grep -i -E "^(HTTP|idempotent-replayed)"
PAYMENT_ID=$(echo "$PAYMENT" | jq -r .id)

echo "== 5. Same key, different body -> 422 idempotency_key_reused"
post /v1/payments "${BODY/1000/2000}" "$PAY_KEY" | jq .

echo "== 6. Partial refund of \$3.00"
post "/v1/payments/$PAYMENT_ID/refunds" '{"amount_minor":300}' | jq .

echo "== 7. Balances"
for id in $CUSTOMER $MERCHANT; do get "/v1/accounts/$id" | jq '{code, balance_minor}'; done

echo "== 8. Customer statement"
get "/v1/accounts/$CUSTOMER/postings?limit=10" | jq '.data[] | {entry_type, amount_minor, direction}'

echo "== 9. Reconciliation"
get /v1/admin/reconciliation | jq '{balanced, accounts_checked, journal_entries_checked}'
