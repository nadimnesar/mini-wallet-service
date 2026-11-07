import http from 'k6/http';
import { check } from 'k6';

export const options = {
  scenarios: {
    extreme_read: {
      executor: 'ramping-vus',
      startVUs: 50,
      stages: [
        { duration: '10s', target: 200 },
        { duration: '15s', target: 400 },
        { duration: '15s', target: 500 },
        { duration: '5s', target: 50 },
      ],
      gracefulRampDown: '5s',
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.05'],
  },
};

const phones = ['01712345678', '01628871060'];

export default function () {
  const phone = phones[Math.floor(Math.random() * phones.length)];
  const res = http.get(`http://localhost:8080/api/v1/wallets/${phone}/balance`, {
    headers: { Accept: 'application/json' },
  });
  check(res, {
    'status is 200': (r) => r.status === 200,
  });
}
