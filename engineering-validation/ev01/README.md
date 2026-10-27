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

## Phase B — Independent-key load

B단계는 모든 승인 요청에 서로 다른 `posTrx`를 사용해 신규 승인 처리량을 측정한다. k6의
`constant-vus`로 10/25/50/100/200 VU를 60초 동안 실행하고, 각 조건은 wrapper의 `--repeat 3`으로
반복한다. Smoke는 `--smoke`로 10 VU 5초만 실행한다.

```bash
bash engineering-validation/ev01/scripts/run-independent-key-load.sh --vus 10 --repeat 3
bash engineering-validation/ev01/scripts/run-independent-key-load.sh --smoke
```

각 VU는 별도 `posNo`를 사용하고 iteration마다 sequence를 증가시킨다. 사용 범위는 `0001..9999`이며
초과 시 전체 k6 실행을 명시적으로 중단한다. 아래 B 실행 전 조건과 evidence 해석을 함께 확인한다.

B 결과는 `results/B/<run-id>/`에 저장하며, `k6-summary.json`, `run-summary.txt`, SQL assertion,
`van-log-assertion.txt`, `db-waits.csv`, `system-resources.csv`, `environment.txt`를 포함한다.
HTTP req/s와 APPROVED transaction/s는 별도 값이며, 고정 VU에서 응답시간 증가가 신규 요청률을 낮출
수 있다는 한계를 함께 해석한다. VU 수를 TPS나 운영 수용량으로 표현하지 않는다.

### B 감사 반영: 실행 전 조건과 예약 범위

- `bash`, `python3`(표준 라이브러리만 사용), `k6`, `psql`, `docker`가 필요하다. Node는 단위검사용이다.
- Payment/VAN을 PostgreSQL/TCP baseline으로 실행하고 호스트 Java PID를 각각 `PAYMENT_JAVA_PID`,
  `VAN_JAVA_PID`로 지정한다. 서로 다른 실제 Java 프로세스 PID여야 한다.
- Docker context는 Colima를 선택하고 `payment-postgres`, `van-postgres` 컨테이너가 실행 중이어야 한다.
- 새 결과 디렉터리를 원자적으로 생성하며 기존 원본은 덮어쓰지 않는다. 기본 반복은 조건별 3회다.

예약은 `${HOME}/.payment-simulator/ev01-b-reservations/<UTC-date>/<base>/`를 원자적 `mkdir`로 만든다.
모든 VU 조건은 고정 200개 posNo 슬롯을 하나 사용해 서로 겹치지 않는다. 한 날짜에 최대 50개 run이며,
마지막 슬롯은 9800..9999다. 미사용 VU 영역도 해당 run에 예약한다. 예약은 DB 충돌이나 실행 실패 후에도
보존하고 반환하지 않는다. `EV01_RESERVATION_DIR`로 경로를 바꿀 수 있지만 같은 머신의 모든 checkout과
사용자는 **동일한 로컬 파일시스템의 동일 ledger**를 사용해야 한다. ledger 삭제/경로 변경은 과거 예약
보장을 없앤다. 다른 머신, 다른 사용자 ledger, A 또는 다른 외부 writer와는 분산 예약을 보장하지 않는다.
전용 DB와 실행 범위를 사용하고 여러 머신에서 동시에 실행하지 않는다. DB 점검 후 외부 writer가 쓰는
경쟁은 production schema 변경 없이 막을 수 없다.

슬롯 예약 후 Payment Attempt/External Info/Sequence와 VAN 원장의 **슬롯 전체**를 읽어 기존 데이터가
한 건이라도 있으면 HTTP 전송 전에 실패한다. 카드 PAN은 공개 테스트 값으로 고정하고 override하지 않는다.
payload나 응답 원문은 결과에 저장하지 않는다. DB URL은 명령 인자로 필요하지만 environment evidence에
저장하지 않으며, 증거 수집 도구는 credential이 섞일 수 있는 psql stderr를 결과에 복사하지 않는다.

### B 거래별 대조와 실패 증거

k6는 HTTP 호출 직전 `request-events.jsonl`에 posTrx/attemptSeq/시각만 기록하고, 응답 후에는 HTTP 상태와
업무 상태를 기록한다. `requests.csv`는 이 intent를 입력으로 만든다. intent 직후 프로세스 중단은 실제
전송을 증명하지 못하므로 response 부재와 HTTP count 불일치를 FAIL/미확정으로 보고한다. 재전송은 없다.
외부 강제 종료/SIGKILL·디스크 오류로 유실된 console event는 복구할 수 없으며 PASS를 주장하지 않는다.

SQL은 psql 접속 세션의 temp table에 요청 목록을 COPY하고 요청별 `posTrx+attemptSeq=1`을 대조한다.
production schema는 변경하지 않는다. 각 요청의 Attempt/External Info/Sequence/원장 개수와 순번을
검증하며 Payment/VAN 승인번호·거래번호·금액 및 정확한 TCP received 1건을 교차 확인한다.
예약 슬롯의 미요청 DB/TCP 식별자도 FAIL로 처리한다. SQL 결과는 `payment-assertion.csv`,
`van-assertion.csv`, 종합 대조는 `transaction-assertion.csv`에 남긴다. baseline은 모든 요청이
APPROVED/OK이어야 하며 DECLINED/UNKNOWN_TIMEOUT/PROCESSING/HTTP 오류는 별도 집계하고 FAIL한다.
HTTP 실패만으로 VAN 승인 미발생을 추측하지 않는다. SQL/로그 수집 실패는 `run-summary.json`의 errors와
`stage-status.txt`, `evidence-output.txt`에 남긴다. k6 non-zero 뒤에도 양쪽 DB·로그·resource 증거를
가능한 범위에서 수집하고 최종 FAIL을 판정한다. failed trial 뒤 다음 trial은 자동 실행하지 않는다.

### B 시간 및 리소스 해석

60초(또는 smoke 5초)는 신규 iteration 시작 구간이고, 그 뒤 `gracefulStop=15s`는 이미 시작한 HTTP가
끝나도록 기다리는 구간이다. HTTP timeout은 부하 도구에만 10초로 지정한다. settle 기본 2초 뒤 DB와
로그를 읽으며 application timeout/pool 설정은 변경하지 않는다. k6 percentile은 drain 완료 응답도
포함한다. `http_req_per_second`는 측정 구간 내 완료된 HTTP 수/측정 초, `approved_confirmed_per_second`는
같은 구간 내 APPROVED/OK 응답 수/측정 초다. `final_db_approved`는 settle 후 실제 Payment 승인 수이며
`final_cohort_approved_per_measurement_second`는 최종 cohort 승인 수/측정 초다. 마지막 값에는 drain이나
settle 중 확정도 들어갈 수 있어 정확한 DB commit 시각별 승인 TPS로 해석하지 않는다.

`system-resources.csv`는 지정한 호스트 Java PID 각각과 Colima PostgreSQL 두 컨테이너를 구분한다.
호스트 `ps %cpu`는 OS가 계산한 프로세스 관찰치이며 구간 CPU profiler 값이 아니다. Docker stats는 자체
샘플링 때문에 설정 interval보다 느릴 수 있다. `db-waits.csv`는 A sampler를 재사용한 wait state 표본이며
행 수를 HTTP 대기 비율이나 누적 wait 시간으로 해석하지 않는다.

Windows PowerShell wrapper는 동일 Bash 하네스를 호출하므로 Git Bash와 그 PATH의 Python 3/k6/psql이
필요하다. PowerShell의 별도 sampler/예약 구현은 제거했다. Colima 호스트는 macOS가 기준이며 Windows에서
동일 Colima/호스트 PID 조건을 만족하지 못하면 실행 전 점검이 실패한다. Windows native resource 수집은
지원하지 않는다. PowerShell 자체 실행은 이 환경에서 미검증이다.

```bash
export PAYMENT_JAVA_PID=<Payment-Java-PID>
export VAN_JAVA_PID=<VAN-Java-PID>
bash engineering-validation/ev01/scripts/run-independent-key-load.sh --smoke
# 부하 없는 검증
python3 engineering-validation/ev01/scripts/test-independent-key.py
node engineering-validation/ev01/k6/test-independent-key-pos-trx.mjs
```

## Phase C — Recovery backlog

C1은 synthetic Payment UNKNOWN_TIMEOUT + VAN APPROVED 거래 10건 Smoke 및 100/500/1000건 × 기본 3회를
지원한다. 네트워크 장애를 실제로 발생시킨 결과가 아니다. SQL은 fixture 삽입과 조회에만 사용하며 실제
복구는 test runtime의 `Ev01RecoveryLauncher`가 기존 `recoveryJob`을 실행한다. 각 invocation은
JobLauncher를 딱 한 번 호출한다. 하네스는 본 실행과 별도의 재실행 검증 invocation을 각각 한 번 호출한다.
운영 API/Java/runtime 기본 설정은 바꾸지 않는다. 추가 dependency 없이 기존 Spring Batch/test classpath와
Python 표준 라이브러리를 사용한다. Java **JDK 21**(JRE만으로는 compile 불가), Gradle 다운로드/캐시,
`psql`, Python 3, Colima 및 Docker CLI가 필요하다.

C2는 `--mixed --smoke` 10건만 지원한다. manifest에 APPROVAL APPROVED/NOT_FOUND/UNKNOWN,
CANCEL CANCELLED/NOT_FOUND, REVERSAL REVERSED/NOT_FOUND를 미리 선언한다. 성공 가능한 경우는
RESOLVED, 미해결 경우는 기존 정책의 RETRY_WAIT를 기대한다. CANCEL/REVERSAL의 참조 원승인은
별도 APPROVED fixture이며, C2는 실제 취소/망취소 요청 경로가 아닌 Inquiry/finalization 참조 관계를
검증하는 synthetic 실험이다. REVERSAL UNKNOWN은 VAN schema가 허용하지 않아 생성하지 않는다.
실제 DROP_RESPONSE fault 실험, retry 소진 MANUAL_REVIEW 유도, lease 탈취/다중 Worker 성능 실험은
현재 자동 하네스에서 지원하지 않는다. 기존 lease-fencing PostgreSQL 테스트가 소유권 상실 계약의
별도 근거이며, C Smoke만으로 그 테스트를 대신했다고 주장하지 않는다.

### 집에서 B Smoke → C Smoke → 본 측정 실행 순서

모든 명령은 저장소 루트에서 실행한다. 각 서버는 별도 터미널에 둔다. PID는 Gradle 프로세스가 아닌
실제 Java application PID를 `jps -lv` 등으로 확인한다. B와 C는 순서대로 실행하며 동시에 실행하지 않는다.

1. B 서버/DB 기동 (기존 baseline DB 사용):

```bash
docker context use colima
docker compose up -d
# 터미널 1 — 기본 VAN DB
LOG_DIR="$PWD/logs" ./gradlew :van-simulator:bootRun --args='--spring.profiles.active=postgres'
# 터미널 2 — 기본 Payment DB (로컬 테스트 키)
CARD_SECRET_KEY='local-dev-card-fingerprint-secret-key-32bytes' POSTGRES_PASSWORD=postgres \
  PAYMENT_VAN_MODE=tcp LOG_DIR="$PWD/logs" \
  ./gradlew :payment-server:bootRun --args='--spring.profiles.active=postgres'
# 터미널 3 — 실제 Java PID로 치환
export PAYMENT_JAVA_PID=12345
export VAN_JAVA_PID=12346
bash engineering-validation/ev01/scripts/run-independent-key-load.sh --smoke
```

2. 아래 B 본 측정 명령은 **C1 Smoke 통과 후 B 서버를 다시 기동한 뒤** 실행한다.
이 위치에서 바로 실행하지 않는다. 최종 실행 순서는 뒤의 「최종 감사 실행 순서」를 따른다.
Smoke 결과가 FAIL이면 원인을 확인한 뒤 진행한다. B에서 HTTP 실패를 자동 재전송하지 않는다.

```bash
for vus in 10 25 50 100 200; do
  bash engineering-validation/ev01/scripts/run-independent-key-load.sh --vus "$vus" --repeat 3 || break
done
```

3. C로 전환: 위 Payment/VAN 서버를 각각 Ctrl-C로 정상 종료한다. C는 전용 DB만 허용한다.
아래 DB 생성은 **최초 한 번** 수행하며, 이미 존재하면 오류를 확인하고 기존 C DB를 그대로 사용한다.
기존 DB를 drop/truncate/delete하는 명령은 제공하지 않는다.

```bash
docker exec payment-postgres psql -U postgres -d postgres -v ON_ERROR_STOP=1 \
  -c 'CREATE DATABASE payment_sim_ev01_c'
docker exec van-postgres psql -U postgres -d postgres -v ON_ERROR_STOP=1 \
  -c 'CREATE DATABASE van_sim_ev01_c'
# 별도 터미널 — C 전용 VAN DB로 다시 기동
export EV01_C_VAN_APPLICATION_NAME="EV01C-$(date -u +%Y%m%dT%H%M%S)-$$"
printf '%s\n' "$EV01_C_VAN_APPLICATION_NAME"   # copy this exact value to the test terminal
VAN_DB_URL="jdbc:postgresql://localhost:5433/van_sim_ev01_c?ApplicationName=${EV01_C_VAN_APPLICATION_NAME}" \
  LOG_DIR="$PWD/logs/ev01-c" ./gradlew :van-simulator:bootRun --args='--spring.profiles.active=postgres'
```

Payment 서버는 C 실행 중 기동하지 않는다. test launcher가 각 Job용 Payment JVM을 생성·종료한다.
빈 Payment C DB의 schema/Batch metadata 초기화도 test launcher의 prepare-only 경로에서 딱 한 번 수행한다.
schema가 부분 생성된 경우 자동 수선하지 않고 실패한다. VAN schema는 위 VAN 기동으로 준비한다.

```bash
export EV01_C_ALLOW_WRITE=YES
export C_PAYMENT_DB_URL='postgresql://postgres:postgres@localhost:5432/payment_sim_ev01_c'
export C_VAN_DB_URL='postgresql://postgres:postgres@localhost:5433/van_sim_ev01_c'
export EV01_C_VAN_APPLICATION_NAME='<copy the exact EV01C-... value printed/exported in the VAN terminal>'
export VAN_JAVA_PID=23456   # C 전용 VAN의 실제 Java PID로 치환
# 실행 전 확인 (JDK에는 javac가 있어야 함)
java -version
javac -version
psql "$C_PAYMENT_DB_URL" -X -c 'select current_database(),inet_server_addr(),inet_server_port()'
psql "$C_VAN_DB_URL" -X -c 'select current_database(),inet_server_addr(),inet_server_port()'
./gradlew --no-daemon -I engineering-validation/ev01/recovery-launcher.gradle :payment-server:compileTestJava
# C1 Smoke, 기존 recoveryJob 실행 + 별도 재실행 검증
bash engineering-validation/ev01/scripts/run-recovery-backlog.sh --smoke \
  --van-log "$PWD/logs/ev01-c/van-simulator.log"
# C1 본 측정은 Smoke evidence 확인 후 수행한다.
for count in 100 500 1000; do
  bash engineering-validation/ev01/scripts/run-recovery-backlog.sh --count "$count" --repeat 3 \
    --van-log "$PWD/logs/ev01-c/van-simulator.log" || break
done
# C2는 C1 이후 마지막에 수행한다. RETRY_WAIT fixture를 보존하기 때문이다.
bash engineering-validation/ev01/scripts/run-recovery-backlog.sh --mixed --smoke \
  --van-log "$PWD/logs/ev01-c/van-simulator.log"
```

### C 보호 범위, 증거 및 해석

- URL은 `localhost:5432/payment_sim_ev01_c`, `localhost:5433/van_sim_ev01_c`만 허용한다. URL query/options,
  다른 host/DB 및 미승인 쓰기는 거부한다. psqlrc/libpq 접속 override를 사용하지 않는다.
- `9376-UTCdate-posNo-seq`의 C 전용 영구 예약은 `~/.payment-simulator/ev01-c-reservations`에 둔다.
  200-posNo 슬롯을 원자적으로 예약하며 실패해도 반환하지 않는다. B의 2376 namespace/ledger는 변경하지 않는다.
  모든 fixture 식별자의 Payment/VAN 테이블·Task 충돌을 검사한다. 다른 머신 writer까지 보호하지는 않는다.
- C는 Recovery의 전역 스캔 때문에 기존 미확정 승인/취소/망취소, RESOLVED 이외 Task, 실행 중 Batch가 하나라도
  있으면 거부한다. C2 후 RETRY_WAIT가 남아 있으면 이후 C 실행도 거부한다. fixture는 자동 삭제/재등록하지 않는다.
- 원자적 C active 디렉터리와 B 프로세스 점검, 8080 포트 점유로 로컬 B 서버와 동시 실행을 거부한다.
  다른 포트·다른 머신·직접 실행한 비협조적 외부 writer까지 제어하지는 않는다. 전용 C DB를 독점 사용해야 한다.
- 두 DB에 걸친 fixture 삽입은 분산 transaction이 아니다. VAN commit 뒤 Payment 삽입이 실패할 수 있으며
  SQL/manifest/부분 snapshot을 보존하고 FAIL한다. 자동 rollback 보상이나 무제한 재시도를 하지 않는다.
- Job timeout 기본 900초, 허용 최대 3600초다. timeout 시 launcher process group을 종료하고 가능한 원본을
  수집한다. Batch STARTED/RUNNING이 남은 경우 다음 실행은 거부한다. PID/lease/Job 상태를 확인하기 전
  재실행·자동 requeue를 하지 않는다. SIGKILL/디스크 장애 후 남은 active marker도 자동 삭제하지 않는다.
  FAIL 또는 중단 후에는 `~/.payment-simulator/ev01-c-active` marker도 보존한다. 관련 JVM/Job이 종료됐음을
  직접 확인한 뒤에만 해당 빈 marker 디렉터리를 수동 `rmdir`한다. 영구 reservation ledger는 삭제하지 않는다.

`results/C/<run-id>/`에 fixture manifest/SQL, pre/post/replay/final JSON 및 거래별 SQL assertion CSV,
Job/Step ID·BatchStatus·실제 시작/종료와 duration, Job 로그, VAN checkpoint/delta,
DB wait CSV, 호스트 Payment CPU/RSS JSONL, 호스트 VAN/PostgreSQL resource CSV,
Task state sample, ownership 관찰 범위, `run-summary.json/txt`, `report.md`를 보존한다.
실패 뒤에도 양쪽 DB·로그의 수집을 시도하며 오류는 최종 summary에 기록한다. Job 준비 실패나 timeout이면
Job/Step 종료 시각이 없을 수 있으므로 해당 성능 수치는 unavailable이다.

Discovery의 실제 Repository 후보 목록과 Step 종료 시 PENDING Task를 manifest에 대조한다. Process는 단일
Worker가 수행하며, History tryNo/결과/시작·종료, 최종 상태, 소유권 반납, Payment/VAN 식별자와 원장 불변성,
Inquiry 수와 History 수, 신규 승인/취소/망취소 TCP 수신 부재를 검사한다. 짧은 RUNNING 전이는 표본에서
놓칠 수 있으며 `every_task_claim_observed=false`로 기록한다. 표본만으로 모든 claim/lease fencing을 증명하지 않는다.

Worker 처리량은 실제 Process Step 기간으로 나눈 완료 RESOLVED 수다. Gradle 빌드/JVM 기동 시간이나
두 번째 재실행 기간을 첫 Job 처리시간에 합치지 않는다. History 원본은 남기지만 p95/p99는 현재 계산하지 않는다.
`ps %cpu`와 DB wait는 관찰치이고 전체 누적 wait duration·운영 TPS·다중 Worker 용량으로 일반화하지 않는다.

```bash
# 결과 확인 및 부하 없는 회귀 검사
ls -lt engineering-validation/ev01/results/B/
ls -lt engineering-validation/ev01/results/C/
cat engineering-validation/ev01/results/C/<run-id>/run-summary.txt
python3 engineering-validation/ev01/scripts/test-recovery-backlog.py
python3 engineering-validation/ev01/scripts/test-independent-key.py
node engineering-validation/ev01/k6/test-independent-key-pos-trx.mjs
```

raw results/B/C는 Git에서 제외한다. Job 로그와 fixture SQL은 공개 테스트 자료만 담도록 작성했지만 공유 전
로컬 경로와 접속 오류의 credential 노출 여부를 다시 확인한다. A 보고서/원본/백업은 C의 입력·수정 대상이 아니다.

### 최종 감사 실행 순서

순서는 **B Smoke → C1 Smoke → B 본 측정 → C1 본 측정 → C2 Smoke**다. 위의 서버 기동/DB 생성
명령을 그대로 사용하되 단계 전환마다 Payment/VAN 서버를 Ctrl-C로 종료하고 다음 단계의 DB로 다시 기동한다.

1. baseline B Payment/VAN 기동 → 실제 `PAYMENT_JAVA_PID`/`VAN_JAVA_PID` 지정 → B `--smoke`.
2. 두 B 서버 종료 → C 전용 DB 최초 생성 → C 전용 VAN 기동 → `VAN_JAVA_PID` 재지정 → C1 `--smoke`.
3. C VAN 종료 → baseline B Payment/VAN 재기동 → 두 PID 재지정 → B `--vus 10/25/50/100/200 --repeat 3`.
4. 두 B 서버 종료 → C 전용 VAN 재기동 → `VAN_JAVA_PID` 재지정 → C1 `--count 100/500/1000 --repeat 3`.
5. 동일 C VAN에서 C2 `--mixed --smoke`. 이후 RETRY_WAIT 원본은 그대로 보존한다.

C 환경에는 `EV01_C_ALLOW_WRITE`, `C_PAYMENT_DB_URL`, `C_VAN_DB_URL`, `VAN_JAVA_PID`가 필수다.
NAT/SSH forwarding으로 VAN source port가 바뀌는 환경에서는 VAN 기동 때 만든 동일한
`EV01_C_VAN_APPLICATION_NAME`도 실행 터미널에 전달한다.
JDK 21을 선택하려면 macOS에서 `export JAVA_HOME=$(/usr/libexec/java_home -v 21)` 후
`export PATH="$JAVA_HOME/bin:$PATH"`를 실행한다. 임의 `JAVA_TOOL_OPTIONS`, `JDK_JAVA_OPTIONS`,
`SPRING_APPLICATION_JSON`, Gradle JVM override가 남아 있지 않은지 확인한다. pool/lease/timeout 성능 튜닝은 하지 않는다.
B에는 앞서 명시한 CARD_SECRET_KEY/POSTGRES_PASSWORD와 실제 Java PID 두 개가 필요하다.

C Python은 같은 psql 세션의 `current_database()`/실제 server port 확인 뒤에만 SQL을 실행한다.
Java는 Boot 초기화 전에 실제 JDBC DB를 확인하고, command-line datasource URL과 Hikari connection-init-sql로
DB 이름을 강제 검사한 뒤 Job 직전 Spring JdbcTemplate 접속도 재확인한다. 환경변수만으로 전환을 가정하지 않는다.
VAN은 `lsof`(macOS 기본 제공)의 실제 PID 연결 포트와 `pg_stat_activity.client_port/datname`을 직접
대조한다. NAT/SSH forwarding이 source port를 변환하면 VAN JDBC URL에 per-process
`ApplicationName=EV01C-...`를 지정하고 같은 값을 `EV01_C_VAN_APPLICATION_NAME`으로 전달한다.
guard는 VAN PID의 localhost:5433 socket 개수와 고유 ApplicationName backend 개수를 비교하고, 그 표식의
실제 PID 환경 포함 여부 및 모든 backend가 `van_sim_ev01_c`에 연결됐는지 확인한다. 표식 누락/불일치/
중복/개수 불일치/다른 DB가 있으면 fixture/job을 거부한다. 프로세스 환경 원문은 출력하거나 저장하지
않는다. 결과는 검증 방식과 socket/backend 식별자를 `van-db-identity.json`에 기록한다.
직접 port 매핑과 이 보강 검증 모두 증명되지 않으면 guard를 우회하지 말고 실행을 중단한다.

### 맥북으로 미커밋 변경 전달

전체 저장소나 `.git`/stash/환경변수 파일을 압축하지 않는다. 변경 파일만 검토 후 전송한다.
아래 명령은 로컬 전달용이며 staging/commit을 수행하지 않는다.

```bash
git diff --binary HEAD -- .gitignore engineering-validation/ev01/README.md > /tmp/ev01-bc-tracked.patch
git ls-files --others --exclude-standard -- engineering-validation/ev01 \
  payment-server/src/test/java/com/chaeyeongmin/payment_sim/recovery/ev01 > /tmp/ev01-bc-new-files.txt
cat /tmp/ev01-bc-new-files.txt   # 코드·.gitkeep만 있는지 확인. 결과/로그/키가 있으면 전송 중단
tar -czf /tmp/ev01-bc-new-files.tar.gz -T /tmp/ev01-bc-new-files.txt
```

맥북에서도 HEAD `58ae82b`와 기존 변경사항을 먼저 확인한다. 위 patch/archive를 별도 경로로 전달하고
`git apply --check`로 충돌 여부를 확인한 뒤 적용한다. archive는 `tar -tzf`로 경로를 검토하고 임시 폴더에
풀어 기존 파일과 비교해 복사한다. 기존 파일에 직접 덮어 풀지 않는다. stash 2개는 클라우드 작업 사본에 보존된다.

최종 감사 환경에는 JRE 21만 있어 Java 컴파일/실제 PostgreSQL·VAN 연결은 미검증이다. 집에서
`compileTestJava`와 DB identity guard가 통과하기 전 Smoke 실행 가능 판정을 하지 않는다.
