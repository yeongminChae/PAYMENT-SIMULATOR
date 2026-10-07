# EV-01 Performance & Operational Verification

EV-01은 기존 결제 정합성 설계를 변경하지 않고, 경합/부하/Recovery backlog에서의 동작을 정량 검증한다.

기준 커밋: `develop@e605eb1a118a259a0b233c1231555f610c9dfd12`

## 원칙

- Payment/VAN production code와 runtime 기본 설정은 baseline 측정 전에 변경하지 않는다.
- UNKNOWN_TIMEOUT은 실패로 간주하지 않으며 원 승인/취소/망취소를 blind replay하지 않는다.
- 성능 수치와 correctness 수치를 분리해서 기록한다.
- 한 머신에서 얻은 TPS를 production capacity나 최대 TPS로 일반화하지 않는다.
- AI는 코드 작성/분석 보조로 사용할 수 있지만, workload 생성과 pass/fail 판정은 k6/SQL 같은 deterministic tool이 담당한다.

## Phase A — Same-key contention

동일한 `posTrx`, 금액, 카드 payload를 동시에 요청해 hot-key 직렬화 비용과 멱등성을 검증한다.

기본 concurrency: 20 / 50 / 100 / 200. 500은 200 구간이 안정적일 때만 추가한다.

각 측정 구간은 새로운 `posTrx`를 사용하고 3회 반복한다. k6의 `per-vu-iterations`로 VU당 1회 요청을 발생시킨다.

### Correctness

측정 완료 후 반드시 다음을 함께 확인한다.

- PAYMENT_ATTEMPT = 1
- PAYMENT_EXTERNAL_INFO = 1
- PAYMENT_ATTEMPT_SEQ = 1 row, LAST_SEQ = 1
- PAYMENT_ATTEMPT.FINAL_STATUS = APPROVED
- VAN van_approval = 1
- VAN TCP `[van-tcp][approval][received]` 로그 = 1

마지막 항목이 중요하다. VAN ledger 1건만으로는 중복 TCP 호출이 없었다는 것을 증명할 수 없다. VAN simulator 자체가 같은 `(posTrx, attemptSeq)` 요청을 멱등 처리하기 때문이다.

### Performance evidence

k6 summary에서 request 수, p50/p95/p99, max, HTTP failure, APPROVED/PROCESSING/예상 외 상태 수를 남긴다.

DB lock wait는 별도 sampler에서 `pg_stat_activity`를 관찰한다. 이는 관찰 시점의 wait 상태 증거이지 전체 누적 lock wait duration으로 해석하지 않는다.

## 현재 파일

- `k6/same-key-contention.js`: A단계 HTTP burst
- `sql/assert-contention-payment.sql`: Payment DB correctness assertion
- `sql/assert-contention-van.sql`: VAN ledger correctness assertion

실행/metric 수집 wrapper는 다음 단계에서 추가한다.
