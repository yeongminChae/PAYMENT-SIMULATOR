# 카드결제 시뮬레이터

POS 업무에서 접한 결제 예외를 계기로, **중복 승인·응답 유실·동시성 충돌 같은 실패 상황을 시뮬레이터에서 재현하고 거래 정합성을 검증하기 위해 만든 프로젝트**입니다.

Spring Boot 기반 Payment Server와 VAN Simulator를 분리하고, PostgreSQL·TCP 환경에서 승인·조회·전체취소·Reversal을 구현했습니다.

## 30초 요약

| 문제 | 설계 | 검증 |
|---|---|---|
| 동일 승인 동시 요청 | 거래 단위 DB row lock + 멱등성 재검사 | 20건 동시 요청 → `PAYMENT_ATTEMPT` 1건, VAN approve 1회 |
| VAN 승인 후 응답 유실 | `UNKNOWN_TIMEOUT` 보존, blind retry 금지, Inquiry 복구 | VAN `APPROVED` / Payment `UNKNOWN_TIMEOUT` → Inquiry로 `APPROVED` 복구 |
| Cancel / Reversal 경쟁 | 동일 원승인 `van_approval` row lock | `CANCELLED`와 `REVERSED` 중 성공 operation 최대 1건 |

핵심 관심사는 기능 수보다 **거래 정합성, 멱등성, transaction boundary, 외부 통신 실패, 상태 복구**입니다.

---

## 시스템 구조

```text
POS Client
    ↓ HTTP
Payment Server :8080
    ├─ MyBatis / JDBC
    │    └─ payment_sim PostgreSQL :5432
    │
    └─ VanGateway
         ↓
         TCP [4-byte length prefix][UTF-8 JSON] :9090
         ↓
       VAN Simulator :8081
         ├─ Spring Data JPA
         └─ van_sim PostgreSQL :5433
```

Payment Server와 VAN Simulator는 Java DTO를 공유하지 않고 TCP protocol contract를 경계로 분리했습니다.

- **Payment Server**: 조건부 UPDATE, 상태 전이, row lock을 명시적으로 다루기 위해 MyBatis/JDBC 사용
- **VAN Simulator**: 독립 거래 원장을 엔티티 중심으로 관리하기 위해 Spring Data JPA 사용
- HTTP는 장애 시나리오 제어, TCP는 Approval / Inquiry / Cancel / Reversal 거래 처리에 사용

TCP payload는 **4-byte Big Endian length prefix + UTF-8 JSON**으로 구성합니다.

상세 계약은 [`docs/docs/van-protocol`](docs/docs/van-protocol/README.md)에 정리했습니다.

---

## 핵심 설계

### 1. 외부 TCP 호출을 DB transaction 밖으로 분리

승인·취소·Reversal은 공통적으로 다음 경계를 사용합니다.

```text
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
```

외부 시스템 timeout 동안 DB transaction과 row lock을 유지하지 않도록, **중간 상태를 먼저 commit한 뒤 외부 호출 결과를 별도 transaction에서 확정**합니다.

---

### 2. 승인 응답 유실을 실패로 단정하지 않음

VAN이 승인을 commit한 뒤 응답만 유실되면 Payment Server는 승인 결과를 확정할 수 없습니다.

```text
VAN APPROVED commit
→ response loss
→ Payment UNKNOWN_TIMEOUT
→ 동일 승인 blind retry 금지
→ Inquiry
→ VAN ledger 조회
→ Payment 상태 확정
```

`UNKNOWN_TIMEOUT`은 실패가 아니라 **Payment가 VAN의 최종 결과를 아직 확인하지 못한 상태**로 취급합니다.

동일 승인 재요청은 VAN approve를 다시 호출하지 않고 기존 DB 결과를 반환합니다.

**검증:** `DROP_RESPONSE`로 VAN `APPROVED` / Payment `UNKNOWN_TIMEOUT`을 만든 뒤 TCP Inquiry로 Payment 상태를 `APPROVED`로 복구했습니다.

---

### 3. 승인 멱등성과 동시성

같은 `posTrx` 요청이 동시에 들어와도 최초 요청만 VAN approve를 호출해야 합니다.

`PAYMENT_ATTEMPT_SEQ.POS_TRX` row를 직렬화 기준으로 사용하고, lock 획득 후 기존 attempt를 다시 확인합니다.

- 동일 `posTrx` + 동일 payload → 기존 결과 재사용
- 동일 `posTrx` + 다른 amount/card fingerprint → 거래번호 재사용 차단

**검증:** 동일 승인 요청 20건 동시 실행에서 `PAYMENT_ATTEMPT` 1건, `PAYMENT_EXTERNAL_INFO` 1건, VAN approve 1회를 확인했습니다.

---

### 4. Cancel response loss도 Inquiry로 복구

Cancel은 **원승인 단위로 직렬화**하고 최초 요청만 VAN Cancel을 호출합니다.

VAN이 Cancel을 commit한 뒤 응답이 유실되면 Payment는 `UNKNOWN_TIMEOUT`을 보존하고, 동일 Cancel을 다시 보내지 않고 Cancel Inquiry로 VAN 원장을 조회합니다.

**검증:** 동일 원승인 Cancel 20건에서 `CANCELLED` 1건, `ALREADY_CANCELLED` 19건, VAN Cancel 1회를 확인했습니다. `DROP_RESPONSE` 이후에는 Payment `UNKNOWN_TIMEOUT`을 Cancel Inquiry로 `CANCELLED`까지 복구했고 VAN row 중복은 없었습니다.

---

### 5. Reversal을 일반 Cancel과 분리

Payment가 원승인을 `UNKNOWN_TIMEOUT`으로 알고 있지만 VAN에는 실제 `APPROVED`가 남아 있을 수 있습니다.

이 경우 Reversal은 자동 retry가 아니라 **결과가 불확실한 승인을 명시적으로 되돌리는 recovery operation**입니다.

```text
Payment : UNKNOWN_TIMEOUT
VAN     : APPROVED

        ↓ explicit Reversal

Payment : PAYMENT_REVERSAL = REVERSED
VAN     : van_reversal     = REVERSED
```

승인 사실을 덮어쓰지 않고 Approval / Cancel / Reversal을 각각 별도 ledger에 기록합니다.

**검증:** Approval `DROP_RESPONSE` 이후 Reversal을 실행해 Payment/VAN 양쪽이 동일 `vanReversalTrxId`, `reversalApprovalNo`로 `REVERSED` 확정되는 것을 확인했습니다.

VAN Simulator를 종료한 상태에서도 동일 Reversal은 기존 `REVERSED` 결과를 재응답하고, 같은 original의 다른 identity는 `ALREADY_REVERSED`를 반환해 **Payment DB에서 중복 요청이 차단됨**을 확인했습니다.

---

### 6. Cancel과 Reversal의 단일 종료 보장

동일 원승인에서 Cancel과 Reversal이 모두 성공하지 않도록 다음 invariant를 둡니다.

```text
successful(CANCELLED) + successful(REVERSED) <= 1
```

VAN Cancel과 Reversal은 모두 동일 `(originalPosTrx, originalAttemptSeq)`의 `van_approval` row를 `PESSIMISTIC_WRITE`로 잠근 뒤 반대편 성공 ledger를 확인합니다.

- Cancel 선행 성공 → Reversal `ALREADY_CANCELLED`
- Reversal 선행 성공 → Cancel `ALREADY_REVERSED`
- declined operation은 반대 operation을 막지 않음

**검증:** PostgreSQL concurrency integration test에서 Cancel winner / Reversal winner 양쪽 시나리오와 위 invariant를 확인했습니다.

---

## 추가 설계 이슈

### Request-not-sent와 전달 여부 불명확 실패 분리

실제 connection refused로 확인한 **connect-before-send 실패만** request-not-sent로 분류해 `PROCESSING` row를 정리하고 동일 payload 재요청을 허용합니다.

반면 response timeout, connection reset, EOF, write failure처럼 요청 전달 여부가 불명확한 오류는 자동 retry하지 않습니다.

### 카드정보 보호

PAN 원문은 저장하지 않고 HMAC-SHA256 기반 `cardFingerprint`와 BIN/last4만 저장합니다.

외부 VAN 응답을 그대로 반환하지 않고 조건부 UPDATE와 DB 재조회 결과를 최종 응답 기준으로 사용합니다.

---

## Release 5 검증 결과

상단 핵심 시나리오 외에도 다음을 검증했습니다.

| 검증 항목 | 결과 |
|---|---|
| POS 거래번호 동시 발급 | 20건 반환, 중복 0 |
| connect-before-send | `PROCESSING` 정리 후 동일 payload 재요청 가능 |
| Cancel response loss | `UNKNOWN_TIMEOUT` 보존 후 Cancel Inquiry 복구 |
| Reversal exact replay | VAN down 상태에서도 기존 DB 결과 재응답 |
| Same-original Reversal | VAN down 상태에서도 `ALREADY_REVERSED`, 새 row 없음 |
| declined opposite | 실패 operation이 반대 successful operation을 막지 않음 |
| 전체 회귀 | `:van-simulator:test`, `:payment-server:test` 모두 GREEN |

위 검증은 PostgreSQL transaction/concurrency test와 수동 E2E를 통해 DB 최종 상태, 외부 호출 횟수, ledger 중복 여부를 확인한 것입니다.

**TPS나 p95 성능을 측정한 테스트는 아닙니다.**

---

## 기술 스택

| 구분 | 기술 |
|---|---|
| Language | Java 21 |
| Framework | Spring Boot 3.5.9 |
| API / TCP | Spring Web, Validation, Spring Integration TCP, JSON |
| Payment Persistence | MyBatis, Spring JDBC |
| VAN Persistence | Spring Data JPA |
| Database | SQLite, PostgreSQL 17 |
| Test | JUnit 5, Mockito, Spring Boot Test, MyBatis Test, Testcontainers |

---

## API

| 기능 | Method | Path |
|---|---|---|
| POS 거래번호 발급 | POST | `/api/v1/pos-trx/issue` |
| 승인 | POST | `/api/v1/payments/approve` |
| 승인 조회 | POST | `/api/v1/payments/inquiry` |
| 전체 취소 | POST | `/api/v1/payments/cancel` |
| 취소 조회 | POST | `/api/v1/payments/cancel/inquiry` |
| Reversal | POST | `/api/v1/payments/reversal` |

---

## 실행

PostgreSQL + VAN Simulator 환경:

```powershell
docker compose up -d
```

VAN Simulator:

```powershell
.\gradlew.bat :van-simulator:bootRun --args="--spring.profiles.active=postgres"
```

Payment Server:

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

---

## 테스트

```powershell
$env:CARD_SECRET_KEY = "local-dev-card-fingerprint-secret-key-32bytes"

.\gradlew.bat :van-simulator:test
.\gradlew.bat :payment-server:test
```

PostgreSQL integration test는 Testcontainers를 사용하므로 Docker가 필요합니다.

---

## 한계

- 실제 상용 VAN·카드사 전문 연동은 아님
- Reversal response loss recovery 미구현
- TX1 commit 후 VAN 호출 전 process crash 시 `PROCESSING` / `PENDING` 고착 가능
- connection reset, EOF, write failure처럼 요청 전달 여부가 불명확한 오류는 자동 retry하지 않음
- 다중 Payment Server instance / HA / distributed lock 미검증
- scheduler/batch 기반 장기 `PENDING` 복구, 부분취소, 정산/매입/대사 미구현
- TPS / p95 성능 측정 미수행
