import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter } from 'k6/metrics';

const orderUrl = __ENV.ORDER_URL || 'http://localhost:8081';
const inventoryUrl = __ENV.INVENTORY_URL || 'http://localhost:8082';
const runId = __ENV.K6_RUN_ID || 'local';
const serviceRequests = new Counter('service_instance_requests');
const logCounts = {};

function instanceOf(response) {
  return response.headers['X-Service-Instance'] || response.headers['x-service-instance'] || 'unknown';
}

function record(service, response) {
  const instance = instanceOf(response);
  serviceRequests.add(1, { service, instance });
  logCounts[service] = (logCounts[service] || 0) + 1;
  if (logCounts[service] <= 2 || logCounts[service] % 50 === 0) {
    console.log(`K6_INSTANCE service=${service} instance=${instance}`);
  }
  return instance;
}

export const options = {
  thresholds: {
    checks: ['rate>0.95'],
    http_req_failed: ['rate<0.05'],
  },
};

export default function () {
  const suffix = `${runId}-${__VU}-${__ITER}`;
  const product = http.post(`${inventoryUrl}/products`, JSON.stringify({
    sku: `K6-${suffix}`,
    name: 'k6 smoke product',
    initialStock: 2,
  }), {
    headers: {
      'Content-Type': 'application/json',
      'Idempotency-Key': `k6-product-${suffix}`,
    },
  });
  record('inventory', product);
  const productOk = check(product, { 'product created': (response) => response.status === 201 });
  if (!productOk) {
    return;
  }

  const productId = JSON.parse(product.body).productId;
  const order = http.post(`${orderUrl}/orders`, JSON.stringify({
    items: [{ productId, quantity: 1 }],
  }), {
    headers: {
      'Content-Type': 'application/json',
      'Idempotency-Key': `k6-order-${suffix}`,
    },
  });
  record('order', order);
  check(order, { 'order accepted': (response) => response.status === 202 });
  sleep(0.05);
}

export function handleSummary(data) {
  const summaryPath = __ENV.K6_SUMMARY_PATH || '/reports/k6-summary.json';
  return {
    [summaryPath]: JSON.stringify({
      runId,
      checks: data.metrics.checks,
      httpReqFailed: data.metrics.http_req_failed,
      serviceInstanceRequests: data.metrics.service_instance_requests,
    }, null, 2),
  };
}
