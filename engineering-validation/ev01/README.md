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

## A 실행 절차

사전 조건은 Payment/VAN을 PostgreSQL profile로 기동하고, Payment API와 VAN TCP가 연결되어 있으며,
`k6` 및 PostgreSQL client `psql`이 실행 경로에 있는 것이다. VAN은 파일 로그가 켜진 상태여야 한다.
아래 순서로 별도 터미널에서 baseline 서비스를 시작한다(성능용 설정 변경은 하지 않는다).

```bash
docker compose up -d
LOG_DIR="$PWD/logs" ./gradlew :van-simulator:bootRun --args='--spring.profiles.active=postgres'
CARD_SECRET_KEY='local-dev-card-fingerprint-secret-key-32bytes' POSTGRES_PASSWORD=postgres \
  PAYMENT_VAN_MODE=tcp LOG_DIR="$PWD/logs" \
  ./gradlew :payment-server:bootRun --args='--spring.profiles.active=postgres'
```

PowerShell에서는 위 환경 변수를 설정한 뒤 `./gradlew.bat`으로 같은 두 application을 실행한다.
기본 로그 위치는 저장소 루트의 `logs/van-simulator.log`이며, 다른 `LOG_DIR`로 기동했다면 실행 시
그 파일을 `--van-log-file`/`-VanLogFile`로 반드시 지정한다.

`PAYMENT_DB_URL`, `VAN_DB_URL`, `VAN_LOG_FILE` 환경 변수 또는 아래 옵션으로 로컬 환경을 지정할 수
있다. DB URL에는 비밀번호가 포함될 수 있으므로 결과의 `environment.txt`에는 URL을 기록하지 않는다.

macOS/Linux (Bash):

```bash
cd PAYMENT-SIMULATOR
bash engineering-validation/ev01/scripts/run-same-key-contention.sh \
  --concurrency 20 \
  --payment-db-url 'postgresql://postgres:postgres@localhost:5432/payment_sim' \
  --van-db-url 'postgresql://postgres:postgres@localhost:5433/van_sim' \
  --van-log-file "$PWD/logs/van-simulator.log"
```

Windows (PowerShell):

```powershell
Set-Location PAYMENT-SIMULATOR
./engineering-validation/ev01/scripts/run-same-key-contention.ps1 `
  -Concurrency 20 `
  -PaymentDbUrl 'postgresql://postgres:postgres@localhost:5432/payment_sim' `
  -VanDbUrl 'postgresql://postgres:postgres@localhost:5433/van_sim' `
  -VanLogFile "$PWD/logs/van-simulator.log"
```

각 concurrency를 3회 반복할 때는 아래처럼 실행한다. 각 반복은 fresh `posTrx`와 별도 run directory를
사용한다. 지원 값은 `20`, `50`, `100`, `200`이다.

```bash
bash engineering-validation/ev01/scripts/run-same-key-contention.sh --concurrency 50 --repeat 3
```

래퍼는 실행 전에 k6/psql, 두 DB 연결, VAN 로그 파일을 확인하고, k6 threshold/DB assertion/VAN log
assertion 중 하나라도 실패하면 즉시 non-zero로 종료한다. `--pos-trx`는 fresh 값을 명시하고 싶은 단일
run에서만 허용한다. 기본 생성 번호는 VAN protocol 형식(`dddd-yyyyMMdd-dddd-dddd`)을 따르고 Payment/VAN DB에
없는 값을 확인한 뒤 선택한다.

### 결과와 raw artifact 정책

한 번의 측정은 `engineering-validation/ev01/results/A/<run-id>/`에만 기록된다.

- `environment.txt`: 커밋, 도구 버전, 파라미터. DB URL과 PAN은 기록하지 않는다.
- `k6-summary.json`: 성능 원본(요청 수, p50/p95/p99/max, HTTP failure, APPROVED/PROCESSING/unexpected).
- `payment-assertion.txt`, `van-assertion.txt`: DB correctness assertion 결과.
- `van-log-assertion.txt`: run 직전 기준 line 이후 새 로그에서 정확한 `posTrx`의 `[van-tcp][approval][received]` 로그 수.
- `van-log-delta.txt`: 기준점 이후 새로 추가된 VAN 로그. 파일 교체/rotation/truncate 또는 prefix 변경 시 fail-fast한다.
- `db-waits.csv`: `pg_stat_activity`의 point-in-time sample(active connection/wait event/lock wait).
- `run-summary.txt`: PASS run의 wall-clock duration과 evidence 인덱스.

`results/A`의 반복 raw result는 `.gitignore`로 제외한다. 공유가 필요하면 run directory를 CI artifact,
사내 object storage 또는 검토용 압축 파일로 보관하고, 비교에 필요한 요약 수치만 별도 문서/PR에 남긴다.
`.gitkeep`만 저장소에 유지한다.

## 현재 파일

- `k6/same-key-contention.js`: A단계 HTTP burst
- `sql/assert-contention-payment.sql`: Payment DB correctness assertion
- `sql/assert-contention-van.sql`: VAN ledger correctness assertion
- `scripts/run-same-key-contention.sh`: Bash 실행 wrapper와 fail-fast orchestration
- `scripts/run-same-key-contention.ps1`: Windows PowerShell 실행 wrapper
- `scripts/sample-payment-db-waits.sh`: Bash용 `pg_stat_activity` sampler
