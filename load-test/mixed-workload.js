import http from 'k6/http';
import { check } from 'k6';
import exec from 'k6/execution';

export const options = {
  scenarios: {
    mixed: {
      executor: 'ramping-vus',
      startVUs: 20,
      stages: [
        { duration: '10s', target: 100 },
        { duration: '20s', target: 200 },
        { duration: '10s', target: 300 },
        { duration: '5s', target: 20 },
      ],
      gracefulRampDown: '5s',
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.05'],
  },
};

function uuidv4() {
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, function (c) {
    const r = (Math.random() * 16) | 0;
    const v = c === 'x' ? r : (r & 0x3) | 0x8;
    return v.toString(16);
  });
}

const wallets = [];
for (let i = 1; i <= 20; i++) {
  const num = i < 10 ? '0' + i : '' + i;
  wallets.push(`018000000${num}`);
}

export default function () {
  const rand = Math.random();

  if (rand < 0.70) {
    // 70% Balance inquiry
    const phone = wallets[Math.floor(Math.random() * wallets.length)];
    const res = http.get(`http://localhost:8080/api/v1/wallets/${phone}/balance`, {
      headers: { Accept: 'application/json' },
    });
    check(res, { 'balance ok': (r) => r.status === 200 });
  } else if (rand < 0.85) {
    // 15% Add-Money request ingestion
    const phone = wallets[Math.floor(Math.random() * wallets.length)];
    const res = http.post('http://localhost:8080/api/v1/wallets/add-money', JSON.stringify({
      phone: phone,
      amount: 1000,
      gateway_ref: uuidv4(),
      payment_method: 'VISA',
    }), {
      headers: {
        'Content-Type': 'application/json',
        'Accept': 'application/json',
        'Idempotency-Key': uuidv4(),
      },
    });
    check(res, { 'add-money ok': (r) => r.status === 202 });
  } else if (rand < 0.95) {
    // 10% Synchronous transfer
    const idx1 = Math.floor(Math.random() * wallets.length);
    let idx2 = Math.floor(Math.random() * wallets.length);
    while (idx2 === idx1) {
      idx2 = Math.floor(Math.random() * wallets.length);
    }
    const res = http.post('http://localhost:8080/api/v1/transfers', JSON.stringify({
      from_phone: wallets[idx1],
      to_phone: wallets[idx2],
      amount: 100,
      note: 'High load mixed transfer',
    }), {
      headers: {
        'Content-Type': 'application/json',
        'Accept': 'application/json',
        'Idempotency-Key': uuidv4(),
      },
    });
    check(res, { 'transfer ok': (r) => r.status === 201 });
  } else {
    // 5% Wallet creation
    const num = 20000000 + (exec.scenario.iterationInTest % 79999999);
    const phone = `016${String(num).padStart(8, '0')}`;
    const res = http.post('http://localhost:8080/api/v1/wallets', JSON.stringify({
      user_id: uuidv4(),
      phone: phone,
      type: 'LIABILITY',
    }), {
      headers: {
        'Content-Type': 'application/json',
        'Accept': 'application/json',
      },
    });
    check(res, { 'wallet create ok': (r) => r.status === 201 });
  }
}
