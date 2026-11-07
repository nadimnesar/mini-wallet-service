import http from 'k6/http';
import { check } from 'k6';

export const options = {
  scenarios: {
    target_rate: {
      executor: 'ramping-arrival-rate',
      startRate: 1000,
      timeUnit: '1s',
      preAllocatedVUs: 100,
      maxVUs: 600,
      stages: [
        { duration: '10s', target: 2000 },
        { duration: '15s', target: 4000 },
        { duration: '15s', target: 6000 },
        { duration: '10s', target: 1000 },
      ],
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
