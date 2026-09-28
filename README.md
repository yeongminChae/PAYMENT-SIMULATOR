# 카드결제 시뮬레이터

POS 업무에서 접한 결제 예외를 계기로, **중복 승인·응답 유실·동시성 충돌·장기 미확정 거래 같은 실패 상황에서 결제 서버가 거래 정합성을 어떻게 유지해야 하는지 직접 구현하고 검증한 프로젝트**입니다.

Spring Boot 기반 Payment Server와 VAN Simulator를 분리하고 PostgreSQL·TCP 환경에서 승인, 조회, 전체취소, Reversal과 미확정 거래 자동 Recovery를 구현했습니다.

> **동일 승인 요청 20건을 VAN 승인 1건으로 수렴시키고, 응답이 유실돼 결과를 알 수 없는 거래는 재승인하지 않고 Inquiry로 복구했습니다. 이후 Cancel/Reversal 경쟁과 자동 Recovery까지 확장해 장애와 경합 상황에서도 저장된 거래 사실을 기준으로 상태가 수렴하도록 검증했습니다.**

---

## 30초 요약

| 문제 | 해결 | 결과 |
|---|---|---|
| 같은 승인 요청이 동시에 여러 번 들어옴 | DB에서 동일 거래를 직렬화하고 기존 처리 결과를 다시 확인 | **20건 요청 → Payment attempt 1건 / VAN 승인 1회** |
| VAN은 처리했지만 응답이 유실됨 | 실패로 단정하거나 재승인하지 않고 VAN의 실제 처리 결과를 조회 | `UNKNOWN_TIMEOUT → APPROVED` 복구 |
| Cancel과 Reversal이 동시에 경쟁함 | 하나가 성공한 뒤 다른 거래가 성공하지 못하도록 원승인 기준으로 제어 | 동일 원승인에 Cancel/Reversal 동시 성공 방지 |
| 미확정 거래가 장시간 남음 | 서버가 자동 탐지하고 VAN의 실제 처리 결과를 확인해 복구 | Worker 충돌·중단 이후에도 상태 복구 |

핵심 관심사는 기능 수보다 **거래 정합성, 멱등성, transaction boundary, failure semantics, 상태 복구**입니다.

---

## Why I Built This

POS 프로젝트에서 이중 승인, 장비·통신 timeout, 반품 실패 등 거래 예외를 재현하고 로그와 요청·응답을 확인하는 업무를 경험했습니다.

이 과정에서 화면의 정상 동작보다 **“외부 시스템의 처리 결과와 내부 거래 상태가 어긋났을 때 서버는 어떻게 판단하고 복구해야 하는가?”**에 관심을 갖게 됐습니다.

실제 VAN 승인 서버를 개발한 경험은 아니기 때문에, 개인 프로젝트에서 직접 제어할 수 있는 Payment Server와 VAN Simulator를 분리해 실패 상황을 재현하고 거래 상태를 검증했습니다.

---

## Architecture

```text
POS Client
    │
    │ HTTP
    ▼
Payment Server :8080
    │
    ├─ Approval / Inquiry / Cancel / Reversal
    │
    ├─ Recovery
    │    ├─ Candidate Discovery
    │    ├─ Recovery Task / History
    │    ├─ Worker
    │    └─ Retry / MANUAL_REVIEW
    │
    ├─ MyBatis / JDBC
    │    └─ payment_sim PostgreSQL :5432
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
         ├─ Inquiry
         └─ Spring Data JPA
              │
              ▼
         van_sim PostgreSQL :5433
````

Payment Server와 VAN Simulator는 Java DTO를 공유하지 않고 **TCP message contract를 시스템 간 경계**로 사용합니다.

- **Payment Server — MyBatis / JDBC**
  조건부 UPDATE, 상태 전이, row lock과 Recovery lifecycle을 명시적으로 제어합니다.
- **VAN Simulator — Spring Data JPA**
  Payment DB와 분리된 Approval / Cancel / Reversal 원장을 관리합니다.
- **HTTP**
  POS-facing API와 VAN Simulator 장애 시나리오 제어에 사용합니다.
- **TCP**
  Approval / Inquiry / Cancel / Reversal 거래 통신에 사용합니다.

TCP payload는 **4-byte Big Endian length prefix + UTF-8 JSON**으로 구성합니다.

---

## State Model

거래 상태와 Recovery 실행 상태는 서로 다른 lifecycle로 관리합니다.

### Payment Transaction

```
Approval

PROCESSING
 ├─ APPROVED
 ├─ DECLINED
 └─ UNKNOWN_TIMEOUT
```

```
Cancel

PENDING
 ├─ CANCELLED
 ├─ CANCEL_DECLINED
 └─ UNKNOWN_TIMEOUT
```

```
Reversal

PENDING
 ├─ REVERSED
 └─ REVERSAL_DECLINED
```

이 프로젝트에서 **Reversal은 승인 결과가 불확실하지만 VAN에는 승인 사실이 남아 있을 가능성이 있을 때 해당 승인을 명시적으로 되돌리기 위한 보상 거래**로 다룹니다.

### Recovery Task

```
PENDING
   │
   ▼
RUNNING
 ├─ RESOLVED
 │
 ├─ RETRY_WAIT
 │      │
 │      └────→ RUNNING
 │
 └─ MANUAL_REVIEW
```

> **Payment Transaction 상태와 Recovery Task 상태는 서로 다른 lifecycle입니다.**

예를 들어 Payment 거래가 `UNKNOWN_TIMEOUT` 상태를 유지하더라도 Recovery Task는 반복 복구 실패 후 `MANUAL_REVIEW`가 될 수 있습니다.

`MANUAL_REVIEW`는 거래를 임의로 승인·거절했다는 의미가 아니라, **자동 복구만으로는 더 이상 안전하게 사실을 확정할 수 없다는 의미**입니다.

---

## Core Design

### 1. Transaction Boundary

Approval / Cancel / Reversal은 공통적으로 외부 VAN 통신을 DB transaction과 분리합니다.

```
TX1
동일 거래 단위 직렬화
→ 멱등성 판단
→ PROCESSING / PENDING 저장
→ COMMIT

        ↓

VAN TCP 호출
(no DB transaction)

        ↓

TX2
VAN 결과 조건부 UPDATE
→ 상태 확정
→ DB row 기준 응답
→ COMMIT
```

외부 VAN이 느리거나 timeout이 발생해도 DB transaction과 row lock을 네트워크 응답 시간만큼 유지하지 않기 위한 선택입니다.

대신 TX1 이후 process가 종료되거나 상태 확정과 후속 처리 사이에 장애가 발생하면 중간 상태가 남을 수 있습니다.

이 문제를 하나의 긴 transaction으로 제거하지 않고, 이후 **Inquiry와 Recovery를 통해 현재 저장된 상태를 기준으로 다시 수렴**시키는 방향을 선택했습니다.

---

### 2. Idempotency & Concurrency

#### Approval

동일 `posTrx` 요청이 동시에 들어오더라도 최초 요청만 VAN Approval을 호출해야 합니다.

`PAYMENT_ATTEMPT_SEQ.POS_TRX` row를 직렬화 기준으로 사용하고, lock 획득 후 기존 attempt를 다시 조회합니다.

```
같은 posTrx + 같은 payload
→ 기존 결과 재사용

같은 posTrx + 다른 amount / cardFingerprint
→ 거래번호 재사용 conflict
```

**검증**

```
동일 승인 20건 동시 실행

PAYMENT_ATTEMPT       1건
PAYMENT_EXTERNAL_INFO 1건
VAN approve           1회
```

#### Cancel

Cancel은 현재 취소 거래번호가 아니라 **원승인 단위로 직렬화**합니다.

동일 원승인에 전체취소 요청이 동시에 들어와도 실제 VAN Cancel은 한 번만 발생하도록 검증했습니다.

```
20 concurrent cancel requests

CANCELLED          1
ALREADY_CANCELLED 19
VAN cancel         1
```

---

### 3. Timeout & Inquiry Recovery

이 프로젝트의 핵심 failure semantics는 다음과 같습니다.

```
응답을 받지 못함
≠
VAN에서 거래가 실패함
```

예를 들어 VAN이 Approval을 commit한 직후 응답만 유실될 수 있습니다.

```
VAN APPROVED commit
        ↓
response loss
        ↓
Payment read timeout
        ↓
UNKNOWN_TIMEOUT
```

이 상태에서 Approval을 다시 보내면 VAN에서는 이미 승인된 거래가 다시 처리될 위험이 있습니다.

따라서:

```
UNKNOWN_TIMEOUT
→ 성공/실패 추측 금지
→ 동일 command blind retry 금지
→ VAN Inquiry
→ VAN ledger 확인
→ Payment 상태 확정
```

으로 처리합니다.

**검증**

```
VAN     : APPROVED
Payment : UNKNOWN_TIMEOUT

        ↓ Inquiry

Payment : APPROVED
```

같은 원칙을 Cancel에도 적용했습니다.

```
VAN Cancel commit
→ response DROP
→ Payment Cancel UNKNOWN_TIMEOUT
→ Cancel command 재전송 X
→ Cancel Inquiry
→ CANCELLED 복구
```

R6에서는 Inquiry contract를 Reversal까지 확장해 장시간 `PENDING`인 Reversal 역시 Reversal command를 재전송하지 않고 VAN의 기존 Reversal ledger를 조회하도록 했습니다.

#### Request-not-sent와 전달 여부가 불확실한 실패

모든 network error를 같은 방식으로 처리하지 않습니다.

```
connect-before-send
→ 요청 bytes가 VAN에 전달되지 않았다고 확인 가능한 경우
→ command retry 가능
```

반면 다음 오류는 VAN에서 요청을 일부 또는 전부 처리했을 가능성을 배제할 수 없어 blind retry 대상으로 처리하지 않습니다.

```
read timeout
connection reset
EOF
write failure
```

---

### 4. Cancel vs Reversal Invariant

원승인 하나가 Cancel과 Reversal 양쪽에서 동시에 성공하면 거래 사실이 충돌합니다.

따라서 다음 invariant를 두었습니다.

```
successful(CANCELLED)
+
successful(REVERSED)
<= 1
```

처음에는 Cancel과 Reversal 양쪽에서 동일 `van_approval` row를 lock하는 것만으로 충분하다고 판단했습니다.

그러나 concurrency test에서 실제로:

```
Cancel   → CANCELLED
Reversal → REVERSED
```

가 모두 성공하는 문제를 재현했습니다.

row lock은 두 transaction을 직렬화할 뿐, **두 번째 transaction이 첫 번째 transaction의 성공 사실을 확인하도록 만들지는 않았기 때문**입니다.

수정 후에는 동일 원승인 row lock을 획득한 뒤 반대편 successful ledger를 다시 확인합니다.

```
Cancel 선행 성공
→ Reversal = ALREADY_CANCELLED

Reversal 선행 성공
→ Cancel = ALREADY_REVERSED

Declined operation
→ 반대 transaction을 차단하지 않음
```

원 Approval row를 취소 상태로 덮어쓰지 않고:

```
Approval Ledger
Cancel Ledger
Reversal Ledger
```

를 별도로 보존합니다.

**검증:** PostgreSQL concurrency integration test에서 Cancel winner / Reversal winner 양쪽 시나리오와 invariant를 확인했습니다.

---

### 5. Automated Reconciliation & Recovery

사용자가 Inquiry를 호출하지 않더라도 장시간 미확정 상태로 남은 거래를 자동으로 발견하고 복구합니다.

Recovery 대상은 다음과 같습니다.

```
Approval
- UNKNOWN_TIMEOUT
- stale PROCESSING

Cancel
- UNKNOWN_TIMEOUT
- stale PENDING

Reversal
- stale PENDING
```

여기서 중요한 판단은:

```
stale != request not sent
```

입니다.

오래 처리 중이라는 이유만으로 Approval / Cancel / Reversal command를 다시 보내지 않습니다.

```
미확정 거래
    ↓
Candidate Discovery
    ↓
Recovery Task
    ↓
Worker Claim
    ↓
현재 DB 상태 재조회
    │
    ├─ 이미 terminal
    │     → VAN 호출 없이 RESOLVED
    │
    └─ 아직 미확정
          ↓
       VAN Inquiry
          ↓
    Conditional UPDATE
          ↓
       DB reread
          ↓
RESOLVED / RETRY_WAIT / MANUAL_REVIEW
```

#### 여러 Worker가 같은 Task를 처리하는 문제

Recovery Worker가 여러 개 실행될 수 있으므로 PostgreSQL의 `FOR UPDATE SKIP LOCKED`를 사용해 하나의 Task를 한 Worker만 claim하도록 했습니다.

Worker가 처리 중 종료되면 Task가 영원히 `RUNNING`으로 남지 않도록 lease를 둡니다.

#### lease가 만료된 이전 Worker가 늦게 복귀하는 문제

다음 상황에서는 lease만으로 충분하지 않습니다.

```
Worker A
→ Task claim

A 처리 지연

→ lease 만료

Worker B
→ 같은 Task reclaim
→ 새로운 결과 확정

그 뒤 Worker A가 늦게 복귀
```

A가 뒤늦게 자신의 결과를 저장하면 B의 최신 결과를 덮어쓸 수 있습니다.

그래서 `claimToken`을 Task lifecycle UPDATE의 **fencing 조건**으로 사용합니다.

```
Task ID
+ RUNNING
+ 현재 claimToken
+ 유효한 lease
```

를 모두 만족한 Worker만 상태를 변경할 수 있습니다.

**검증**

```
Worker A → lease expiry
Worker B → reclaim

A late update
→ OWNERSHIP_LOST

B result
→ 유지
```

PostgreSQL 통합 테스트로 이전 Worker의 늦은 write가 최신 Task 상태를 덮지 못하는 것을 확인했습니다.

#### 경합 시 기존 확정 상태를 덮어쓰지 않음

Recovery가 VAN에서 terminal 결과를 확인해도 현재 DB 상태를 무조건 덮어쓰지 않습니다.

```
conditional UPDATE
        ↓
update = 1
→ APPLIED

update = 0
→ DB reread
```

재조회 결과를 기준으로:

```
ALREADY_CONSISTENT
STILL_UNRESOLVED
TERMINAL_CONFLICT
TARGET_NOT_FOUND
```

등으로 구분합니다.

이를 통해 사용자 Inquiry와 Recovery가 동시에 같은 거래를 확정하더라도 마지막 network response가 아니라 **실제 persisted DB state를 기준으로 수렴**합니다.

#### 처리 도중 장애가 발생한 경우

외부 I/O와 여러 DB transaction을 분리했기 때문에 다음 구간에서 process가 종료될 수 있습니다.

```
VAN Inquiry
→ Payment terminal COMMIT
→ process crash
→ Recovery Task는 RUNNING
```

lease가 만료된 뒤 다른 Worker가 Task를 reclaim하면 Payment DB를 다시 읽습니다.

이미 terminal이면:

```
VAN Inquiry 재호출 X
→ Recovery Task만 RESOLVED
```

처리합니다.

즉 이전 Worker가 왜 종료됐는지 별도 flag로 추론하지 않고 **현재 저장된 거래 사실을 다시 읽어 복구**합니다.

#### 자동 복구의 종료 경계

외부 사실을 계속 확인할 수 없는 경우 무한 retry하지 않습니다.

유한한 retry 이후:

```
MANUAL_REVIEW
```

로 전환합니다.

`MANUAL_REVIEW`는 Payment 거래를 임의 승인·거절하는 상태가 아니라 **사람의 판단이 필요한 Recovery workflow 상태**입니다.

현재는 구조화 로그 기반 알림을 제공하며 Slack / Email / SMS 같은 실제 외부 운영 채널은 연결하지 않았습니다.

MANUAL_REVIEW Task와 History를 조회하고 기존 Task를 다시 `PENDING`으로 돌리는 Admin API도 제공합니다.

Admin requeue는 Approval / Cancel / Reversal command를 직접 실행하지 않고 **다음 Recovery cycle에서 다시 Inquiry 기반 복구를 수행**합니다.

Spring Batch는 Recovery 업무 규칙 자체가 아니라 **Candidate Discovery → Worker Processing을 실행하는 orchestration**으로 사용하며 Scheduler는 기본 비활성화 상태입니다.

---

## Test Strategy & Key Verification Results

단순 happy path보다 실패 위치와 동시 실행 순서를 명시적으로 만들어 DB 최종 상태와 VAN 호출 횟수를 확인했습니다.

| 시나리오 | 검증 결과 | 대표 테스트 |
|---|---|---|
| 동일 승인 동시 요청 20건           | Payment attempt 1 / VAN approve 1                   | `PostgresApprovalConcurrencyIntegrationTest`       |
| Approval response loss    | `UNKNOWN_TIMEOUT → Inquiry → APPROVED`              | `PostgresUnknownTimeoutIntegrationTest`            |
| 동일 원승인 Cancel 20건         | `CANCELLED 1 / ALREADY_CANCELLED 19 / VAN cancel 1` | `PostgresCancelConcurrencyIntegrationTest`         |
| Cancel vs Reversal 경쟁     | successful terminal operation 최대 1                  | `PostgresCancelReversalConcurrencyIntegrationTest` |
| lease 만료 후 이전 Worker 복귀   | stale Worker `OWNERSHIP_LOST`, 새 Worker 상태 유지       | `PostgresRecoveryLeaseFencingIntegrationTest`      |
| Human Inquiry vs Recovery | 실행 순서와 관계없이 persisted terminal로 수렴                  | `PostgresInquiryRecoveryRaceIntegrationTest`       |
| target ledger 확정 후 crash  | reclaim 후 VAN 재호출 없이 `RESOLVED`                     | `PostgresRecoveryCrashGapIntegrationTest`          |

Human Inquiry와 Recovery는 동일한 Finalizer를 공유하지 않습니다.

두 경로는 서로 다른 conditional update 경로를 사용하지만, 모두 **update miss 이후 DB를 다시 읽는 방식**으로 경합 시 persisted state에 수렴합니다.

PostgreSQL integration test는 Testcontainers를 이용합니다.

이 검증은 **정합성 및 장애 시나리오 검증**이며 TPS / p95 성능 테스트가 아닙니다.

---

## Project Evolution

```
MVP
→ Approval / Inquiry / Cancel 기본 거래 흐름

Concurrency Hardening (R3)
→ PostgreSQL concurrency & idempotency

System Boundary (R4)
→ Payment/VAN TCP boundary & response loss

Transaction Lifecycle (R5)
→ Cancel / Reversal transaction lifecycle

Automated Recovery (R6)
→ Reconciliation / Claim / Lease / Fencing
```

기능 수를 늘리는 것보다 이전 단계에서 발견한 failure case를 다음 단계의 설계 문제로 확장하는 방식으로 발전시켰습니다.

---

## Tech Stack

| 구분 | 기술 / 용도 |
|---|---|
| Language               | Java 21                                                          |
| Framework              | Spring Boot 3.5.9                                                |
| HTTP                   | Spring Web, Validation                                           |
| TCP                    | Spring Integration TCP, JSON                                     |
| Payment Persistence    | MyBatis, Spring JDBC                                             |
| VAN Persistence        | Spring Data JPA                                                  |
| Main Database          | PostgreSQL 17 — 거래/동시성/Recovery 검증 기준                            |
| Compatibility          | SQLite — 초기 구현 및 일부 regression compatibility                     |
| Recovery Orchestration | Spring Batch                                                     |
| Test                   | JUnit 5, Mockito, Spring Boot Test, MyBatis Test, Testcontainers |
| Local Environment      | Docker Compose                                                   |
| Logging                | MDC, Logback                                                     |

---

## API Summary

| 기능 | Method | Path |
|---|---|---|
| POS 거래번호 발급  | POST | `/api/v1/pos-trx/issue`           |
| 승인           | POST | `/api/v1/payments/approve`        |
| 승인 조회        | POST | `/api/v1/payments/inquiry`        |
| 전체 취소        | POST | `/api/v1/payments/cancel`         |
| 취소 조회        | POST | `/api/v1/payments/cancel/inquiry` |
| Reversal     | POST | `/api/v1/payments/reversal`       |

Recovery 운영 기능으로는 **MANUAL_REVIEW Task 조회, 실행 History 조회, 기존 Task 재큐잉 API**를 제공합니다.

---

## How to Run

### PostgreSQL

```
docker compose up -d
```

### VAN Simulator

```
.\gradlew.bat :van-simulator:bootRun --args="--spring.profiles.active=postgres"
```

### Payment Server

```
$env:CARD_SECRET_KEY = "local-dev-card-fingerprint-secret-key-32bytes"
$env:POSTGRES_PASSWORD = "postgres"
$env:PAYMENT_VAN_MODE = "tcp"

.\gradlew.bat :payment-server:bootRun --args="--spring.profiles.active=postgres"
```

| 대상기본 주소                   |                              |
| ------------------------- | ---------------------------- |
| Payment Server            | `localhost:8080`             |
| Payment DB                | `localhost:5432/payment_sim` |
| VAN HTTP Control Plane    | `localhost:8081`             |
| VAN TCP Transaction Plane | `localhost:9090`             |
| VAN DB                    | `localhost:5433/van_sim`     |

Recovery Scheduler는 테스트/로컬 실행 중 의도하지 않은 background recovery를 막기 위해 기본 비활성화되어 있습니다.

---

## Test

```
$env:CARD_SECRET_KEY = "local-dev-card-fingerprint-secret-key-32bytes"

.\gradlew.bat :van-simulator:test
.\gradlew.bat :payment-server:test
```

PostgreSQL integration test는 Testcontainers를 사용하므로 Docker가 필요합니다.

---

## Limitations

이 프로젝트는 거래 정합성과 장애 복구 문제를 학습·검증하기 위한 개인 시뮬레이터입니다.

- 실제 상용 VAN / PG / 카드사 운영 연동 환경이 아님
- VAN Simulator를 이용하며 실제 proprietary 금융망 전문을 구현한 것은 아님
- 실제 다중 Payment Server instance 환경의 HA / 대규모 부하 검증 미수행
- PostgreSQL concurrency primitive는 검증했지만 TPS / p95 등 성능 검증은 수행하지 않음
- `MANUAL_REVIEW` 알림은 현재 structured logging 기반이며 Slack / Email / SMS 연동 없음
- 운영자 Force Resolve / 수기 정산 workflow 미구현
- Partial Cancel 미구현
- 매입 / 정산 / 대사 시스템 미구현
