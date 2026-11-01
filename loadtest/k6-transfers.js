// k6 load test: random transfers between a pool of funded wallets.
//
// Run (app must be up, e.g. via `docker compose up`):
//   docker run --rm -i -e BASE_URL=http://host.docker.internal:8080 grafana/k6:0.54.0 run - < loadtest/k6-transfers.js
// On Linux use --network host and BASE_URL=http://localhost:8080.
//
// Tunables (env vars): BASE_URL, API_KEY, WALLETS, VUS, DURATION, INITIAL_BALANCE
import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';
import { uuidv4 } from 'https://jslib.k6.io/k6-utils/1.4.0/index.js';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const API_KEY = __ENV.API_KEY || 'sk_test_local_dev_key';
const WALLETS = parseInt(__ENV.WALLETS || '20', 10);
const INITIAL_BALANCE = parseInt(__ENV.INITIAL_BALANCE || '100000000', 10);

const transfersOk = new Counter('transfers_ok');
const transfersRejected = new Counter('transfers_rejected_422');
const transfersUnexpected = new Counter('transfers_unexpected');

export const options = {
    scenarios: {
        transfers: {
            executor: 'constant-vus',
            vus: parseInt(__ENV.VUS || '50', 10),
            duration: __ENV.DURATION || '60s',
        },
    },
    thresholds: {
        http_req_failed: ['rate<0.01'],
        http_req_duration: ['p(95)<500'],
        transfers_unexpected: ['count==0'],
    },
};

function headers() {
    return {
        'Content-Type': 'application/json',
        'X-Api-Key': API_KEY,
        'Idempotency-Key': uuidv4(),
    };
}

export function setup() {
    const run = uuidv4().substring(0, 8);
    const wallets = [];
    for (let i = 0; i < WALLETS; i++) {
        const created = http.post(`${BASE_URL}/v1/accounts`, JSON.stringify({
            code: `k6_${run}_${i}`, type: 'LIABILITY', currency: 'USD',
        }), { headers: headers() });
        if (created.status !== 201) {
            throw new Error(`account creation failed: ${created.status} ${created.body}`);
        }
        const id = created.json('id');
        const funded = http.post(`${BASE_URL}/v1/fundings`, JSON.stringify({
            account_id: id, amount_minor: INITIAL_BALANCE, currency: 'USD',
        }), { headers: headers() });
        if (funded.status !== 201) {
            throw new Error(`funding failed: ${funded.status} ${funded.body}`);
        }
        wallets.push(id);
    }
    return { wallets };
}

export default function (data) {
    const wallets = data.wallets;
    const from = Math.floor(Math.random() * wallets.length);
    let to = Math.floor(Math.random() * wallets.length);
    if (to === from) {
        to = (to + 1) % wallets.length;
    }
    const res = http.post(`${BASE_URL}/v1/transfers`, JSON.stringify({
        from_account_id: wallets[from],
        to_account_id: wallets[to],
        amount_minor: 1 + Math.floor(Math.random() * 1000),
        currency: 'USD',
    }), { headers: headers(), tags: { name: 'POST /v1/transfers' } });

    if (res.status === 201) {
        transfersOk.add(1);
    } else if (res.status === 422) {
        transfersRejected.add(1);
    } else {
        transfersUnexpected.add(1);
    }
    check(res, { 'status is 201 or 422': (r) => r.status === 201 || r.status === 422 });
}

export function teardown() {
    const res = http.get(`${BASE_URL}/v1/admin/reconciliation`, { headers: { 'X-Api-Key': API_KEY } });
    check(res, { 'ledger balanced after load': (r) => r.status === 200 && r.json('balanced') === true });
}
