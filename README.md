# 카드결제 시뮬레이터

POS 업무에서 접한 결제 예외를 계기로, **중복 승인·응답 유실·동시성 충돌·장기 미확정 거래 같은 실패 상황에서 결제 서버가 거래 정합성을 어떻게 유지해야 하는지 직접 구현하고 검증한 프로젝트**입니다.

Spring Boot 기반 Payment Server와 VAN Simulator를 분리하고 PostgreSQL·TCP 환경에서 승인, 조회, 전체취소, Reversal과 미확정 거래 자동 Recovery를 구현했습니다.

> **동일 승인 요청 20건을 VAN 승인 1건으로 수렴시키고, 응답이 유실된 거래는 재승인하지 않고 Inquiry로 실제 처리 결과를 확인했습니다. 이후 Cancel/Reversal 경쟁과 자동 Recovery까지 확장해 장애와 경합 상황에서도 저장된 거래 사실을 기준으로 상태를 관리하도록 검증했습니다.**

---

## 30초 요약

| 문제 | 해결 | 검증 결과 |
|---|---|---|
| 같은 승인 요청이 동시에 여러 번 들어옴 | 같은 거래를 하나씩 처리하고 이미 처리된 결과를 재사용 | **동시 요청 20건 → VAN 승인 1회** |
| VAN은 거래를 처리했지만 응답만 유실됨 | 실패로 단정하거나 재승인하지 않고 VAN 처리 결과를 다시 조회 | **`UNKNOWN_TIMEOUT → APPROVED` 복구** |
| 같은 원승인에 취소와 보상 거래가 경쟁함 | 하나가 성공하면 다른 거래가 성공하지 못하도록 제어 | **성공 거래 최대 1건** |
| 미확정 거래나 자동 복구 작업이 오래 남음 | 실제 처리 결과를 다시 확인하고, 중단된 작업은 다른 Worker가 다시 가져가 처리하도록 설계 | **이전 Worker의 늦은 저장 차단 (`OWNERSHIP_LOST`)** |

기능 수보다 **실패·경합 상황에서도 거래 상태가 일관되게 유지되는지**를 검증하는 데 집중했습니다.

---

## Why I Built This

롯데마트·슈퍼 POS 프로젝트에서 이중 승인, 장비·통신 timeout, 반품 실패 등 거래 예외를 재현하고 로그와 요청·응답을 확인하는 업무를 경험했습니다.

이 과정에서 화면이 정상적으로 동작하는 것보다:

> **외부 시스템의 실제 처리 결과와 내부 거래 상태가 어긋났을 때 서버는 무엇을 기준으로 판단하고 복구해야 하는가?**

라는 문제에 관심을 갖게 됐습니다.

실제 VAN 승인 서버를 개발한 경험은 아니기 때문에 개인 프로젝트에서 직접 제어할 수 있는 Payment Server와 VAN Simulator를 분리하고, 정상 거래뿐 아니라 응답 유실·동시 요청·process crash 같은 실패 상황을 의도적으로 만들어 검증했습니다.

---

## Architecture

```text
POS Client
    │
    │ HTTP
    ▼
Payment Server :8080
    │
    ├─ Approval / Inquiry
    ├─ Cancel / Reversal
    ├─ Automated Recovery
    │
    ├─ MyBatis / JDBC
    │      │
    │      ▼
    │  payment_sim PostgreSQL :5432
    │
    └─ VanGateway
           │
           │ TCP
           │ [4-byte length prefix][UTF-8 JSON]
           ▼
      VAN Simulator :9090
           │
           ├─ Approval Ledger
           ├─ Cancel Ledger
           ├─ Reversal Ledger
           └─ Spring Data JPA
                  │
                  ▼
             van_sim PostgreSQL :5433
```

Payment Server와 VAN Simulator는 별도 DB를 사용하고 Java DTO를 공유하지 않습니다. 시스템 간 거래 통신은 TCP message contract를 경계로 둡니다.

- **Payment Server — MyBatis / JDBC**: row lock, conditional UPDATE, 상태 전이와 Recovery lifecycle을 명시적으로 제어합니다.
- **VAN Simulator — Spring Data JPA**: Payment DB와 분리된 Approval / Cancel / Reversal ledger를 관리합니다.
- **HTTP**: POS-facing API와 장애 시나리오 제어에 사용합니다.
- **TCP**: Approval / Inquiry / Cancel / Reversal 거래 통신에 사용합니다.

TCP payload는 `4-byte Big Endian length prefix + UTF-8 JSON`으로 구성합니다.

---

## State Model

### Payment Transaction

```text
Approval : PROCESSING → APPROVED / DECLINED / UNKNOWN_TIMEOUT

Cancel   : PENDING    → CANCELLED / CANCEL_DECLINED / UNKNOWN_TIMEOUT

Reversal : PENDING    → REVERSED / REVERSAL_DECLINED
```

이 프로젝트에서 Reversal은 승인 결과가 불확실한 `UNKNOWN_TIMEOUT` 원승인에 대해 VAN에 남아 있을 수 있는 승인 사실을 명시적으로 되돌리기 위한 보상 거래로 다룹니다.

### Recovery Task

```text
PENDING
   ↓
RUNNING
 ├─ RESOLVED
 ├─ RETRY_WAIT → RUNNING
 └─ MANUAL_REVIEW
          │
          │ Admin requeue
          ▼
        PENDING
```

**Payment Transaction 상태와 Recovery Task 상태는 서로 다른 lifecycle입니다.**

`MANUAL_REVIEW`는 거래를 임의로 승인·거절한 상태가 아니라 자동 복구만으로 외부 거래 사실을 안전하게 확정하기 어렵다는 의미입니다.

---

## Core Design

### 1. Transaction Boundary

Approval / Cancel / Reversal은 외부 VAN 통신을 DB transaction과 분리합니다.

```text
TX1
거래 직렬화
→ 멱등성 / 현재 상태 판단
→ PROCESSING / PENDING 저장
→ COMMIT

        ↓

VAN TCP Call
(no DB transaction)

        ↓

TX2
VAN 결과 조건부 반영
→ 현재 DB 상태 재확인
→ COMMIT
```

VAN이 느리거나 timeout이 발생해도 DB transaction과 row lock을 네트워크 응답 시간만큼 유지하지 않기 위한 선택입니다.

대신 TX 사이에 process가 종료되면 중간 상태가 남을 수 있으므로, 현재 저장된 거래 상태를 다시 읽고 Inquiry / Recovery로 사실을 확인합니다.

---

### 2. Idempotency & Concurrency

#### Approval

동일한 `posTrx` 요청이 동시에 여러 번 들어와도 최초 거래만 VAN Approval로 이어져야 합니다.

```text
같은 posTrx + 같은 payload
→ 기존 거래 상태/결과를 기준으로 응답

같은 posTrx + 다른 amount / cardFingerprint
→ 거래번호 재사용 conflict
```

검증:

```text
20 concurrent approval requests

Payment attempt  1
VAN Approval     1
```

#### Cancel

Cancel은 새로운 취소 거래번호가 아니라 **원승인 단위**로 경쟁을 제어합니다.

```text
20 concurrent cancel requests

CANCELLED          1
ALREADY_CANCELLED 19
VAN Cancel         1
```

Cancel과 Reversal은 각각 자신의 거래 identity에 대해 동일 payload replay와 payload conflict를 구분합니다.

---

### 3. Timeout → UNKNOWN_TIMEOUT → Inquiry

핵심 failure semantics는 다음과 같습니다.

```text
응답을 받지 못함
≠
VAN에서 거래가 실패함
```

예를 들어:

```text
VAN Approval COMMIT
        ↓
response loss
        ↓
Payment read timeout
        ↓
UNKNOWN_TIMEOUT
```

VAN에는 이미 승인이 저장되어 있을 수 있기 때문에 같은 Approval command를 다시 보내지 않습니다.

```text
UNKNOWN_TIMEOUT
        ↓
VAN Inquiry
        ↓
VAN ledger 확인
        ↓
Payment 상태 확정
```

실제 response loss 시나리오에서:

```text
VAN     : APPROVED
Payment : UNKNOWN_TIMEOUT
        ↓
Inquiry
Payment : APPROVED
```

로 복구되는 것을 검증했습니다.

같은 원칙을 Cancel에도 적용하고, R6에서는 Reversal까지 Inquiry contract를 확장했습니다.

요청 전달 여부를 확정할 수 없는 network failure에서는 transaction command를 blind replay하지 않습니다.

---

### 4. Cancel vs Reversal Invariant

동일 원승인에서 Cancel과 Reversal이 모두 성공하지 않도록 다음 invariant를 둡니다.

```text
successful(CANCELLED)
+
successful(REVERSED)
<= 1
```

동일 원승인 row를 lock하는 것만으로는 충분하지 않았고, concurrency test에서 두 operation이 순차적으로 모두 성공하는 문제를 확인했습니다.

이후 lock 획득 후 반대편 successful ledger를 다시 확인하도록 수정했습니다.

PostgreSQL integration test에서 Cancel 선행 / Reversal 선행 양쪽 모두 **successful operation이 최대 1건**임을 검증했습니다.

---

### 5. Automated Recovery

사용자가 직접 Inquiry를 호출하지 않아도 장시간 미확정 상태로 남은 거래를 자동으로 확인합니다.

```text
미확정 거래 탐지
        ↓
Recovery Task
        ↓
Worker Claim
        ↓
현재 DB 상태 재확인
        ↓
필요 시 VAN Inquiry
        ↓
조건부 상태 반영
        ↓
RESOLVED / RETRY_WAIT / MANUAL_REVIEW
```

Recovery에서는:

```text
stale != request not sent
```

로 판단합니다.

오래 `PROCESSING / PENDING` 상태라는 이유만으로 기존 transaction command를 다시 보내지 않고 Inquiry로 외부 사실을 확인합니다.

또한 lease가 만료된 Task를 새로운 Worker가 reclaim한 뒤 이전 Worker가 늦게 복귀할 수 있으므로 `claimToken + lease`를 fencing 조건으로 사용합니다.

```text
Worker A lease expiry
→ Worker B reclaim
→ Worker A late write
→ OWNERSHIP_LOST
```

경합 중 conditional UPDATE가 적용되지 않으면 실패로 단정하지 않고 DB를 다시 읽어 현재 persisted state를 기준으로 판단합니다.

> Recovery transaction boundary, Task dedupe, lease / claimToken fencing, failure classification과 race/crash 처리의 상세 내용은 [Design Notes](./docs/DESIGN.md)를 참고하세요.

---

## Observability

거래 장애를 단순 예외 메시지가 아니라 **어느 구간까지 처리되었는지 추적할 수 있도록** 애플리케이션 로그를 보강했습니다.

- HTTP 요청은 MDC의 `requestId`를 `[rid=...]`로 기록합니다.
- Payment Server ↔ VAN Simulator TCP 거래는 HTTP Request ID와 별도의 protocol correlation ID를 사용하고, 로그에서는 `vanRequestId`로 구분합니다.
- Payment Server는 `[van]`, `[reversal]`, `[inquiry]`, `[cancel-inquiry]`, `[recovery]` 흐름을 기록합니다.
- VAN Simulator는 `[van-tcp]` 기준으로 요청 수신, 처리 결과, Approval response drop 시나리오를 기록합니다.
- PAN, expiry, card fingerprint, secret, raw payload와 DTO 전체 값은 로그에 남기지 않습니다.
- Logback 출력 단계에서 request ID와 message의 제어문자를 치환해 로그 라인 변조 가능성을 줄입니다.

상세 정책은 [Logging & Request ID Policy](./docs/logging-policy.md)를 참고하세요.

---

## Key Verification Results

단순 happy path보다 **실패 위치와 동시 실행 순서를 의도적으로 만들어 최종 DB 상태와 VAN 호출 횟수**를 확인했습니다.

| Scenario | Verification Result |
|---|---|
| 동일 승인 요청 ×20 | **Payment attempt 1 / VAN Approval 1** |
| Approval response loss | **`UNKNOWN_TIMEOUT → Inquiry → APPROVED`** |
| 동일 원승인 Cancel ×20 | **`CANCELLED 1 / ALREADY_CANCELLED 19 / VAN Cancel 1`** |
| Cancel vs Reversal 경쟁 | **successful terminal operation 최대 1** |
| Lease 만료 후 stale Worker 복귀 | **late write = `OWNERSHIP_LOST` / 새 Worker 상태 유지** |
| Human Approval Inquiry vs Recovery Finalization | 동일 `APPROVED` 결과를 반영하는 두 실행 순서에서 persisted `APPROVED`로 수렴 |
| Payment terminal commit 후 Worker crash | reclaim 후 **VAN 재호출 없이 Task `RESOLVED`** |

<details>
<summary>Representative PostgreSQL integration tests</summary>

- `PostgresApprovalConcurrencyIntegrationTest`
- `PostgresUnknownTimeoutIntegrationTest`
- `PostgresCancelConcurrencyIntegrationTest`
- `PostgresCancelReversalConcurrencyIntegrationTest`
- `PostgresRecoveryLeaseFencingIntegrationTest`
- `PostgresInquiryRecoveryRaceIntegrationTest`
- `PostgresRecoveryCrashGapIntegrationTest`

</details>

PostgreSQL integration test는 Testcontainers를 사용합니다.

이 검증은 거래 정합성과 장애·경합 상황을 확인하기 위한 것으로 TPS / p95 성능 테스트는 아닙니다.

---

## Project Evolution

기능을 한 번에 완성하기보다 이전 단계에서 발견한 failure case를 다음 단계의 설계 문제로 확장했습니다.

```text
MVP1
→ Approval / Inquiry / Cancel 기본 거래 흐름

MVP2
→ 상태 모델과 timeout / UNKNOWN 복구 기반 강화

R3
→ PostgreSQL concurrency & idempotency

R4
→ Payment / VAN TCP boundary & response loss

R5
→ Cancel / Reversal transaction lifecycle

R6
→ Automated reconciliation & recovery
```

---

## Tech Stack

| 구분 | 기술 / 용도 |
|---|---|
| Language | Java 21 |
| Framework | Spring Boot 3.5.9 |
| HTTP | Spring Web, Validation |
| TCP | Spring Integration TCP, JSON |
| Payment Persistence | MyBatis, Spring JDBC |
| VAN Persistence | Spring Data JPA |
| Main Database | PostgreSQL 17 |
| Compatibility | SQLite — 초기 구현 및 일부 regression compatibility |
| Recovery Orchestration | Spring Batch |
| Test | JUnit 5, Mockito, Spring Boot Test, MyBatis Test, Testcontainers |
| Local Environment | Docker Compose |
| CI | GitHub Actions — develop PR / push 시 전체 Gradle test |
| Logging | MDC, Logback, HTTP / TCP correlation logging |

---

## API Summary

| 기능 | Method | Path |
|---|---|---|
| POS 거래번호 발급 | POST | `/api/v1/pos-trx/issue` |
| 승인 | POST | `/api/v1/payments/approve` |
| 승인 조회 | POST | `/api/v1/payments/inquiry` |
| 전체 취소 | POST | `/api/v1/payments/cancel` |
| 취소 조회 | POST | `/api/v1/payments/cancel/inquiry` |
| Reversal | POST | `/api/v1/payments/reversal` |

Recovery 운영 기능으로는 `MANUAL_REVIEW` Task 조회, 실행 History 조회, 기존 Task requeue API를 제공합니다.

---

## How to Run

### PostgreSQL

```bash
docker compose up -d
```

### VAN Simulator

```powershell
.\gradlew.bat :van-simulator:bootRun --args="--spring.profiles.active=postgres"
```

### Payment Server

```powershell
$env:CARD_SECRET_KEY = "local-dev-card-fingerprint-secret-key-32bytes"
$env:POSTGRES_PASSWORD = "postgres"
$env:PAYMENT_VAN_MODE = "tcp"

.\gradlew.bat :payment-server:bootRun --args="--spring.profiles.active=postgres"
```

| 대상 | 기본 주소 |
|---|---|
| Payment Server | `localhost:8080` |
| Payment DB | `localhost:5432/payment_sim` |
| VAN HTTP Control Plane | `localhost:8081` |
| VAN TCP Transaction Plane | `localhost:9090` |
| VAN DB | `localhost:5433/van_sim` |

Recovery Scheduler는 테스트와 로컬 실행 중 의도하지 않은 background recovery를 막기 위해 기본 비활성화되어 있습니다.

---

## Test

```powershell
$env:CARD_SECRET_KEY = "local-dev-card-fingerprint-secret-key-32bytes"

.\gradlew.bat :van-simulator:test
.\gradlew.bat :payment-server:test
```

PostgreSQL integration test는 Testcontainers를 사용하므로 Docker가 필요합니다.

GitHub Actions는 `develop` 대상 PR과 `develop` push에서 Java 21(Temurin) 환경으로 `./gradlew test --no-daemon --stacktrace`를 실행합니다. 테스트 실패 시 Payment Server와 VAN Simulator의 테스트 리포트를 artifact로 업로드합니다.

---

## Limitations

- 실제 상용 VAN / PG / 카드사 환경과 연동한 프로젝트가 아니며 VAN Simulator와 자체 TCP contract를 사용합니다.
- 실제 다중 Payment Server instance 기반 HA 및 TPS / p95 수준의 대규모 성능 검증은 수행하지 않았습니다.
- PostgreSQL 기반 대표 concurrency/race 시나리오는 검증했지만 모든 경합·장애 조합을 포괄하는 검증은 아닙니다.
- `MANUAL_REVIEW` 운영 알림은 structured logging 기반이며 외부 알림 채널과 Force Resolve / 수기 정산 workflow는 구현하지 않았습니다.
- Partial Cancel은 구현하지 않았습니다.
- 매입 / 정산 / 대사 시스템은 구현하지 않았습니다.

---

## Documentation

더 자세한 transaction boundary, network failure semantics, concurrency invariant와 Recovery race/crash 설계는 아래 문서에서 설명합니다.

- [Detailed Design — Payment Simulator Design Notes](./docs/DESIGN.md)
  - Transaction Model & Boundary
  - Network Failure Semantics
  - Inquiry Recovery
  - Cancel / Reversal Invariant
  - Recovery Claim / Lease / Fencing
  - Conditional Finalization
  - Race & Crash Scenarios
- [Logging & Request ID Policy](./docs/logging-policy.md)
  - HTTP Request ID / MDC
  - TCP protocol correlation ID
  - Sensitive data / log injection policy
