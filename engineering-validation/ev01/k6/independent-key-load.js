// B의 HTTP workload만 수행한다. 번호 예약·DB 대조·최종 PASS/FAIL 집계는 wrapper/Python이 담당한다.
// wrapper가 BIZ_DATE/POS_NO_BASE/VUS/DURATION/SUMMARY_PATH를 전달한다. 실행 순서는 README를 참고한다.
import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';
import exec from 'k6/execution';
import { makePosTrx } from './independent-key-pos-trx.js';

// B단계는 wrapper가 예약한 번호 영역에서 VU별 독립 posTrx를 생성한다.
const baseUrl = __ENV.BASE_URL || 'http://localhost:8080';
const storeCd = __ENV.STORE_CD || '2376';
const bizDate = __ENV.BIZ_DATE;
const posNoBase = Number(__ENV.POS_NO_BASE);
const vus = Number(__ENV.VUS || '10');
const duration = __ENV.DURATION || '60s';
const amount = Number(__ENV.AMOUNT || '10000');
const pan = '4242424242424242';
const expiryYyMm = __ENV.EV01_EXPIRY_YY_MM || '2812';
const summaryPath = __ENV.SUMMARY_PATH || 'summary.json';

if (!bizDate || !/^\d{8}$/.test(bizDate)) throw new Error('BIZ_DATE must be yyyyMMdd.');
if (!Number.isInteger(vus) || vus < 1) throw new Error('VUS must be a positive integer.');
if (!Number.isInteger(posNoBase) || posNoBase < 0 || posNoBase + vus - 1 > 9999) {
  throw new Error('POS_NO_BASE must reserve a four-digit posNo range for every VU.');
}
if (!Number.isInteger(amount) || amount < 1) throw new Error('AMOUNT must be a positive integer.');

const approved = new Counter('ev01_approved');
// 업무 상태 분포와 HTTP 실패는 별개다. 분류 가능한 미확정 응답도 baseline 합격을 의미하지 않는다.
const declined = new Counter('ev01_declined');
const unknownTimeout = new Counter('ev01_unknown_timeout');
const processing = new Counter('ev01_processing');
const unexpected = new Counter('ev01_unexpected_business_result');
const invalidResponse = new Counter('ev01_invalid_response');

export const options = {
  summaryTrendStats: ['avg', 'min', 'med', 'p(95)', 'p(99)', 'max'],
  scenarios: {
    independent_key_load: {
      executor: 'constant-vus',
      vus,
      duration,
      gracefulStop: '15s',
      // duration 뒤에는 새 iteration을 시작하지 않고 이미 시작한 요청의 종료를 기다린다.
    },
  },
  thresholds: {
    // check는 응답 형식/상태 조합을 검사한다. 아래 Counter threshold는 APPROVED 외 baseline 결과를 거부한다.
    http_req_failed: ['rate==0'],
    checks: ['rate==1'],
    ev01_invalid_response: ['count==0'],
    ev01_unexpected_business_result: ['count==0'],
    ev01_declined: ['count==0'],
    ev01_unknown_timeout: ['count==0'],
    ev01_processing: ['count==0'],
  },
};

export default function () {
  // 0건인 상태도 summary/threshold에서 누락되지 않도록 metric을 초기화한다.
  approved.add(0); declined.add(0); unknownTimeout.add(0); processing.add(0);
  unexpected.add(0); invalidResponse.add(0);
  let posTrx;
  try { posTrx = makePosTrx(storeCd, bizDate, posNoBase, vus, __VU, __ITER); }
  catch (error) { exec.test.abort(error.message); return; }
  // 전송 직전 intent를 영구 console 파일에 기록한다. 중단 시 전송 여부는 미확정으로 취급한다.
  const startedMs = Date.now() - exec.scenario.startTime;
  console.log(JSON.stringify({ event: 'request', posTrx, attemptSeq: 1, startedMs }));
  const response = http.post(`${baseUrl}/api/v1/payments/approve`, JSON.stringify({
    // PAN은 전송 payload에서만 사용한다. console evidence에는 식별자와 상태만 기록한다.
    posTrx,
    amount,
    card: { pan, expiryYyMm },
  }), { redirects: 0, timeout: '10s', headers: { 'Content-Type': 'application/json' }, tags: { ev01_phase: 'B', workload: 'independent-key-load' } });

  let body = null;
  try { body = response.json(); } catch (_) { invalidResponse.add(1); }
  const finalStatus = body?.data?.finalStatus;
  const resultCode = body?.result_code;
  console.log(JSON.stringify({ event: 'response', posTrx, httpStatus: response.status,
    finalStatus: finalStatus || 'UNCLASSIFIED', resultCode: resultCode || 'UNCLASSIFIED',
    completedMs: Date.now() - exec.scenario.startTime }));
  if (finalStatus === 'APPROVED' && resultCode === 'OK') approved.add(1);
  else if (finalStatus === 'DECLINED' && resultCode === 'DECLINED') declined.add(1);
  else if (finalStatus === 'UNKNOWN_TIMEOUT' && resultCode === 'UNKNOWN_TIMEOUT') unknownTimeout.add(1);
  else if (finalStatus === 'PROCESSING' && resultCode === 'RETRY_LATER') processing.add(1);
  else unexpected.add(1);

  check(response, {
    'HTTP 200': (r) => r.status === 200,
    'response body parsed': () => body !== null,
    'posTrx matches': () => body?.data?.posTrx === posTrx,
    'attemptSeq is 1': () => body?.data?.attemptSeq === 1,
    'business state is classified': () =>
      (finalStatus === 'APPROVED' && resultCode === 'OK') ||
      (finalStatus === 'DECLINED' && resultCode === 'DECLINED') ||
      (finalStatus === 'UNKNOWN_TIMEOUT' && resultCode === 'UNKNOWN_TIMEOUT') ||
      (finalStatus === 'PROCESSING' && resultCode === 'RETRY_LATER'),
  });
}

export function handleSummary(data) {
  // JSON은 drain 응답까지 포함한 k6 원본이다. 측정 구간 내 처리량은 event 시각으로 별도 계산한다.
  return {
    stdout: textSummary(data),
    [summaryPath]: JSON.stringify({
      ...data,
      ev01_test_run_duration_ms: data.state?.testRunDurationMs ?? null,
      ev01_request_count: data.metrics.http_reqs?.values?.count ?? 0,
      ev01_approved_count: data.metrics.ev01_approved?.values?.count ?? 0,
      ev01_declined_count: data.metrics.ev01_declined?.values?.count ?? 0,
      ev01_unknown_timeout_count: data.metrics.ev01_unknown_timeout?.values?.count ?? 0,
      ev01_processing_count: data.metrics.ev01_processing?.values?.count ?? 0,
      ev01_unexpected_count: data.metrics.ev01_unexpected_business_result?.values?.count ?? 0,
    }, null, 2),
  };
}

function textSummary(data) {
  // 터미널 확인용 출력이다. DB 최종 승인과 TCP 호출 단일성은 이 요약만으로 판정하지 않는다.
  const durationValues = data.metrics.http_req_duration?.values || {};
  return [
    '', 'EV-01 B / independent-key load', `vus=${vus}, duration=${duration}`,
    `requests=${data.metrics.http_reqs?.values?.count ?? 0}, failedRate=${data.metrics.http_req_failed?.values?.rate ?? 0}`,
    `p50=${durationValues.med ?? 'n/a'}ms, p95=${durationValues['p(95)'] ?? 'n/a'}ms, p99=${durationValues['p(99)'] ?? 'n/a'}ms, max=${durationValues.max ?? 'n/a'}ms`,
    `APPROVED=${data.metrics.ev01_approved?.values?.count ?? 0}, DECLINED=${data.metrics.ev01_declined?.values?.count ?? 0}, UNKNOWN_TIMEOUT=${data.metrics.ev01_unknown_timeout?.values?.count ?? 0}, PROCESSING=${data.metrics.ev01_processing?.values?.count ?? 0}`,
    '',
  ].join('\n');
}
