# B13 Mac 호스트 VAN 단발 런처

2026-11-07 Mac 로컬 사전검증 기준. 실제 B13 Recovery Job은 사용자 승인으로 **최초1회 실행 후 FAIL**, 자동 재실행하지 않았다.
과거 준비 문서의 2회/replay 절차는 이번 실행 범위가 아니다. 이 디렉터리에는 `run.py`/`compare.py`가 없으며 현재 런처만 사용한다.
[마감 점검 기록](../CLOSEOUT-20261107.md)과 [실제 거래 후속 과제](../FOLLOWUP-B13-ISOLATED-RECOVERY.md)를 함께 확인한다.

## 고정 환경

- Payment: `ev01-b13-payment`, `127.0.0.1:15432/payment_sim`, Docker `172.19.0.2`.
- VAN DB: `ev01-b13-van`, `127.0.0.1:15433/van_sim`, Docker `172.19.0.3`.
- 전용 network: `ev01-b13-net`, 멤버는 위 DB 두 개만 허용.
- VAN은 Mac 호스트 Java PID `42831`, TCP `19090`, HTTP `18081`.
- VAN JDBC ApplicationName `EV01B13_VAN`, DB에서 보이는 NAT client_addr `172.19.0.1`.
- 정지 원본 B 2개와 실행 중이어도 되는 C 2개를 명시적으로 보호 목록에 등록한다. 설정 예시의 REPLACE 항목은 현재 inspect로 확인한 실제 이름/ID로 채워야 한다. 2026-11-07에는 원본 B/C 네 컨테이너 모두 정지 상태를 확인했다. 이름으로 추정하거나 placeholder 컨테이너를 생성하지 않는다.

## 가드와 증거

`isolation.py`는 현재 Mac Docker context의 daemon ID, DB container ID/mount/network/IP/포트를 실제 조회한다. 복사본 PGDATA는 독립된 쓰기 허용 named volume이어야 한다. 원본과 공유·중첩 mount, host/PID/privileged 공유, Docker socket mount, 기본 포트/공개 bind, 외부 network 멤버를 차단한다. B는 정지 필수, C는 실행 중이어도 된다. **이 isolation.py는 C에 inspect만 수행한다.** 이번 실행의 별도 보호 감사는 정지된 C volume에 readonly helper mount로만 전후 내용 해시를 추가했다. C container에 exec/SQL/시작/종료는 하지 않았다. 원본 B mount만 network=none helper의 readonly mount로 읽어 실행 전후 파일 내용 해시를 비교한다. helper는 원본 컨테이너를 시작하지 않는다. symlink/외부 tablespace가 있으면 중단한다. 실행 중 C의 전체 무결성을 파일 해시로 판정하지 않는다.

B13 컨테이너 내부 psql은 고정 SELECT를 `BEGIN READ ONLY`로만 수행한다. Java direct JDBC 및 Spring Hikari 신규 연결은 DB명/system identifier/data_directory/서버 IP/port/user를 대조한다. 물리 복사본에서 system identifier가 같아도 컨테이너·mount·network·실제 서버 주소가 분리되어야 한다. Mac이 Colima context를 쓰면 해당 context를 그대로 사용한다; 다른 daemon으로 임의 전환하지 않는다.

호스트 증거는 PID 기동 시각, Java 실행 파일, 19090/18081의 유일한 listener 소유자, PID의 JDBC URL 설정, 15433 연결 소켓 수와 동일한 ApplicationName backend 수, backend DB/user/NAT 주소를 함께 대조한다. NAT 뒤 client_port와 Mac source port가 다를 수 있어 두 포트를 같다고 가정하지 않는다. `ps eww`의 명령/환경은 메모리에서 검증하고 저장하거나 출력하지 않는다. 비밀번호가 포함된 ps 원문을 증거로 제출하지 않는다. JDBC URL은 다음 형태만 허용한다(다른 옵션은 현재 fail closed):

`jdbc:postgresql://127.0.0.1:15433/van_sim?ApplicationName=EV01B13_VAN`

`localhost`도 VAN 프로세스 URL에 한해 허용한다. `lsof` 권한/프로세스 설정/풀 크기 증명이 불충분하면 차단한다. VAN이 재시작돼 PID나 포트·IP가 바뀌면 값만 임의로 바꾸지 말고 환경을 재식별하고 증거를 다시 생성한다. JVM 설정 파일에만 JDBC URL이 있어 ps에서 안 보이는 환경은 현재 지원하지 않는다.

런처는 증거 파일 존재만으로 통과시키지 않는다. 시작/Job 직전/각 Inquiry 전후에 실제 Docker·host 경계를 다시 읽고 최초 증거와 대조한다. 필수 원본 목록·DB identity·PID 증거가 없거나 바뀌면 중단한다. 증거는 신뢰된 로컬 실행자가 관리하는 비공개 디렉터리에 둔다.

`targets.json`은 EV-01 최종 보고서의 실제 13개 POS_TRX/attempt=1/승인11·미확인2를 고정했다. 런처 내 별도 고정 집합과도 대조하므로 임의의 다른 13개로 바꾸지 못한다. 대상의 NULL=PROCESSING, 금액10000/sequence/external 상태, VAN APPROVED11/row 부재2, 전역 Repository 후보의 정확한 집합·취소/망취소0, Task/History/Batch0을 확인한다. VAN NOT_FOUND라는 응답 자체는 향후 실제 Inquiry 때만 입증할 수 있다. 지금은 DB row 부재와 기대값을 확인하도록 구현했다.

Gateway test proxy는 Inquiry 외 승인·취소·망취소를 delegate 호출 전에 차단한다. 실제 Inquiry 응답의 대상·상태·승인번호·VAN ID와 원장을 대조한다. amount 필드는 Inquiry 응답에 없으므로 양 DB 원장끼리 확인한다. 운영 Handler/Worker/finalizer/Job은 바꾸지 않는다. Spring schema/data/Batch 초기화, Scheduler, 자동 Job은 비활성화한다. 기존 테이블이 없으면 초기화하지 않고 차단한다.

기존 recoveryJob 호출은 정확히 한 군데이며 명시적 opt-in에서만 1회 실행한다. replay 없음. evidence의 원자적 `invocation.claim` 예약과 Payment copy 세션 advisory lock으로 중복 런처를 차단한다. 실패해도 claim/증거를 지우고 재사용하지 않는다. watchdog은 10초 이상 공백/역행 시 halt(70)해 후속 확정을 막는다. 이 경우 정상 종료가 아니므로 부분 결과/lease를 보존해야 한다. retry 정책은 1/5/30분 그대로이며 단발 실행도 1분 이상 걸리면 due retry가 같은 Job 안에서 발생할 수 있다. 동일 대상의 두 번째 Inquiry는 delegate 호출 전에 차단한다. Job 자체가 1회라는 사실만으로 Inquiry가13회라고 간주하지 않는다. 첫 실행 Inquiry/History가 정확히13이 아니면 사후 검증이 실패하며 이미 commit된 작업을 되돌리지는 않는다. 실패 시 자동 재실행하지 않는다.

## Mac 사전 조사와 실행 명령

아래 명령은 재현 절차다. 사전검증은 Job/Inquiry 없이 실행할 수 있으며 **실제 쓰기 실행은 별도 사용자 승인 후**에만 진행한다. repository root에서 JDK21/Python3/Docker/lsof/ps 사용 권한이 필요하다.

1. B/C 네 컨테이너의 실제 ID와 mount를 `docker ps -a`, `docker inspect`로 확인한다. B 정지/C 실행 상태를 보존한다. C를 멈추지 않는다. B13은 이미 준비된 복사본을 사용하며 이 도구는 생성/복원/초기화하지 않는다. 원본 B 복제 출처와 복제 전후 논리 무결성 증거를 별도 보존한다.
2. config 예시를 private 경로로 복사하고 보호 목록의 REPLACE 네 항목을 정확한 이름/ID로 채운다. VAN DB user가 postgres가 아니면 `van.user`와 `tcp.dbUser` 모두 실제 값으로 맞춘다. 비밀번호는 JSON에 넣지 않는다.

```sh
cp engineering-validation/ev01/b13/config.example.json /private/path/b13-config.json
# 위 파일의 protectedContainers를 실제 조사 결과로 편집
# 아직 없는 새 출력 디렉터리. 증거를 손으로 생성/수정하지 않는다.
python3 engineering-validation/ev01/b13/isolation.py \
  /private/path/b13-config.json /private/path/b13-evidence-new
python3 engineering-validation/ev01/b13/isolation.py --check \
  /private/path/b13-evidence-new/attested.json
```

사전 도구는 Job/Inquiry를 호출하지 않는다. B13 DB에 readonly SELECT를 수행하며, 원본 B에는 readonly 파일 해시만, C에는 inspect만 수행한다. psql 인증 실패 시 통과시키지 않는다. 예시는 컨테이너 내부 loopback psql 인증이 가능한 상태를 전제로 한다. 물리 복사본의 pg_hba가 비밀번호 인증을 요구하면 비공개 인증 구성을 먼저 마련해야 한다. trust로 가드를 완화하지 않는다.

3. 컴파일/단위 테스트만 실행하려면 다음을 사용한다. Job/DB를 호출하는 테스트는 포함하지 않는다.

```sh
python3 -m unittest discover -s engineering-validation/ev01/b13 -p 'test_*.py'
./gradlew -I engineering-validation/ev01/b13/launcher.gradle \
  :payment-server:compileTestJava :payment-server:test --tests '*B13GuardTest'
```

4. **사용자가 연결 DB·대상13건·예상 변경·명령을 확인하고 실행을 승인한 경우에만** 아래 명령을 정확히1회 사용한다. 비밀번호는 승인된 비공개 인증 경로에서 환경으로 전달하며 저장소·증빙·명령행 인자로 기록하지 않는다.

```sh
export B13_CONFIG=/private/path/b13-evidence-new/attested.json
export B13_ALLOW_WRITE=YES
# private shell에서 실제 값을 설정. 비밀번호·키를 터미널 로그에 출력하지 않는다.
export B13_PAYMENT_PASSWORD B13_VAN_PASSWORD POSTGRES_PASSWORD CARD_SECRET_KEY
export LOG_DIR=/private/path/b13-evidence-new/payment-logs
caffeinate -dimsu ./gradlew --no-daemon \
  -I engineering-validation/ev01/b13/launcher.gradle :payment-server:b13RecoveryLaunch
# 성공/실패에 관계없이 원본 B 내용과 live 경계 재확인
python3 engineering-validation/ev01/b13/isolation.py --finish "$B13_CONFIG"
```

`job-1.json`, `post-1.json`, `inquiries.json`, `jdbc-identities.json`, `result.json` 등과 VAN 원본 TCP 로그를 보존한다. Java 결과만으로 외부 TCP/원본 무결성 전체 PASS를 주장하지 않는다. 실제 VAN 프로세스 로그의 Inquiry13/승인 요청0과 DB 전후 원장을 독립적으로 대조해야 한다. HTTP18081에는 요청을 보내지 않는다. 가드는 이 test launcher의 Gateway만 제한하므로 VAN에 접근하는 다른 클라이언트/수동 승인과 이미 실행 중인 C의 자체 writer까지 막지는 않는다. 실험 중 다른 writer를 배제하고 원본에 대한 운영 접근 권한을 통제해야 한다.

## 2026-11-07 사전검증

- 기존 Python24개와 JSON 여러 줄/비정상 출력/접속·실행 실패 회귀6개를 합쳐30개 통과.
- 기존 EV-01 Python9개/7개와 POS 거래번호 Node 테스트 통과.
- JDK21 컴파일, Java 가드8개와 별도 임시 PostgreSQL JDBC 통합1개 통과.
- 기존 attestation `--check`와 새 attestation 생성 통과. 원본 B 내용 해시는 과거 attestation과 동일.
- B13 컨테이너 내부 readonly snapshot: PROCESSING13, VAN APPROVED11/row 부재2, Task/History/Batch0.
- 호스트의 비밀번호 없는 접속은 인증 실패했다. 사용자 승인 후 메모리 내 인증으로 실제 호스트 direct JDBC와 Hikari READ ONLY 정상·오류 guard를 검증해 PASS했다.
- 실제 VAN PID42831, JDBC 설정/유일한19090·18081 listener/socket 수/ApplicationName/NAT backend 대조 통과.
- 위 사전검증 시점에는 Job/Inquiry0회였다. 이후 승인된 실제 실행 결과는 아래와 같다. 원본 B/C에는 Job/Inquiry/SQL을 보내지 않았다.

인증이 필요한 호스트 경로는 승인된 인증값을 메모리에서만 사용했다. 사전검증 시점의 호스트/내부 DB identity 및 논리 snapshot은 일치했다. 이 사실만으로 Job 성공을 보장하지 않는다.

## 승인된 단일 실행 결과

**FAIL**. Job1회FAILED, Payment PROCESSING13, Task RUNNING1/PENDING12, 미종료History1,
실제 Inquiry1/SUCCESS·APPROVED, 신규 승인0, VAN ledger 변경0이다.
[최종 결과](../B13-RESULT-20261107.json)와 [보고서](../FINAL-REPORT-20261029.md)를 확인한다.
원본 B/C 내용 해시·state·mount 동일을 확인했다. 실행 이후 pool/timeout/Production은 바꾸지 않았다.

실행 전에 JavaExec의 `workingDir`을 rootProject로 명시해 repo 상대 경로를 고정했고,
Job 없이 Gradle 설정 assertion을 통과했다. 실제 실패는 이후 실행된 Process Step에서 발생했다.
런처의 풀1개 환경에서 추가 트랜잭션 커넥션 획득이 실패했다. MyBatis를 포함한 SELECT-only 진단에서
풀1개 대기/풀2개 진입을 재현했지만 실제 Job의 풀2개 재검증은 하지 않았다.
현재 output의 invocation claim을 삭제하거나 동일 DB/증거 디렉터리를 재사용하지 않는다.
재실행·현재 Task 정리·원본 복구에는 별도 계획과 사용자 승인이 필요하다.
