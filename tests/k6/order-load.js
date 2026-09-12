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
    http_req_duration: ['p(50)<250', 'p(95)<800', 'p(99)<1500'],
  },
};

const baseUrl = __ENV.BASE_URL || 'http://host.docker.internal:8785';

export default function () {
  const unique = `${__VU}-${__ITER}-${Date.now()}`;
  const response = http.post(`${baseUrl}/api/orders`, JSON.stringify({
    userId: `load-${__VU}`,
    sku: 'LIFE-LOAD-001',
    quantity: 1,
    amount: 39.90,
  }), {
    headers: {
      'Content-Type': 'application/json',
      'Idempotency-Key': `load-${unique}`,
      'X-Trace-Id': `load-trace-${unique}`,
      'X-Client-Id': `k6-vu-${__VU}`,
    },
  });

  check(response, {
    'order API creates an order': (result) => result.status === 200 && result.json('status') === 'PENDING_PAYMENT',
  });
  sleep(0.05);
}
