# B13 수정 후 R2 실행 승인 명세 — 2026-11-15

실행 전 명세 상태: 사전검증 PASS / 사용자 승인 대기였음. 이후 승인된 R2 단일 실행은PASS로 완료했다.
[실제 실행 결과](B13-R2-RESULT-20261115.json)를 별도로 보존한다. 기존 최초 FAIL을 유지한다.
커밋·push하지 않았다. 원본 B/C와 실패 B13 DB를 초기화하거나 재기동하지 않았다.

## 연결 경계

- Payment: ev01-b13r2-payment-20261111, 전용 동명-data volume, 127.0.0.1:16432/payment_sim →172.29.0.2:5432.
- VAN DB: ev01-b13r2-van-20261111, 전용 동명-data volume, 127.0.0.1:16433/van_sim →172.29.0.3:5432.
- Network: ev01-b13r2-20261111. 멤버는 새 DB 두 개만 허용한다.
- system identifier는 양쪽7700965760585932837. 원본과 같은 물리 복사본이므로 container/volume/network로도 격리를 검증했다.
- 새 VAN Java PID31941, TCP29090, HTTP28081, ApplicationName=EV01B13R2_VAN.
- PID 시작시각·JDBC 설정·유일한 listener·socket/backend 수·NAT172.29.0.1을 검증했다.
- 원본 B/C4개와 실패 B13 두 개는 보호 목록에 포함하며 정지 상태와 독립 mount를 확인했다.

## 검증 결과 — 실제 Job 실행 전 기록

- 새 프로필은 recheck-20261111의 고정 이름/포트/IP만 허용한다. original 프로필의 기존 guard는 유지했다.
- 잘못된 프로필/기존 endpoint 혼용/실패 DB mount 공유/실패 container 실행/보호 목록 누락을 거부한다.
- Java 호스트 READ ONLY 연결의 실제 DB·주소·port·user·data_directory·system identifier 확인.
  정상 connection guard 통과, 잘못된 system identifier 거부 확인.
- 새 복사본 인증 설정에만 임시 비밀번호를 설정했다. 값은 출력·파일 기록하지 않고 전용 VAN 환경에만 전달했다.
  거래 원장을 포함한 public 테이블 전체 digest는 복구 전 원본 snapshot과 같다.
- 후보는 manifest의 정확한13건, 모두 PROCESSING/attempt1/amount10000이다.
- VAN APPROVED11 / row 부재2이며, 실제 NOT_FOUND 응답은 아직 관측하지 않았다.
- Task/History/Batch는 모두0건, 실제 Inquiry·신규 승인·Recovery 호출도0회다.
- 기존 증빙1241개 + 최초 B13 실패 증빙47개 SHA256 동일.
- 현재 Java 업무/격리 회귀89개 + 실행 가드9개 =98개 PASS. Python 가드37개 PASS.
- 격리 회귀는 새 Testcontainers DB·mock VAN을 사용했다. 실제 R2 대상에는 Job을 실행하지 않았다.

## 승인 대상

승인은 R2 복사본의 기존 recoveryJob 정확히1회에 한정한다. pool1/기존 timeout/Lease/Fencing/backoff를 유지한다.
11건 Payment APPROVED·Task RESOLVED,2건 PROCESSING·RETRY_WAIT, Inquiry/History13건을 기대한다.
Task/History/Batch 및 승인 확정 변경은 새 Payment 복사본에서만 허용한다.
신규 승인 요청0, VAN 전체 원장 변경0, 원본 B/C·실패 B13 변경0을 대조한다.
성공·실패·부분 완료 모두 자동 재실행하지 않으며 invocation.claim과 모든 증거를 보존한다.

private evidence의 attested.json을 B13_CONFIG로 지정하고 인증값을 메모리에서 환경으로만 전달한다.
예정 명령은 다음과 같다. 이 문서 작성 중 실행하지 않았다.

```sh
B13_ALLOW_WRITE=YES B13_CONFIG=<private-r2-evidence>/attested.json \
  caffeinate -dimsu ./gradlew --offline --no-daemon \
  -I engineering-validation/ev01/b13/launcher.gradle :payment-server:b13RecoveryLaunch
```

시작 직전에 PID·실제 JDBC·DB 상태·코드 hash·원본 해시를 다시 검사한다.
전용 VAN이나 VM이 종료되면 과거 사전검증을 재사용하지 않고 새 증거를 생성한다.
원본 B13의 직접 복구·기존 실패 DB 재사용·replay·commit/push는 이 승인에 포함하지 않는다.

## 증거 위치

로컬 private B13 recheck-20261115 디렉터리에 config/attestation, 새 JDBC 검사,
전체 public 테이블 digest, 정확한 후보 집합, 보호 파일 hash, 실행 소스 hash,98개 테스트 XML을 보존했다.
원본 증거와 개인 홈 절대경로·인증값은 이 문서에 포함하지 않는다.
