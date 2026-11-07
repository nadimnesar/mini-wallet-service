import http from 'k6/http';
import { check } from 'k6';

export const options = {
  scenarios: {
    transfers: {
      executor: 'ramping-vus',
      startVUs: 5,
      stages: [
        { duration: '5s', target: 10 },
        { duration: '15s', target: 20 },
        { duration: '5s', target: 5 },
      ],
      gracefulRampDown: '2s',
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.02'],
    http_req_duration: ['p(95)<200'],
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
  const idx1 = Math.floor(Math.random() * wallets.length);
  let idx2 = Math.floor(Math.random() * wallets.length);
  while (idx2 === idx1) {
    idx2 = Math.floor(Math.random() * wallets.length);
  }

  const payload = JSON.stringify({
    from_phone: wallets[idx1],
    to_phone: wallets[idx2],
    amount: 100, // 1 BDT
    note: 'Load test transfer',
  });

  const res = http.post('http://localhost:8080/api/v1/transfers', payload, {
    headers: {
      'Content-Type': 'application/json',
      'Accept': 'application/json',
      'Idempotency-Key': uuidv4(),
    },
  });

  check(res, {
    'status is 201': (r) => r.status === 201,
  });
}
