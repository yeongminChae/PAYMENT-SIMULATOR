# B13 첫 Handler 실패와 Connection 반환 경로 — 읽기 전용 분석

2026-11-07, 로컬 결과 고정 HEAD `7f4076f093be24a92862548824192aacd1fdab18` 이후 분석이다.
이 문서는 11월7일 당시 미커밋 후속 분석 기록이다. 아래 Git 상태는 그 시점의 기록이며, 11월16일 사용자가 관련 수정·분석 문서의 커밋·push를 승인했다. Production·Hikari 실제 설정·DB 데이터를 변경하지 않았다.
Recovery Job/Handler.handle/Worker/Gateway를 추가 호출하지 않았다. 최초 B13 **FAIL**은 그대로다.

## 결론과 증거 수준

최초 실패 지점으로 가장 강하게 뒷받침되는 경로는 `finalizeApproval`의 REQUIRED 트랜잭션 시작이다.
Process Step의 NOT_SUPPORTED가 물리 트랜잭션은 없애지만 기본 ALWAYS synchronization은 유지한다.
그 안에서 Handler의 MyBatis SELECT가 SqlSession과 Connection C1을 묶어 두고,
새 REQUIRED 트랜잭션은 C1을 반환하거나 재사용하지 못한 채 C2를 요구한다. 하네스 풀1개에서는 대기한다.

실제 실패 로그에 남은 스택은 그 뒤 `unknownFailure`의 트랜잭션 생성 실패다.
원래 첫 Handler 예외 스택은 복구할 수 없었다. 아래 구조 재현에서 첫 예외와 후속 예외를 각각 기록했지만,
그 재현 스택이나 backend PID를 과거 실행에서 관측한 값으로 표시하지 않는다.

| 구분 | 확인 내용 |
|---|---|
| 실제 실행 관측 | Task claim 완료, Handler SELECT, Inquiry SUCCESS/APPROVED, 최종 unknownFailure의 CannotCreateTransactionException, 풀 total1/active1/idle0·30.008초 대기 |
| 실제 DB 관측 | Payment13건 PROCESSING 불변, Task RUNNING1/PENDING12, 미종료 History1, 실제 Job1회FAILED |
| 오프라인 검증 | 저장된 응답으로 실제 Handler의 응답 identity 검증 함수와 update-param factory가 통과, APPROVED 값 생성. Handler.handle은 호출하지 않음 |
| 코드·의존성 및 재현 | 동일 Spring/MyBatis/Hikari 버전에서 C1 보유→Finalizer 진입 전 C2 대기→실패 기록도 C2 대기→바깥 scope 종료 후 C1 반환 |
| 높은 신뢰도의 추론 | 첫 예외는 finalizeApproval의 doBegin에서 발생했을 가능성이 가장 높음. 실제 첫 스택이 없는 한 직접 관측으로 격상하지 않음 |
| 입증하지 않은 것 | 풀2개 실제 Recovery 성공, Production 전체 결함/해결, 과거 B 풀10개 성능 정체의 원인 |

## 실제 애플리케이션 호출 경계

| 단계 | 코드 | 선언·역할 |
|---|---|---|
| 바깥 Process scope | [RecoveryBatchConfig](../../payment-server/src/main/java/com/chaeyeongmin/payment_sim/recovery/application/batch/RecoveryBatchConfig.java#L58) | NOT_SUPPORTED. synchronization 정책 자체는 지정하지 않음 |
| Worker 반복 | [RecoveryProcessTasklet](../../payment-server/src/main/java/com/chaeyeongmin/payment_sim/recovery/application/batch/RecoveryProcessTasklet.java#L28) | NO_TASK까지 반복, Worker 예외를 상위로 전파 |
| claim/History 시작 | [claimAndStart](../../payment-server/src/main/java/com/chaeyeongmin/payment_sim/recovery/application/transaction/RecoveryExecutionTransactionService.java#L45) | 별도 Spring bean의 기본 REQUIRED. 원래 실행에서 해당 TX 커밋이 확인됨 |
| Handler 조회 | [ApprovalRecoveryHandler](../../payment-server/src/main/java/com/chaeyeongmin/payment_sim/recovery/application/handler/impl/ApprovalRecoveryHandler.java#L71) | Handler에 @Transactional 없음. Repository→Mapper→SqlSessionTemplate 경로 |
| VAN 경계 | [inquiry 호출](../../payment-server/src/main/java/com/chaeyeongmin/payment_sim/recovery/application/handler/impl/ApprovalRecoveryHandler.java#L110) | 외부 I/O. 물리 TX가 없어도 앞서 빌린 Connection은 남을 수 있음 |
| 승인 확정 | [finalizeApproval](../../payment-server/src/main/java/com/chaeyeongmin/payment_sim/recovery/application/service/impl/RecoveryFinalizationServiceImpl.java#L57) | 별도 bean의 @Transactional REQUIRED. 소유권 잠금과 원장 변경 전에 doBegin 수행 |
| 예외 기록 | [Worker UNKNOWN 분기](../../payment-server/src/main/java/com/chaeyeongmin/payment_sim/recovery/application/worker/RecoveryWorker.java#L327) | 원래 예외를 던지기 전에 unknownFailure 호출 |
| 실패 History 종료 | [unknownFailure](../../payment-server/src/main/java/com/chaeyeongmin/payment_sim/recovery/application/transaction/RecoveryExecutionTransactionService.java#L169) | 동일한 기본 REQUIRED. 이 진입도 실패하면 원래 예외를 throw하는 줄까지 도달하지 못함 |

핵심 호출은 서로 다른 Spring bean을 통하므로 self-invocation으로 @Transactional이 무시되는 경로가 아니다.
최종 실제 스택에도 `RecoveryExecutionTransactionService$$SpringCGLIB...unknownFailure`가 남아 있다.
실제 concrete TransactionManager bean 이름/종류 전체는 당시 덤프하지 않았다. 관측 스택의
`DataSourceTransactionManager.doBegin`과 그 상위 구현을 추적했으며, 재현은 DataSourceTransactionManager로 수행했다.

## 실제 타임라인에서 알 수 있는 범위

- 18:55:19.614 KST: Job 시작. Discovery는199.860ms 후 완료.
- 18:55:20.099: 첫 Task claim 완료.20.100에는 Handler의 `findByPosTrxAndAttemptSeq` SELECT가 기록됨.
- 18:55:22.215→22.458: VAN Inquiry 요청→SUCCESS/APPROVED 응답.
- 이후 Finalizer 소유권 조회/원장 UPDATE의 mapper Preparing 로그는 발견되지 않았다.
- 18:56:25.277: `unknownFailure` 진입의 CannotCreateTransactionException 기록. Job은25.357에FAILED.

응답 이후 약62.819초는 재현된 두 번의 borrow 대기와 하네스 확인 시간에 부합한다.
그러나 시간 차이와 SQL 로그 부재만으로 첫 스택을 복구했다고 주장하지 않는다.
기존 런처의 Inquiry 후 identity/ledger 검사도 있으므로 원래 첫 예외 위치에 대한 제한은 남긴다.
저장된 실제 응답의 필수 identity와 status→APPROVED 변환은 별도 오프라인 검사에서 통과했다.

## SqlSession과 Connection이 실제로 닫히는 시점

분석한 정확한 runtime jar는 Spring JDBC/TX6.2.15, MyBatis3.5.19, MyBatis-Spring3.0.5,
HikariCP6.3.3, Spring Batch5.2.4다. 로컬 jar SHA-256과 `javap -c -p -l` 결과를 private 진단 증거에 보존했다.

1. `AbstractPlatformTransactionManager`의 기본 synchronization 정책은 ALWAYS다.
   NOT_SUPPORTED scope에서도 `actualTransactionActive=false`, `synchronizationActive=true`가 될 수 있다.
2. `SqlSessionUtils.registerSessionHolder`는 실제 TX 존재가 아니라 synchronization 활성 여부와
   SpringManagedTransactionFactory를 보고 SqlSessionHolder를 등록한다.
3. `SpringManagedTransaction`이 DataSourceUtils로 C1을 빌린다. Mapper 호출 종료 시
   `SqlSessionTemplate`이 부르는 `closeSqlSession`은 등록된 세션의 참조 수만 줄인다.
   이 시점에 실제 SqlSession.close와 Connection.close가 실행되는 것은 아니다.
4. 추가 TX 시작 때 `SqlSessionSynchronization.suspend`는 세션을 unbind하지만 close하지 않는다.
   `ConnectionSynchronization.suspend`도 ConnectionHolder.isOpen이면 C1을 pool에 반환하지 않는다.
5. `DataSourceTransactionManager.doBegin`은 사용할 holder가 없거나 기존 holder가 synchronized이면
   DataSource.getConnection으로 새 C2를 요구한다. 풀1개이고 C1이 계속 borrowed이면 timeout이다.
6. Process 바깥 scope의 beforeCompletion/afterCompletion에서 SqlSession이 닫히고,
   SpringManagedTransaction.close→DataSourceUtils.releaseConnection→Hikari proxy close/recycle을 통해
   C1이 반환된다. 재현6개 모두 scope 완료 후 active0·bound resource0으로 확인했다.

이는 종료 후에도 영구히 사라지는 Connection leak의 증명이 아니라, **너무 넓은 synchronization 범위로
반환이 지연되는 현상**이다. active1은 SQL이 계속 실행되거나 DB 물리 트랜잭션이 열려 있다는 뜻이 아니다.
재현의 기본 autoCommit은true이고 VAN 경계에서 실제 TX는false였다. WAL·Lock 대기 원인으로 확대하지 않는다.

## Hikari 설정의 역할과 관측 한계

| 설정 | 실제 실행 근거 | 해석 |
|---|---|---|
| maximumPoolSize | 테스트 런처가1을 명시, 실제 예외 total1/active1 | C1이 보유된 동안 새 C2를 만들 수 없음 |
| connectionTimeout | 실제 대기30.008초. 저장소/런처에 별도 값 없음, 해당 Hikari 버전 기본30,000ms | DB SQL timeout과 구분. 진단만500ms로 분리해 빠르게 재현 |
| minimumIdle | 런처가 명시하지 않음. 진단에서 기본값은 maxPoolSize와 같은1/2로 기록 | 실제 실행의 모든 effective property를 덤프한 것은 아님. idle 부족의 근본 원인과 별개 |
| autoCommit | 저장소에 별도 override없음, 해당 버전 기본true. 진단에서true 확인 | 실제 TX없음과 Connection 미점유는 같은 조건이 아님 |
| connectionInitSql | 모든 새 연결의 DB identity 검증 | identity가 맞아도 상위 SqlSession의 반환 범위 문제는 남음 |

읽기 전용 진단은 readOnly=true이며 실제 승인 확정 TX의 쓰기 동작을 모사하지 않는다.
조회·트랜잭션 시작 때의 borrow/return 구조만 검증한다. 실제 runtime 전체 Hikari property dump는
없으므로 기본값 추정과 직접 관측을 구분한다. JdbcTransactionManager를 쓰는 경우에도
이번에 추적한 doBegin은 상위 DataSourceTransactionManager 구현이며 해당 상속 관계를 bytecode로 확인했다.

## 읽기 전용 재현6개

실제 B13 Payment endpoint를 identity guard로 확인한 뒤 `SELECT pg_backend_pid()`만 실행했다.
Spring Boot/Application/Job/실제 Handler·Worker·VAN을 시작하거나 호출하지 않았다.
`@Transactional`이 붙은 진단용 bean을 TransactionInterceptor로 proxy하고 Connection borrow/close,
물리 backend PID, active 수, ThreadLocal holder와 예외 스택을 기록했다.
진단의500ms connectionTimeout은 별도 probe 설정이며 실제 런처의30초 또는 Production 설정을 바꾸지 않았다.
모든 진단용 실제 TX는 readOnly였다. 원장 변경·claim·History INSERT를 재현하는 테스트는 하지 않았다.

| 재현 | VAN 역할 경계의 active | Finalizer 역할 | 실패 기록 역할 | 의미 |
|---|---:|---|---|---|
| A ALWAYS + MyBatis + pool1 + REQUIRED | 1, SqlSession/ConnectionHolder open | doBegin timeout | 다시 doBegin timeout | 첫 예외와 후속 마스킹 경로 재현 |
| B 같은 구조 + pool2 | 1 | C2로 진입 | 호출 필요 없음 | C1은 계속 유지, 증설은 반환 경계를 고치지 않음 |
| C ON_ACTUAL_TRANSACTION + MyBatis + pool1 | 0 | 진입 | 호출 필요 없음 | 실제 TX에서는 동기화를 유지하고 empty scope의 세션 보유 제거 |
| D ALWAYS + 짧은 readOnly REQUIRED 조회 + pool1 | 0 | 진입 | 호출 필요 없음 | 조회 TX가 끝날 때 세션/Connection 반환 |
| E ALWAYS + JdbcTemplate 조회 + pool1 | 1, ConnectionHolder open=false | suspend 때 C1 반환 후 진입 | 호출 필요 없음 | 단순 JdbcTemplate 재현만으로 MyBatis 문제를 제외할 수 없음 |
| F ALWAYS + MyBatis + pool1 + REQUIRES_NEW | 1 | doBegin timeout | 다시 timeout | 전파만 REQUIRES_NEW로 바꿔도 해결되지 않음 |

B에서는 C1과 C2의 서로 다른 backend PID를 관측했고 C2가 반환돼도 active1이었다.
D에서는 짧은 조회 TX가 끝날 때 C1 반환을 관측했고 같은 physical backend를 다음 TX에서 다시 사용했다.
E에서는 MyBatis의 열린 SpringManagedTransaction이 없어 suspend 때 반환되는 차이를 확인했다.
모든 case는 예상한 실패 또는 성공을 assertion으로 확인했다. `6개 통과`는 진단 assertion 통과이며 Recovery PASS가 아니다.
진단 전후 public 테이블16개의 전체 행 digest가 동일했고 batch_job_execution은여전히1건이었다.
기존 실패 원본47개 파일은 덮어쓰지 않았다.

## 수정 후보 — 제안만, 미구현

| 후보 | 기대되는 개선 | 필요한 검토 |
|---|---|---|
| 별도 조회 service의 짧은 readOnly REQUIRED TX로 DTO 반환 | 조회용 SqlSession/Connection을 VAN 전에 닫음. pool1에서도 경계 재현 통과 | 추가 비트랜잭션 조회 유무, 승인/취소/망취소 전체 경로, 실제 JobRepository/Step wiring 회귀 |
| 배치에 한정한 ON_ACTUAL_TRANSACTION synchronization 정책 | NOT_SUPPORTED empty scope가 SqlSession을 오래 묶지 않도록 함 | 실제 TX의 rollback/callback 유지, 기존 TM 선택과 다중 TM/JobRepository qualifier, 다른 기능 영향 |
| 최초 예외를 DB 실패 기록보다 먼저 보존 | unknownFailure 실패가 원래 예외를 숨기는 문제 방지 | 비밀·payload를 제외한 원인 기록, cleanup 예외는 suppressed 등으로 분리, 기존 실패 정책 유지 |
| 풀 용량 검토 | 동시 처리에서 필요한 여유 Connection 확보 | 보조 용량 대책이다. C1 보유·긴 session cache를 없애지 못하며 실제 Recovery 성공 미검증 |

Handler 전체를 긴 @Transactional로 감싸면 VAN 중 물리 TX까지 유지할 수 있으므로 대안으로 선택하지 않는다.
REQUIRES_NEW만 추가하거나 synchronization을 전역 NEVER로 끄는 변경도 제안하지 않는다.
수동 unbind/SqlSession.close로 framework 소유 자원을 강제 해제하는 방식은 사용하지 않았다.

추가 승인 후의 회귀 테스트 후보는 다음과 같다.

- Mock VAN 경계에서 실제 TX없음·borrowed0·불필요한 bound SqlSession없음을 검사한다.
- 실제 Batch Step/JobRepository 경계와 pool1에서 동일 점유 재현 및 반환을 검사한다.
- 첫 Finalizer 예외 + 실패 기록 예외를 동시에 주입해 원래 원인과 후속 예외가 모두 남는지 검사한다.
- 새 안전한 테스트 DB에서 claim/lease/fencing·History·rollback·NOT_FOUND/backoff를 검증한다.
  이번 단계에서는 DB를 만들거나 쓰지 않았다.
- 실제13건 격리 재검증은 별도 승인·새 증거/격리 계획 후 수행한다. 현재 실패 DB/claim을 초기화하거나 재사용하지 않는다.

## 2026-11-07 당시 증거·커밋 및 남은 파일

private 로컬 `transaction-diagnosis-20261107/`에는 진단 Java source, connection-lifecycle.json,
runtime-jars.json, 전후 테이블 digest와 표준 출력/오류를 보존한다. 원본 `closeout-20261107/run-once/`와는 별도다.
개인 홈 경로·인증값·원본 snapshot/로그를 이 문서나 Git에 복사하지 않는다.

| 승인된 로컬 커밋 | 내용 |
|---|---|
| `1b030664e2bc2f606bbaa0a170b710f669010703` | test: EV-01 B13 복구 검증 보강 — 도구·테스트11개 |
| `9749487a0ca43a19ddb1d335cc49938bc52253c3` | docs: EV-01 B13 최초 복구 실패 및 최종 결과 정리 — 문서·요약5개 |
| `7f4076f093be24a92862548824192aacd1fdab18` | chore: EV-01 생성 파일 추적 정책 정리 — .gitignore1개 |

커밋 직후에는 FETCH_HEAD만 미추적이었다. 이 후속 분석 문서를 추가한 최종 Working Tree에는
FETCH_HEAD와 이 문서2개가 미추적이며, 둘 다 커밋하지 않았다. 추적 파일 수정과 staged 변경은없다.
Push/Merge·Production 변경·DB 변경·Recovery 재실행은 하지 않았다.

## 승인된 커밋별 전체 포함 파일

### 1b03066 — test: EV-01 B13 복구 검증 보강

- `engineering-validation/ev01/b13/.gitignore`
- `engineering-validation/ev01/b13/config.example.json`
- `engineering-validation/ev01/b13/isolation.py`
- `engineering-validation/ev01/b13/launcher.gradle`
- `engineering-validation/ev01/b13/targets.json`
- `engineering-validation/ev01/b13/test_host_proof.py`
- `engineering-validation/ev01/b13/test_isolation.py`
- `engineering-validation/ev01/b13/test_query.py`
- `payment-server/src/test/java/com/chaeyeongmin/payment_sim/recovery/ev01/B13GuardTest.java`
- `payment-server/src/test/java/com/chaeyeongmin/payment_sim/recovery/ev01/B13JdbcGuardIntegrationTest.java`
- `payment-server/src/test/java/com/chaeyeongmin/payment_sim/recovery/ev01/B13RecoveryLauncher.java`

### 9749487 — docs: EV-01 B13 최초 복구 실패 및 최종 결과 정리

- `engineering-validation/ev01/B13-RESULT-20261107.json`
- `engineering-validation/ev01/CLOSEOUT-20261107.md`
- `engineering-validation/ev01/FINAL-REPORT-20261029.md`
- `engineering-validation/ev01/FOLLOWUP-B13-ISOLATED-RECOVERY.md`
- `engineering-validation/ev01/b13/README.md`

### 7f4076f — chore: EV-01 생성 파일 추적 정책 정리

- `.gitignore`

현재 수정 후 R2 결과는 [최종 보고서](FINAL-REPORT-20261029.md)와 [별도 R2 결과](B13-R2-RESULT-20261115.json)를 따른다. 최초 FAIL 분석은 변경하지 않는다.
