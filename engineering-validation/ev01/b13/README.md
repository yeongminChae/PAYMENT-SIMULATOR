> 2026-11-04 변경: B13RecoveryLauncher는 이제 Job을 정확히 1회만 호출한다.
> 고정 연결은 Payment `127.0.0.1:15432/payment_sim`, VAN `127.0.0.1:15433/van_sim`, TCP `19090`이다.
> 아래 이전 2회/replay 절차와 run.py/compare.py는 이번 단발 런처와 호환되지 않으므로 실행하지 않는다.
> 이번 작업은 코드와 컴파일만 검증했으며 Job/DB 접속은 수행하지 않았다.
> 향후 명시적으로 실행할 때는 isolation.py로 별도 증거 디렉터리에 attestation을 생성하고
> B13_CONFIG, B13_ALLOW_WRITE=YES, B13_PAYMENT_PASSWORD, B13_VAN_PASSWORD, CARD_SECRET_KEY를 설정한 뒤
> `./gradlew -I engineering-validation/ev01/b13/launcher.gradle :payment-server:b13RecoveryLaunch`를 사용한다.
> 컴파일만 할 때는 `:payment-server:compileTestJava`를 사용하며 Launch 태스크를 호출하지 않는다.

# B13 격리 Recovery 실행 도구

범위: 실제 B13 복사본에서 기존 recoveryJob 2회 실행. 합성 fixture 생성·운영 코드 변경·C 가드 완화 없음.
클라우드 준비와 실제 B13 검증의 판정은 별개다. `comparison.json`이 생성되지 않으면 PASS가 아니다.

## 아키텍처와 가드

DiscoveryService는 RecoveryCandidateMapper의 전역 승인/취소/망취소 predicate를 사용한다. B13 런처는 원본 보고서의 POS_TRX+attempt 13개와 조회 결과 집합이 정확히 일치할 때만 실행한다. 원장 NULL=PROCESSING이며 UNKNOWN_TIMEOUT fixture로 바꾸지 않는다.
Worker의 claim/History TX → ApprovalRecoveryHandler의 Inquiry → FinalizationService의 잠금·claimToken·lease 확인 → Task/History 종료 TX를 그대로 사용한다. recoveryJob의 discovery/process 두 Step과 1/5/30분 retry 정책을 교체하지 않는다. 첫 실행 11 RESOLVED/2 RETRY_WAIT 및 History를 확인한 뒤 다른 JobParameters로 재실행한다. 재실행 시 이미 확정된 11건은 attempt/Task/History가 동일해야 한다. 2건은 시간이 실제로 지나면 정상 재조회할 수 있으며 승인 재전송은 허용되지 않는다. 이 도구는 retry 소진/MANUAL_REVIEW까지 기다리는 장기 시험은 수행하지 않는다.

`isolation.py`는 명시된 Docker socket에서 B/C 정지 컨테이너와 새 Payment/VAN/TCP 컨테이너를 검사한다. 컨테이너 ID·mount Source·전용 network·network 전체 멤버·loopback 전용 비표준 포트를 확인한다. 원본 공유 mount와 privileged/PID 공유를 거절한다. 원본들은 정지 상태여야 하며 도구가 정지시키지 않는다. 보호 목록은 사전 조사로 확정한 B/C 네 DB 컨테이너를 빠짐없이 지정해야 한다. 이름만 보고 B/C로 추정하지 않는다. 물리 복사본의 system_identifier 중복 자체는 오류가 아니다.

컨테이너 내부 읽기 전용 psql identity와 Java JDBC의 DB명/system_identifier/data_directory/서버 IP/서버 port/user를 대조한다. Spring Hikari의 각 신규 연결에도 같은 identity SQL 가드가 적용된다. Job 직전에 live Docker 경계를 재검증한다. VAN DB의 실제 pg_stat_activity에는 전용 ApplicationName과 TCP 앱 컨테이너 IP가 함께 있어야 한다. 빈 backend는 통과하지 않는다. VAN 앱이 풀을 아직 열지 않았다면 최초 기동 시 DB 연결을 확인하고 대기한다; 확인을 위한 승인 요청은 보내지 않는다.

Spring schema/data/Batch schema 초기화 및 자동 Job/Scheduler는 비활성화한다. 따라서 복사본에 기존 Recovery/Batch 테이블이 없으면 BLOCKED다. 원장 스키마를 임의 초기화하지 않는다. Gateway test proxy는 Inquiry 이외 메서드를 네트워크 호출 전에 거절하며 Inquiry 대상과 응답 identity·승인번호·VAN ID를 원장과 대조한다. Inquiry 응답 계약에 amount 필드는 없으므로 amount=10000은 Payment/VAN 원장끼리 확인한다.

원본 보호 해시는 원본 mount를 별도 `postgres:17` helper의 readonly/network=none mount로 읽는다. helper는 기존 컨테이너를 시작하지 않는다. symlink가 있으면 외부 tablespace/WAL 누락 위험 때문에 중단한다. 볼륨 전체 파일 내용 해시는 실행 전후 비교하며 권한·메타데이터 전체 동일성 증명과는 구분한다. Payment/VAN 각각의 복제 전/후 논리 dump 체크섬과 복제 작업 기록은 실행자가 별도로 보존해야 한다. **내용이 다른 스냅샷이나 잘못 식별한 원본을 이 도구가 정본으로 판별해 주지는 않는다.**

Java와 별도 Docker exec psql 스냅샷을 수집하고 `compare.py`가 Task/History/13건 원장/무관 attempt/VAN 전 원장/Job/replay/TCP 횟수와 응답을 대조한다. VAN ledger equality는 전후 INSERT/UPDATE/DELETE가 남지 않았음을 증명한다. 중간 INSERT 후 DELETE 같은 외부 writer는 전용 network와 정지 원본 경계로 배제하고, 별도 DB audit가 있다면 함께 보존한다. Python은 `-O` 실행을 거절한다.

## Mac 최소 실행 단계

저장소 root에서 실행한다. Java **JDK** 21, Python 3, Docker, Gradle wrapper 네트워크/캐시가 필요하다. 포트 5432/5433/9090 및 기존 compose는 사용하지 않는다.

1. B/C 원본의 컨테이너 ID·volume·network·정지 상태와 기존 증빙/예약 ledger를 읽기 전용으로 확정한다. 원본을 시작하거나 원본에서 pg_dump하지 않는다. 원본에서 이미 안전하게 얻은 복사본 또는 검증된 논리 dump 두 개만 사용한다. 원본 B Payment/VAN을 복제했다는 해시·인벤토리를 기록한다. 파일에 PAN/비밀키가 있으면 별도 비공개 경로에서 취급한다.
2. 아래 예시처럼 **새** 컨테이너/볼륨/network를 만든다. 이름이 존재하면 재사용하지 않고 중단한다. fresh volume만 사용한다. 클라우드에서도 같은 명령으로 새 격리 환경을 구성할 수 있다.

```sh
# 변수는 로컬 비공개 쉘에서 설정; 값 자체를 로그에 남기지 않는다.
export B13_PAYMENT_PASSWORD B13_VAN_PASSWORD CARD_SECRET_KEY
# 반드시 네 이름 모두 미존재인지 docker inspect/volume inspect/network inspect로 확인한다.
docker network create b13-isolated
docker volume create b13-payment-data
docker volume create b13-van-data
docker run -d --name b13-payment --network b13-isolated \
  -p 127.0.0.1:15432:5432 -e POSTGRES_DB=payment_b13 \
  -e POSTGRES_PASSWORD="$B13_PAYMENT_PASSWORD" \
  --mount type=volume,source=b13-payment-data,target=/var/lib/postgresql/data postgres:17
docker run -d --name b13-van --network b13-isolated \
  -p 127.0.0.1:15433:5432 -e POSTGRES_DB=van_b13 \
  -e POSTGRES_PASSWORD="$B13_VAN_PASSWORD" \
  --mount type=volume,source=b13-van-data,target=/var/lib/postgresql/data postgres:17
# pg_isready 성공 후, 보존된 B 논리 dump(custom format)를 새 DB에만 복원.
# --clean 금지. 실패 시 컨테이너/볼륨을 보존하고 중단.
docker exec -i b13-payment pg_restore -U postgres -d payment_b13 --no-owner --no-acl --exit-on-error < /private/path/B-payment.dump
docker exec -i b13-van pg_restore -U postgres -d van_b13 --no-owner --no-acl --exit-on-error < /private/path/B-van.dump
./gradlew :van-simulator:bootJar
```

3. VAN 앱을 별도 `b13-tcp` 컨테이너에서 실행한다. root에서 `van-simulator/build/libs/*`의 boot jar(plain 제외) 실제 경로를 지정한다. JRE21 이미지는 승인된 공급자의 고정 digest를 권장한다. 예시 `eclipse-temurin:21-jre`는 Mac CPU 아키텍처에 맞춰 pull한다.

```sh
# VAN_BOOT_JAR는 절대 경로. 이 컨테이너는 원본 network/mount를 갖지 않는다.
docker run -d --name b13-tcp --network b13-isolated \
  -p 127.0.0.1:19090:9090 \
  --mount "type=bind,source=$VAN_BOOT_JAR,target=/app/van.jar,readonly" \
  -e SPRING_PROFILES_ACTIVE=postgres -e SPRING_SQL_INIT_MODE=never \
  -e VAN_DB_URL='jdbc:postgresql://b13-van:5432/van_b13?ApplicationName=EV01B13-unique-run-id' \
  -e VAN_DB_USERNAME=postgres -e VAN_DB_PASSWORD="$B13_VAN_PASSWORD" \
  eclipse-temurin:21-jre java -jar /app/van.jar
```

복원한 논리 DB에 대한 안전한 SELECT 결과를 원본 복제 기록과 대조한 뒤 계속한다. 앱의 health/TCP readiness·VAN backend ApplicationName을 조회한다. VAN_APP JDBC/schema 설정과 image/jar 해시를 증빙에 보존한다. 애플리케이션/사용자가 다른 writer로 접근하지 않게 한다.

4. `config.example.json`을 비공개 작업 파일로 복사하고 `protectedContainers` 네 항목을 실제 조사한 B/C 이름 또는 ID로 바꾼다. 비밀번호는 config에 쓰지 않고 환경변수로만 전달한다. 이 예시는 DB의 로컬 trust 인증을 쓰는 Docker postgres 초기 기본 설정을 전제로 한다. 기존 스냅샷 인증이 password를 요구하면 docker exec psql의 PGPASSFILE을 안전하게 구성한 뒤 도구에 반영해야 하며 인증 가드를 낮추지 않는다.

```sh
python3 -m unittest discover -s engineering-validation/ev01/b13 -p 'test_*.py'
./gradlew :payment-server:test --tests 'com.chaeyeongmin.payment_sim.recovery.ev01.*'
# 디렉터리는 존재하면 안 됨. 원본 저장소·증빙 경로 밖 새 경로 사용 권장.
caffeinate -dimsu python3 engineering-validation/ev01/b13/run.py \
  /private/path/b13-config.json /private/path/b13-evidence-new
```

`run.py`는 최초 identity/원본 해시/독립 snapshot 이후 test launcher를 실행하고, 종료 후 독립 snapshot/원본 해시/TCP 로그를 수집한다. 중간 실패는 예외/비정상 종료로 남기고 PASS를 만들지 않는다. 원장 변경이 시작된 증거 디렉터리를 재사용해서 재실행하지 않는다. 실패한 복사본은 그대로 보존하고 분석한다. Java watchdog은 실행 중 10초 이상의 시계 공백/역행이면 halt(70)한다. 이는 진행 중 Job을 정상 종료한 것이 아니므로 crash/lease 증거를 보존해야 한다. caffeinate가 없는 Linux에서는 systemd-inhibit 등 호스트의 suspend 방지를 별도로 적용한다.

5. `comparison.json` PASS, Job 두 개의 COMPLETED, 원본 해시 동일, 13건별 결과, Inquiry 로그를 검토하고 기존 후속 문서의 실제 실행 결과를 추가한다. 증빙 누락·DB mismatch·예상 밖 상태·승인 요청은 즉시 중단한다. 원본 FAIL/INVALID는 그대로 둔다. 컨테이너/볼륨은 자동 삭제하지 않는다.

## 클라우드 전달 요건

클라우드에서 PostgreSQL17·JDK21 및 Testcontainers 시험이 가능하다(준비 보고서 참조). 실제 B13 전체 검증에는 checksum/출처가 있는 B Payment/VAN dump, 승인11/미확인2 상태, 원본 보존 증명, 원본 B/C 경계 증빙, 허용된 비공개 전달 방식이 필요하다. 현재 도구의 strict 보호 가드는 같은 Docker daemon에서 원본 B/C 정지 컨테이너 네 개를 요구한다. Mac 원본이 클라우드에 없으면 이 가드를 속이기 위한 placeholder 컨테이너를 만들지 않는다. 그 경우 실제 원본 보호 검증은 Mac에서 수행하고, 클라우드 실행에는 원격 원본 보존 증명을 검증하는 별도 설계/테스트가 먼저 필요하다. **복사본 dump 두 개 전달만으로 현재 도구의 전체 검증을 즉시 실행할 수 있다고 보장하지 않는다.**
