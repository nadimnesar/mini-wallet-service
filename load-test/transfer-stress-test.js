import http from 'k6/http';
import { check } from 'k6';

export const options = {
  scenarios: {
    transfer_stress: {
      executor: 'ramping-vus',
      startVUs: 10,
      stages: [
        { duration: '10s', target: 50 },
        { duration: '15s', target: 80 },
        { duration: '15s', target: 120 },
        { duration: '5s', target: 10 },
      ],
      gracefulRampDown: '5s',
    },
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
    amount: 100,
    note: 'Breaking point transfer',
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
    'status is 503 (busy)': (r) => r.status === 503,
  });
}
