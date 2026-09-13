import http from 'k6/http';
import { check } from 'k6';
import { sleep } from 'k6';

export const options = {
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
  stages: [
    { duration: '10s', target: 5 },
    { duration: '20s', target: 20 },
    { duration: '10s', target: 0 },
  ],
  thresholds: {
    http_req_failed: ['rate<0.05'],
    http_req_duration: ['p(50)<150', 'p(95)<500', 'p(99)<1000'],
  },
};

const baseUrl = __ENV.BASE_URL || 'http://host.docker.internal:8785';

export default function () {
  const unique = `${__VU}-${__ITER}-${Date.now()}`;
  const response = http.post(`${baseUrl}/api/async-orders`, JSON.stringify({
    userId: `async-load-${__VU}`,
    sku: 'LIFE-LOAD-001',
    quantity: 1,
    amount: 39.90,
  }), {
    headers: {
      'Content-Type': 'application/json',
      'Idempotency-Key': `async-load-${unique}`,
      'X-Trace-Id': `async-load-trace-${unique}`,
    },
  });

  check(response, {
    'async entry accepts the request': (result) => result.status === 202 && result.json('status') === 'ACCEPTED',
  });
  sleep(0.05);
}
