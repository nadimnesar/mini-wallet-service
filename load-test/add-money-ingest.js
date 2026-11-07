import http from 'k6/http';
import { check } from 'k6';

export const options = {
  scenarios: {
    add_money: {
      executor: 'ramping-vus',
      startVUs: 5,
      stages: [
        { duration: '5s', target: 20 },
        { duration: '15s', target: 50 },
        { duration: '5s', target: 5 },
      ],
      gracefulRampDown: '2s',
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
    http_req_duration: ['p(95)<250'],
  },
};

function uuidv4() {
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, function (c) {
    const r = (Math.random() * 16) | 0;
    const v = c === 'x' ? r : (r & 0x3) | 0x8;
    return v.toString(16);
  });
}

const phones = ['01712345678', '01628871060'];

export default function () {
  const phone = phones[Math.floor(Math.random() * phones.length)];
  const payload = JSON.stringify({
    phone: phone,
    amount: 1000,
    gateway_ref: uuidv4(),
    payment_method: 'VISA',
  });

  const res = http.post('http://localhost:8080/api/v1/wallets/add-money', payload, {
    headers: {
      'Content-Type': 'application/json',
      'Accept': 'application/json',
      'Idempotency-Key': uuidv4(),
    },
  });

  check(res, {
    'status is 202': (r) => r.status === 202,
  });
}
