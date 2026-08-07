# 카드결제 시뮬레이터

POS 거래번호 발급부터 카드 승인, 승인 조회, 전체 취소까지 이어지는 결제 서버 흐름을 구현한 Spring Boot 백엔드 프로젝트입니다.

단순 CRUD가 아니라 거래 정합성과 실패 상황을 다룹니다. 동일 요청 멱등성, `UNKNOWN_TIMEOUT` 미확정 거래, 중복 승인·취소 방어, 카드번호(PAN) 원문 미저장을 핵심 주제로 구현했습니다. 기본 로컬 실행은 SQLite를 사용하고, Release 3에서는 PostgreSQL 17과 Testcontainers로 동일 키 동시 요청을 재현해 DB 최종 상태와 VAN 호출 횟수를 검증했습니다.

## 핵심 문제와 해결

### 승인 멱등성과 동시성

문제: 같은 `posTrx` 승인 요청이 동시에 들어오면 여러 attempt가 생기고 VAN approve가 여러 번 호출될 수 있었습니다.

판단: 결제 승인은 같은 거래번호와 동일 payload에 대해 하나의 결과만 재사용해야 합니다. 같은 `posTrx`라도 금액이나 카드 fingerprint가 다르면 거래번호 재사용으로 차단합니다.

구현: `PAYMENT_ATTEMPT_SEQ.POS_TRX` row를 직렬화 기준으로 사용합니다. lock 획득 후 기존 attempt를 다시 조회하고, 기존 결과가 있으면 DB 결과를 재응답합니다. 신규 attempt를 만든 첫 요청만 VAN approve를 호출합니다.

검증: PostgreSQL 동시 승인 요청 20건에서 `PAYMENT_ATTEMPT` 1건, `PAYMENT_EXTERNAL_INFO` 1건, `LAST_SEQ=1`, VAN approve 1회를 확인했습니다.

### 취소 정합성

문제: 같은 원승인을 서로 다른 취소 거래번호로 동시에 취소하면 일부 요청이 `RETRY_LATER`로 끝날 수 있었습니다.

판단: 취소는 현재 취소 거래번호가 아니라 원승인 단위로 직렬화해야 합니다. 또한 취소 요청 카드가 원승인 카드와 같은지 확인해야 합니다.

구현: `originalPosTrx` 기준으로 lock을 잡고, 잠금 후 기존 취소 row를 다시 조회합니다. 최초 요청만 `PAYMENT_CANCEL`에 `PENDING`을 저장하고 VAN cancel을 호출합니다. 원승인 `PAYMENT_ATTEMPT.FINAL_STATUS`는 `APPROVED`로 유지하고, 취소 상태는 `PAYMENT_CANCEL.CANCEL_STATUS`에서 관리합니다.

검증: 동시 취소 요청 20건에서 `CANCELLED` 1건, `ALREADY_CANCELLED` 19건, `RETRY_LATER` 0건, VAN cancel 1회, `PAYMENT_CANCEL` 1건을 확인했습니다.

### UNKNOWN_TIMEOUT

문제: VAN 승인 결과가 타임아웃이면 승인 성공인지 거절인지 즉시 단정할 수 없습니다.

판단: `UNKNOWN_TIMEOUT`은 실패가 아니라 승인 결과 미확정 상태입니다. 승인 서비스는 조회 서비스를 즉시 호출하지 않고, 후속 확정은 조회(inquiry) 흐름에서만 수행합니다.

구현: 승인 요청에서는 `UNKNOWN_TIMEOUT`을 DB에 저장하고 종료합니다. 동일 승인 재요청은 VAN approve를 다시 호출하지 않고 기존 DB 결과를 반환합니다. 조회에서만 VAN 후속조회를 수행하고, 확정 결과가 오면 DB를 먼저 갱신한 뒤 저장된 row를 응답 기준으로 사용합니다.

검증: PostgreSQL 테스트에서 `UNKNOWN_TIMEOUT -> APPROVED` 확정, 확정 후 DB 재응답, VAN inquiry 재호출 방지, inquiry도 미확정일 때 DB 상태 유지를 확인했습니다.

### 카드정보 보호와 DB 정합성

문제: 결제 흐름은 카드 식별이 필요하지만 PAN 원문을 저장하면 안 됩니다. 또한 외부 VAN 응답을 그대로 반환하면 DB 상태와 API 응답이 어긋날 수 있습니다.

구현: PAN 원문은 저장하지 않고, HMAC-SHA256 기반 `cardFingerprint`와 BIN/last4 최소 정보만 저장합니다. 승인·취소·조회 확정은 조건부 UPDATE와 `RETURNING` 결과를 사용합니다. UNIQUE와 CHECK 제약으로 중복 row와 잘못된 상태값을 방어합니다.

검증: 카드 fingerprint 비교, payload 충돌, update miss 복구, 이벤트 로그 기록, DB 확정값 기준 응답을 단위 테스트와 통합 테스트로 확인했습니다.

## Release 3 검증 결과

| 검증 항목 | 실행 조건 | 확인한 결과 |
| --- | --- | --- |
| POS 거래번호 발급 | 동일 POS 20건 동시 실행 | 반환 20건, 중복 없음, `0001~0020`, 시퀀스 row 1건, 최종 sequence 20 |
| 승인 동시성 | 동일 승인 요청 20건 동시 실행 | `PAYMENT_ATTEMPT` 1건, `PAYMENT_EXTERNAL_INFO` 1건, `LAST_SEQ=1`, VAN approve 1회, 동일 attempt 재사용 |
| 취소 동시성 | 같은 원승인에 서로 다른 cancel `posTrx` 20건 동시 실행 | `CANCELLED` 1건, `ALREADY_CANCELLED` 19건, `RETRY_LATER` 0건, VAN cancel 1회, `PAYMENT_CANCEL` 1건 |
| `UNKNOWN_TIMEOUT` 복구 | 승인 미확정 후 inquiry | 동일 승인 재요청 시 VAN approve 재호출 없음, inquiry 확정 가능, 이후 DB 결과 재사용, 계속 미확정이면 DB 상태 유지 |

이 검증은 PostgreSQL 트랜잭션 환경에서 동일 키 동시 요청을 재현하고 DB 최종 상태와 VAN 호출 횟수를 확인한 것입니다. TPS나 처리 성능을 측정한 테스트는 아닙니다.

## 기술 스택

| 구분 | 기술 |
| --- | --- |
| Language | Java 21 |
| Framework | Spring Boot 3.5.9 |
| API | Spring Web, Validation |
| Persistence | MyBatis, Spring JDBC |
| Database | SQLite, PostgreSQL 17 |
| Test | JUnit 5, Mockito, Spring Boot Test, MyBatis Test, Testcontainers |
| Build | Gradle |
| Logging | Logback, MDC RequestId |

- SQLite: 기본 로컬 실행 및 기존 테스트 호환
- PostgreSQL: Release 3의 트랜잭션, row lock, 동시성 검증

## 시스템 구조

```text
POS Client
    ↓
Payment API
    ↓
Service
    ├─ Repository / MyBatis → SQLite / PostgreSQL
    └─ VanGateway → SimulatedVanGateway
```

VAN은 실제 외부 연동이 아니라 승인, 조회, 취소 결과를 재현하기 위한 `SimulatedVanGateway`입니다.

| 흐름 | 핵심 처리 |
| --- | --- |
| POS 채번 | 점포·영업일·POS 기준 시퀀스 증가 |
| 승인 | 멱등성 검증, attempt 생성, VAN 호출, 상태 확정 |
| 조회 | 확정 상태 DB 재응답, `UNKNOWN_TIMEOUT`만 VAN 조회 |
| 취소 | 원승인·카드 검증, `PENDING` 저장, VAN 취소, 상태 확정 |

## API 및 주요 테이블

| API | Method | Path | 설명 |
| --- | --- | --- | --- |
| POS 거래번호 발급 | POST | `/api/v1/pos-trx/issue` | 일반 POS 거래번호 발급 |
| POS EOT 거래번호 발급 | POST | `/api/v1/pos-trx/eot` | EOT용 POS 거래번호 발급 |
| 승인 | POST | `/api/v1/payments/approve` | 카드 승인 요청 처리 |
| 조회 | POST | `/api/v1/payments/inquiry` | 결제시도 상태 조회 |
| 취소 | POST | `/api/v1/payments/cancel` | 승인 완료 원거래 전체취소 |

| 테이블 | 역할 |
| --- | --- |
| `POS_TRX_SEQUENCE` | 점포/영업일/POS 번호 기준 거래번호 시퀀스 |
| `PAYMENT_ATTEMPT` | 승인 시도 및 최종 승인 상태 저장 |
| `PAYMENT_ATTEMPT_SEQ` | `posTrx`별 승인 attempt 순번과 직렬화 기준 row |
| `PAYMENT_EXTERNAL_INFO` | 카드 BIN/last4, 마스킹 정보, VAN 거래정보 저장 |
| `PAYMENT_CANCEL` | 취소 요청 및 취소 결과 저장 |
| `PAYMENT_EVENT_LOG` | 승인/취소 주요 이벤트 기록 |
| `BIN_CATALOG` | BIN 기반 카드사 식별 |

## 실행 방법

로컬 기본 실행은 SQLite datasource를 사용합니다. `CARD_SECRET_KEY`는 HMAC 기반 `cardFingerprint` 생성에 사용됩니다.

```powershell
$env:CARD_SECRET_KEY = "local-dev-card-fingerprint-secret-key-32bytes"
.\gradlew.bat :payment-server:bootRun
```

PostgreSQL 실행 설정은 `application-postgres.yml`에 정의되어 있습니다.

| 항목 | 값 |
| --- | --- |
| DB 이름 | `payment_sim` |
| 사용자명 | `postgres` |
| 비밀번호 | `POSTGRES_PASSWORD` 환경변수 |
| profile | `postgres` |
| schema/data | `schema-postgres.sql`, `data-postgres.sql` |

로컬 PostgreSQL에 `payment_sim` 데이터베이스가 준비되어 있어야 합니다.

```powershell
$env:CARD_SECRET_KEY = "local-dev-card-fingerprint-secret-key-32bytes"
$env:POSTGRES_PASSWORD = "<local-postgres-password>"
.\gradlew.bat :payment-server:bootRun --args="--spring.profiles.active=postgres"
```

## 테스트

Release 3 완료 시점 기준 결과:

| 항목 | 결과 |
| --- | --- |
| 전체 테스트 | 140 |
| failures | 0 |
| errors | 0 |
| skipped | 0 |
| PostgreSQL 관련 테스트 | 9 |

핵심 검증 범위:

- 승인 멱등성과 동일 거래번호 payload 충돌
- `UNKNOWN_TIMEOUT` 저장과 후속조회
- 중복 취소와 카드 동일성 검증
- 이벤트 로그 및 update miss 복구
- PostgreSQL POS 채번·승인·취소 동시성

전체 테스트:

```powershell
$env:CARD_SECRET_KEY = "local-dev-card-fingerprint-secret-key-32bytes"
.\gradlew.bat :payment-server:clean :payment-server:test
```

PostgreSQL 테스트는 Testcontainers로 Docker PostgreSQL 17 컨테이너를 실행합니다. Docker가 실행 중이어야 합니다.

```powershell
$env:CARD_SECRET_KEY = "local-dev-card-fingerprint-secret-key-32bytes"
.\gradlew.bat :payment-server:test --tests "*Postgres*"
```

## 한계와 개선 방향

- VAN은 실제 외부 VAN이 아니라 `SimulatedVanGateway` 기반 시뮬레이터입니다.
- 동일 `posTrx` 승인 요청은 VAN 호출 동안 row lock과 DB 커넥션을 점유합니다.
- 동일 키 요청이 대량 유입되면 커넥션 풀에 간접 영향을 줄 수 있습니다.
- VAN 처리는 성공했으나 DB commit 전에 프로세스가 종료되는 장애는 해결하지 않았습니다.
- `PROCESSING` 상태 선점과 외부 호출 트랜잭션 분리는 향후 개선 대상입니다.
- 현재 테스트는 실제 여러 애플리케이션 인스턴스를 띄운 분산 환경 테스트가 아닙니다.
- 부분취소, Reversal, 정산, 실제 VAN 전문 연동은 범위 밖입니다.
