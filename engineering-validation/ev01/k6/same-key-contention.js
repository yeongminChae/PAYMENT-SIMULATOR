import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';

// k6에서는 실행 시 전달한 환경 변수를 __ENV로 읽는다.
// wrapper가 BASE_URL, POS_TRX, VUS 등을 주입하고, 일부 값은 로컬 실행용 기본값을 가진다.
const baseUrl = __ENV.BASE_URL || 'http://localhost:8080';
const posTrx = __ENV.POS_TRX;
const vus = Number(__ENV.VUS || '20');
const amount = Number(__ENV.AMOUNT || '10000');

// 공개 테스트 카드만 사용한다. 실제 PAN은 EV-01 script/result에 넣지 않는다.
const pan = __ENV.EV01_PAN || '4242424242424242';
const expiryYyMm = __ENV.EV01_EXPIRY_YY_MM || '2812';
const summaryPath = __ENV.SUMMARY_PATH || 'summary.json';

// 잘못된 실험 조건으로 부하를 실행하지 않도록 시작 전에 필수 입력을 검증한다.
if (!posTrx) {
  throw new Error('POS_TRX is required. Use a fresh posTrx for every measured run.');
}

if (!Number.isInteger(vus) || vus < 1) {
  throw new Error('VUS must be a positive integer.');
}

if (!Number.isInteger(amount) || amount < 1) {
  throw new Error('AMOUNT must be a positive integer.');
}

// Counter는 k6 기본 metric이 아닌 EV-01 전용 업무 결과를 직접 집계한다.
// A단계에서는 APPROVED 또는 PROCESSING만 정상 범위이며, 그 외 상태는 unexpected로 센다.
const approved = new Counter('ev01_approved');
const processing = new Counter('ev01_processing');
const unexpectedBusinessResult = new Counter('ev01_unexpected_business_result');
const invalidResponse = new Counter('ev01_invalid_response');

export const options = {
  // latency 결과에 평균/최소/p50/p95/p99/최대를 모두 남긴다.
  // med는 median이므로 p50과 같은 의미로 사용한다.
  summaryTrendStats: ['avg', 'min', 'med', 'p(95)', 'p(99)', 'max'],

  scenarios: {
    same_key_contention: {
      // VU(Virtual User)마다 iteration을 정확히 1회 수행한다.
      // 예: VUS=20이면 20명의 가상 사용자가 같은 posTrx 승인 요청을 각각 1번 보낸다.
      executor: 'per-vu-iterations',
      vus,
      iterations: 1,
      maxDuration: '45s',
    },
  },

  // threshold는 "metric을 기록"하는 데서 끝나지 않고,
  // 조건을 만족하지 않으면 k6 프로세스를 실패(exit code != 0)시키는 기준이다.
  thresholds: {
    // per-vu-iterations + iterations=1이므로 실제 HTTP 요청 수도 VU 수와 같아야 한다.
    http_reqs: [`count==${vus}`],

    // transport/HTTP 레벨 실패를 허용하지 않는다.
    http_req_failed: ['rate==0'],

    // 아래 check()에 정의한 모든 검증이 100% 성공해야 한다.
    checks: ['rate==1'],

    // APPROVED/PROCESSING 외 업무 결과 및 JSON 파싱 실패는 0건이어야 한다.
    ev01_unexpected_business_result: ['count==0'],
    ev01_invalid_response: ['count==0'],
  },
};

// k6에서 export default function은 각 VU가 실제로 수행하는 사용자 행동이다.
// 이 시나리오에서는 각 VU가 이 함수를 1회 실행한다.
export default function () {
  // Spring ApproveRequest 형식에 맞는 JSON 요청 payload를 만든다.
  const payload = JSON.stringify({
    posTrx,
    amount,
    card: {
      pan,
      expiryYyMm,
    },
  });

  // 실제 Payment HTTP API에 승인 요청을 보낸다.
  // tags는 이후 metric을 phase/workload 기준으로 식별하기 위한 라벨이다.
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

  // JSON 파싱은 check()가 자동으로 해주지 않는다.
  // response.json()으로 한 번 파싱해 body에 저장한 뒤 여러 검증에서 재사용한다.
  let body = null;
  try {
    body = response.json();
  } catch (_) {
    invalidResponse.add(1);
  }

  // optional chaining(?.)을 사용해 body/data가 없더라도 스크립트 자체가 예외로 죽지 않게 한다.
  const finalStatus = body?.data?.finalStatus;
  const resultCode = body?.result_code;

  // 동일 거래 경합 중에는 먼저 처리한 요청은 APPROVED,
  // 아직 첫 요청의 결과가 확정되기 전 같은 attempt를 본 요청은 PROCESSING/RETRY_LATER가 될 수 있다.
  if (finalStatus === 'APPROVED' && resultCode === 'OK') {
    approved.add(1);
  } else if (finalStatus === 'PROCESSING' && resultCode === 'RETRY_LATER') {
    processing.add(1);
  } else {
    unexpectedBusinessResult.add(1);
  }

  // check의 왼쪽 문자열은 사람이 결과에서 보는 "검증 이름"일 뿐이다.
  // 실제 PASS/FAIL은 오른쪽 함수가 true/false를 반환해 결정한다.
  check(response, {
    // check()의 첫 번째 인자로 response를 넘겼으므로 r에는 해당 HTTP response가 들어온다.
    'HTTP 200': (r) => r.status === 200,

    // 이 아래 검증은 위에서 파싱해 둔 body 변수를 직접 사용한다.
    'response body parsed': () => body !== null,
    'attemptSeq is 1': () => body?.data?.attemptSeq === 1,
    'posTrx matches': () => body?.data?.posTrx === posTrx,

    // HTTP 200만으로 성공을 판단하지 않고 업무 상태 조합까지 검증한다.
    'business state is expected': () =>
      (finalStatus === 'APPROVED' && resultCode === 'OK') ||
      (finalStatus === 'PROCESSING' && resultCode === 'RETRY_LATER'),
  });
}

// 모든 VU 실행이 끝난 뒤 k6가 handleSummary를 호출한다.
// 터미널용 요약과 wrapper가 보관할 JSON evidence를 동시에 만든다.
export function handleSummary(data) {
  return {
    stdout: textSummary(data),
    [summaryPath]: JSON.stringify({
      ...data,
      // k6가 측정한 전체 test run duration을 wrapper가 사용할 수 있도록 별도 필드로 남긴다.
      ev01_test_run_duration_ms: data.state?.testRunDurationMs ?? null,
    }, null, 2),
  };
}

function textSummary(data) {
  // k6 기본 metric과 위에서 정의한 custom Counter 값을 읽어 사람이 보기 쉬운 요약을 만든다.
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
