import http from 'k6/http';
import { check } from 'k6';
import exec from 'k6/execution';

export const options = {
  scenarios: {
    wallet_create: {
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
  },
};

function uuidv4() {
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, function (c) {
    const r = (Math.random() * 16) | 0;
    const v = c === 'x' ? r : (r & 0x3) | 0x8;
    return v.toString(16);
  });
}

export default function () {
  // Guaranteed unique 11-digit phone number using global iteration counter
  const num = 10000000 + (exec.scenario.iterationInTest % 89999999);
  const phone = `015${String(num).padStart(8, '0')}`;

  const payload = JSON.stringify({
    user_id: uuidv4(),
    phone: phone,
    type: 'LIABILITY',
  });

  const res = http.post('http://localhost:8080/api/v1/wallets', payload, {
    headers: {
      'Content-Type': 'application/json',
      'Accept': 'application/json',
    },
  });

  check(res, {
    'status is 201': (r) => r.status === 201,
  });
}
