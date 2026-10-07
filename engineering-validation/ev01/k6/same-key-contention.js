import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';

const baseUrl = __ENV.BASE_URL || 'http://localhost:8080';
const posTrx = __ENV.POS_TRX;
const vus = Number(__ENV.VUS || '20');
const amount = Number(__ENV.AMOUNT || '10000');

// Public test card only. Never put a real PAN in EV-01 scripts or result files.
const pan = __ENV.EV01_PAN || '4242424242424242';
const expiryYyMm = __ENV.EV01_EXPIRY_YY_MM || '2812';
const summaryPath = __ENV.SUMMARY_PATH || 'summary.json';

if (!posTrx) {
  throw new Error('POS_TRX is required. Use a fresh posTrx for every measured run.');
}

if (!Number.isInteger(vus) || vus < 1) {
  throw new Error('VUS must be a positive integer.');
}

if (!Number.isInteger(amount) || amount < 1) {
  throw new Error('AMOUNT must be a positive integer.');
}

const approved = new Counter('ev01_approved');
const processing = new Counter('ev01_processing');
const unexpectedBusinessResult = new Counter('ev01_unexpected_business_result');
const invalidResponse = new Counter('ev01_invalid_response');

export const options = {
  summaryTrendStats: ['avg', 'min', 'med', 'p(95)', 'p(99)', 'max'],
  scenarios: {
    same_key_contention: {
      executor: 'per-vu-iterations',
      vus,
      iterations: 1,
      maxDuration: '45s',
    },
  },
  thresholds: {
    // per-vu-iterations with iterations=1 must emit exactly one request per VU.
    http_reqs: [`count==${vus}`],
    http_req_failed: ['rate==0'],
    checks: ['rate==1'],
    ev01_unexpected_business_result: ['count==0'],
    ev01_invalid_response: ['count==0'],
  },
};

export default function () {
  const payload = JSON.stringify({
    posTrx,
    amount,
    card: {
      pan,
      expiryYyMm,
    },
  });

  const response = http.post(
    `${baseUrl}/api/v1/payments/approve`,
    payload,
    {
      headers: {
        'Content-Type': 'application/json',
      },
      tags: {
        ev01_phase: 'A',
        workload: 'same-key-contention',
      },
    }
  );

  let body = null;
  try {
    body = response.json();
  } catch (_) {
    invalidResponse.add(1);
  }

  const finalStatus = body?.data?.finalStatus;
  const resultCode = body?.result_code;

  if (finalStatus === 'APPROVED' && resultCode === 'OK') {
    approved.add(1);
  } else if (finalStatus === 'PROCESSING' && resultCode === 'RETRY_LATER') {
    processing.add(1);
  } else {
    unexpectedBusinessResult.add(1);
  }

  check(response, {
    'HTTP 200': (r) => r.status === 200,
    'response body parsed': () => body !== null,
    'attemptSeq is 1': () => body?.data?.attemptSeq === 1,
    'posTrx matches': () => body?.data?.posTrx === posTrx,
    'business state is expected': () =>
      (finalStatus === 'APPROVED' && resultCode === 'OK') ||
      (finalStatus === 'PROCESSING' && resultCode === 'RETRY_LATER'),
  });
}

export function handleSummary(data) {
  return {
    stdout: textSummary(data),
    [summaryPath]: JSON.stringify(data, null, 2),
  };
}

function textSummary(data) {
  const duration = data.metrics.http_req_duration?.values || {};
  const requests = data.metrics.http_reqs?.values?.count ?? 0;
  const failedRate = data.metrics.http_req_failed?.values?.rate ?? 0;
  const approvedCount = data.metrics.ev01_approved?.values?.count ?? 0;
  const processingCount = data.metrics.ev01_processing?.values?.count ?? 0;
  const unexpectedCount = data.metrics.ev01_unexpected_business_result?.values?.count ?? 0;
  const invalidCount = data.metrics.ev01_invalid_response?.values?.count ?? 0;

  return [
    '',
    'EV-01 A / same-key contention',
    `posTrx=${posTrx}, vus=${vus}`,
    `requests=${requests}, failedRate=${failedRate}`,
    `p50=${duration['med'] ?? 'n/a'}ms, p95=${duration['p(95)'] ?? 'n/a'}ms, p99=${duration['p(99)'] ?? 'n/a'}ms, max=${duration['max'] ?? 'n/a'}ms`,
    `APPROVED=${approvedCount}, PROCESSING=${processingCount}, unexpected=${unexpectedCount}, invalidResponse=${invalidCount}`,
    '',
  ].join('\n');
}
