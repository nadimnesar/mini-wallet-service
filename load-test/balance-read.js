import http from 'k6/http';
import { check } from 'k6';

export const options = {
  scenarios: {
    read_load: {
      executor: 'ramping-vus',
      startVUs: 10,
      stages: [
        { duration: '5s', target: 50 },
        { duration: '15s', target: 100 },
        { duration: '5s', target: 10 },
      ],
      gracefulRampDown: '2s',
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
    http_req_duration: ['p(95)<100'],
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
    'has balance': (r) => JSON.parse(r.body).balance !== undefined,
  });
}
