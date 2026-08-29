# 카드결제 시뮬레이터

POS 거래번호 발급부터 카드 승인, 승인 조회, 전체 취소까지 이어지는 결제 서버 흐름을 구현한 Spring Boot 백엔드 프로젝트입니다.

단순 CRUD가 아니라 거래 정합성과 실패 상황을 다룹니다. 동일 요청 멱등성, `UNKNOWN_TIMEOUT` 미확정 거래, 중복 승인·취소 방어, 카드번호(PAN) 원문 미저장을 핵심 주제로 구현했습니다.

기본 로컬 실행은 SQLite와 내장 `SimulatedVanGateway`를 사용합니다. PostgreSQL 환경에서는 Payment Server와 VAN Simulator를 별도 애플리케이션·DB로 실행하고 TCP로 승인과 조회를 처리할 수 있습니다.

## 핵심 문제와 해결

### 승인 멱등성과 동시성

**문제:** 같은 `posTrx` 승인 요청이 동시에 들어오면 여러 attempt가 생기고 VAN approve가 여러 번 호출될 수 있었습니다.

**판단:** 결제 승인은 같은 거래번호와 동일 payload에 대해 하나의 결과만 재사용해야 합니다. 같은 `posTrx`라도 금액이나 카드 fingerprint가 다르면 거래번호 재사용으로 차단합니다.

**구현:** `PAYMENT_ATTEMPT_SEQ.POS_TRX` row를 직렬화 기준으로 사용합니다. lock 획득 후 기존 attempt를 다시 조회하고, 기존 결과가 있으면 DB 결과를 재응답합니다. 신규 attempt를 만든 첫 요청만 VAN approve를 호출합니다.

**검증:** PostgreSQL 동시 승인 요청 20건에서 `PAYMENT_ATTEMPT` 1건, `PAYMENT_EXTERNAL_INFO` 1건, `LAST_SEQ=1`, VAN approve 1회를 확인했습니다.

### 취소 정합성

**문제:** 같은 원승인을 서로 다른 취소 거래번호로 동시에 취소하면 일부 요청이 `RETRY_LATER`로 끝날 수 있었습니다.

**판단:** 취소는 현재 취소 거래번호가 아니라 원승인 단위로 직렬화해야 합니다. 또한 취소 요청 카드가 원승인 카드와 같은지 확인해야 합니다.

**구현:** `originalPosTrx` 기준으로 lock을 잡고, 잠금 후 기존 취소 row를 다시 조회합니다. 최초 요청만 `PAYMENT_CANCEL`에 `PENDING`을 저장하고 VAN cancel을 호출합니다. 원승인 `PAYMENT_ATTEMPT.FINAL_STATUS`는 `APPROVED`로 유지하고, 취소 상태는 `PAYMENT_CANCEL.CANCEL_STATUS`에서 관리합니다.

**검증:** 동시 취소 요청 20건에서 `CANCELLED` 1건, `ALREADY_CANCELLED` 19건, `RETRY_LATER` 0건, VAN cancel 1회, `PAYMENT_CANCEL` 1건을 확인했습니다.

### UNKNOWN_TIMEOUT

**문제:** VAN 승인 결과가 타임아웃이면 승인 성공인지 거절인지 즉시 단정할 수 없습니다.

외부 VAN의 승인 처리 성공과 Payment Server의 응답 수신 성공은 동일한 사건이 아닙니다. 따라서 응답을 받지 못했다는 이유만으로 승인 실패로 확정하거나 동일 승인을 다시 요청해서는 안 된다고 판단했습니다.

**판단:** `UNKNOWN_TIMEOUT`은 실패가 아니라 Payment Server가 승인 결과를 아직 확정하지 못한 상태입니다. 승인 서비스는 조회 서비스를 즉시 호출하지 않고, 후속 확정은 조회(inquiry) 흐름에서만 수행합니다.

Inquiry는 승인을 다시 실행하는 기능이 아니라 `(posTrx, attemptSeq)`를 기준으로 VAN에 이미 남아 있는 승인 원장을 조회해 Payment Server의 미확정 상태를 복구합니다.

**구현:** VAN 응답을 읽는 중 timeout이 발생하면 `UNKNOWN_TIMEOUT`을 DB에 저장하고 종료합니다. 동일 승인 재요청은 VAN approve를 다시 호출하지 않고 기존 DB 결과를 반환합니다. 조회에서만 TCP Inquiry를 호출하고, VAN 원장의 확정 결과가 오면 DB를 먼저 갱신한 뒤 저장된 row를 응답 기준으로 사용합니다.

**검증:** VAN이 승인을 commit한 뒤 응답을 유실하는 시나리오에서 Payment Server의 `UNKNOWN_TIMEOUT → APPROVED` 복구, 확정 후 DB 재응답, VAN inquiry 재호출 방지, inquiry도 미확정일 때 DB 상태 유지를 확인했습니다.

### 요청 미전송 실패와 재시도

**문제:** TX1에서 `PROCESSING` attempt를 commit한 뒤 VAN TCP 연결 자체가 실패하면 승인 요청은 VAN에 전달되지 않았지만 `PROCESSING` row가 남을 수 있었습니다. 이 상태에서 동일 payload를 재요청하면 기존 `PROCESSING`을 재사용해 VAN을 다시 호출하지 않고 `RETRY_LATER`를 반환하여 거래가 고착됩니다.

**판단:** 응답을 읽다가 발생한 timeout처럼 요청 전달 여부가 불명확한 실패와, TCP 연결 수립 단계에서 확인된 request-not-sent 실패를 구분해야 합니다.

**구현:** 실제 connection refused 재현을 통해 확인한 connect-before-send 실패만 별도 예외로 분류합니다. 이 경우 TX1에서 만든 `PROCESSING` attempt와 외부정보를 별도 트랜잭션에서 정리해 같은 payload의 재요청을 허용합니다.

sequence와 감사 이벤트는 되돌리지 않으며, 일반 `MessageHandlingException`, reset, EOF, write failure처럼 요청 전달 여부가 불명확한 오류는 정리 대상에 포함하지 않습니다. response read timeout은 기존 `UNKNOWN_TIMEOUT` 정책을 그대로 유지합니다.

**검증:** 실제 닫힌 TCP 포트의 connection refused 경로에서 `PROCESSING` attempt와 외부정보 정리, 동일 payload 재요청 시 VAN 재호출을 확인했습니다. response read timeout에서는 기존 `UNKNOWN_TIMEOUT` 처리와 Inquiry 복구 정책이 유지되는 것도 회귀 테스트로 확인했습니다.

### 카드정보 보호와 DB 정합성

**문제:** 결제 흐름은 카드 식별이 필요하지만 PAN 원문을 저장하면 안 됩니다. 또한 외부 VAN 응답을 그대로 반환하면 DB 상태와 API 응답이 어긋날 수 있습니다.

**구현:** PAN 원문은 저장하지 않고, HMAC-SHA256 기반 `cardFingerprint`와 BIN/last4 최소 정보만 저장합니다. 승인·취소·조회 확정은 조건부 UPDATE와 `RETURNING` 결과를 사용합니다. UNIQUE와 CHECK 제약으로 중복 row와 잘못된 상태값을 방어합니다.

**검증:** 카드 fingerprint 비교, payload 충돌, update miss 복구, 이벤트 로그 기록, DB 확정값 기준 응답을 단위 테스트와 통합 테스트로 확인했습니다.

## Release 3·4 검증 결과

| 검증 항목 | 실행 조건 | 확인한 결과 |
| --- | --- | --- |
| POS 거래번호 발급 | 동일 POS 20건 동시 실행 | 반환 20건, 중복 없음, `0001~0020`, 시퀀스 row 1건, 최종 sequence 20 |
| 승인 동시성 | 동일 승인 요청 20건 동시 실행 | `PAYMENT_ATTEMPT` 1건, `PAYMENT_EXTERNAL_INFO` 1건, `LAST_SEQ=1`, VAN approve 1회, 동일 attempt 재사용 |
| 취소 동시성 | 같은 원승인에 서로 다른 cancel `posTrx` 20건 동시 실행 | `CANCELLED` 1건, `ALREADY_CANCELLED` 19건, `RETRY_LATER` 0건, VAN cancel 1회, `PAYMENT_CANCEL` 1건 |
| `UNKNOWN_TIMEOUT` 복구 | 승인 미확정 후 inquiry | 동일 승인 재요청 시 VAN approve 재호출 없음, inquiry 확정 가능, 이후 DB 결과 재사용, 계속 미확정이면 DB 상태 유지 |
| 응답 유실 복구 | VAN 승인 commit 후 TCP 응답 유실 | Payment `UNKNOWN_TIMEOUT` 저장, TCP Inquiry로 VAN 원장의 `APPROVED` 확인 후 복구 |
| 연결 전 실패 | 닫힌 TCP 포트로 승인 요청 | request-not-sent 분류, `PROCESSING` attempt 정리, 동일 payload 재요청 시 VAN 재호출 |

이 검증은 PostgreSQL 트랜잭션 환경에서 동일 키 동시 요청을 재현하고 DB 최종 상태와 VAN 호출 횟수를 확인한 것입니다. TPS나 처리 성능을 측정한 테스트는 아닙니다.

## 기술 스택

| 구분 | 기술 |
| --- | --- |
| Language | Java 21 |
| Framework | Spring Boot 3.5.9 |
| API / TCP | Spring Web, Validation, Spring Integration TCP, JSON |
| Persistence | MyBatis, Spring JDBC, Spring Data JPA |
| Database | SQLite, PostgreSQL 17 |
| Test | JUnit 5, Mockito, Spring Boot Test, MyBatis Test, Testcontainers |
| Build | Gradle |
| Logging | Logback, MDC RequestId |

- SQLite: 기본 로컬 실행 및 기존 테스트 호환
- PostgreSQL: Payment/VAN 데이터 분리, 트랜잭션·row lock·동시성 검증

## 시스템 구조

```text
POS Client
    ↓
Payment Server :8080
    ├─ Repository / MyBatis → payment_sim PostgreSQL :5432
    │
    └─ VanGateway
         ↓
         TCP [4-byte length prefix][UTF-8 JSON] :9090
         ↓
       VAN Simulator :8081
         └─ Repository / JPA → van_sim PostgreSQL :5433
```

Payment Server와 VAN Simulator는 Java DTO를 공유하지 않고 독립적으로 프로토콜 계약을 구현합니다.

TCP 전문은 4-byte Big Endian payload length와 UTF-8 JSON으로 구성하며, 승인과 조회를 지원합니다. 상세 계약은 [`docs/docs/van-protocol`](docs/docs/van-protocol/README.md)에 정리되어 있습니다.

VAN Simulator의 HTTP API는 승인 결과와 응답 유실 시나리오를 설정하는 Control Plane으로 사용합니다.

승인 시 외부 TCP 호출을 DB 트랜잭션에 포함하지 않습니다.

```text
TX1
posTrx lock
→ 멱등성 판단
→ PROCESSING attempt 생성
→ commit

        ↓

VAN TCP 승인 호출
(no DB transaction)

        ↓

TX2
PROCESSING 조건부 update
→ APPROVED / DECLINED / UNKNOWN_TIMEOUT 확정
```

| 흐름 | 핵심 처리 |
| --- | --- |
| POS 채번 | 점포·영업일·POS 기준 시퀀스 증가 |
| 승인 | TX1에서 멱등성 검증·attempt 생성, 트랜잭션 밖에서 VAN 호출, TX2에서 상태 확정 |
| 조회 | 확정 상태 DB 재응답, `UNKNOWN_TIMEOUT`만 TCP Inquiry로 VAN 원장 조회 |
| 취소 | 원승인·카드 검증, `PENDING` 저장, VAN 취소, 상태 확정. 현재 TCP 취소 전문은 구현하지 않았으며 `SimulatedVanGateway`를 사용 |

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

### 기본 실행

Payment Server만 실행하면 SQLite datasource와 내장 `SimulatedVanGateway`를 사용합니다.

`CARD_SECRET_KEY`는 HMAC 기반 `cardFingerprint` 생성에 사용됩니다.

```powershell
$env:CARD_SECRET_KEY = "local-dev-card-fingerprint-secret-key-32bytes"
.\gradlew.bat :payment-server:bootRun
```

### Payment Server + VAN Simulator 실행

Docker Compose로 서로 분리된 PostgreSQL 17 인스턴스를 시작합니다.

| 항목 | 값 |
| --- | --- |
| Payment DB | `localhost:5432/payment_sim` |
| VAN DB | `localhost:5433/van_sim` |
| VAN HTTP Control Plane | `localhost:8081` |
| VAN TCP Transaction Plane | `localhost:9090` |

```powershell
docker compose up -d
```

첫 번째 터미널에서 VAN Simulator를 실행합니다.

```powershell
.\gradlew.bat :van-simulator:bootRun --args="--spring.profiles.active=postgres"
```

두 번째 터미널에서 Payment Server를 PostgreSQL·TCP 모드로 실행합니다.

```powershell
$env:CARD_SECRET_KEY = "local-dev-card-fingerprint-secret-key-32bytes"
$env:POSTGRES_PASSWORD = "postgres"
$env:PAYMENT_VAN_MODE = "tcp"

.\gradlew.bat :payment-server:bootRun --args="--spring.profiles.active=postgres"
```

macOS/Linux에서는 같은 환경변수를 셸 문법에 맞게 설정하고 `./gradlew`을 사용합니다.

호스트·포트·timeout은 `PAYMENT_VAN_TCP_*`, VAN DB 연결은 `VAN_DB_*` 환경변수로 변경할 수 있습니다.

## 테스트

Release 4 완료 시점에 Docker가 실행 중인 로컬 환경에서 확인한 결과:

| 항목 | 결과 |
| --- | ---: |
| 전체 테스트 | 223 |
| Payment Server | 161 |
| VAN Simulator | 62 |
| failures | 0 |
| errors | 0 |
| skipped | 0 |

핵심 검증 범위:

- 승인 멱등성과 동일 거래번호 payload 충돌
- `UNKNOWN_TIMEOUT` 저장과 후속조회
- 중복 취소와 카드 동일성 검증
- 이벤트 로그 및 update miss 복구
- PostgreSQL POS 채번·승인·취소 동시성
- TCP 4-byte length-prefix framing과 승인·조회 전문 validation
- VAN 승인 commit 후 response loss와 Inquiry 복구
- connect-before-send 실패 정리와 response read timeout 회귀

전체 테스트:

```powershell
$env:CARD_SECRET_KEY = "local-dev-card-fingerprint-secret-key-32bytes"
.\gradlew.bat test
```

PostgreSQL 테스트는 Testcontainers로 Docker PostgreSQL 17 컨테이너를 실행합니다. Docker가 실행 중이어야 합니다.

```powershell
$env:CARD_SECRET_KEY = "local-dev-card-fingerprint-secret-key-32bytes"
.\gradlew.bat :payment-server:test --tests "*Postgres*"
```

## 한계와 개선 방향

- VAN Simulator는 실제 외부 VAN·카드사 연동을 대체한 테스트용 애플리케이션입니다.
- TCP client는 요청마다 새 connection을 사용하는 단순 request-response 구조입니다.
- TCP Transaction Plane은 승인과 조회만 구현되어 있으며 취소는 내장 `SimulatedVanGateway`에서만 지원합니다.
- VAN 처리는 성공했으나 DB commit 전에 프로세스가 종료되는 장애는 해결하지 않았습니다.
- TX1 commit 후 VAN 호출 전에 Payment Server 프로세스가 종료되면 `PROCESSING` attempt가 남을 수 있습니다.
- connection reset, EOF, write failure처럼 요청 전달 여부가 불명확한 통신 오류에 대한 별도 복구 정책은 현재 범위에 포함하지 않았습니다.
- 현재 테스트는 실제 여러 Payment Server 애플리케이션 인스턴스를 띄운 분산 환경 테스트가 아닙니다.
- 부분취소, Reversal, 정산, 실제 VAN 전문 연동은 범위 밖입니다.