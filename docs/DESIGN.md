# Payment Simulator Design Notes

이 문서는 카드결제 시뮬레이터의 상세 설계와 실패·경합 상황에서 거래 상태를 안전하게 관리하고 복구하기 위해 선택한 구현 방식을 설명합니다.

프로젝트 개요, 대표 검증 결과, 실행 방법은 [루트 README](../README.md)를 참고하세요.

---

## 1. Transaction Model

Payment Server와 VAN Simulator는 서로 다른 저장소를 사용하며 TCP message contract를 시스템 간 경계로 사용합니다.

```text
POS Client
    │
    │ HTTP
    ▼
Payment Server
    │
    │ TCP
    │ [4-byte length prefix][UTF-8 JSON]
    ▼
VAN Simulator
```

Payment Server와 VAN Simulator는 Java DTO를 직접 공유하지 않습니다.

Payment Server는 POS 요청과 내부 거래 상태를 관리하고, VAN Simulator는 외부 승인 시스템을 모사하는 독립된 거래 ledger를 관리합니다.

### 1.1 Payment Transaction State

Approval:

```text
PROCESSING
 ├─ APPROVED
 ├─ DECLINED
 └─ UNKNOWN_TIMEOUT
```

Cancel:

```text
PENDING
 ├─ CANCELLED
 ├─ CANCEL_DECLINED
 └─ UNKNOWN_TIMEOUT
```

Reversal:

```text
PENDING
 ├─ REVERSED
 └─ REVERSAL_DECLINED
```

`UNKNOWN_TIMEOUT`은 거래 실패를 의미하지 않습니다.

Payment Server가 VAN의 최종 응답을 받지 못해 외부 시스템에 실제로 어떤 결과가 저장되었는지 확정할 수 없는 상태입니다.

### 1.2 Approval / Cancel / Reversal Ledger

VAN Simulator에서는 Approval / Cancel / Reversal 거래 사실을 별도 ledger로 보존합니다.

Cancel 또는 Reversal이 발생했다고 기존 Approval 사실을 덮어쓰지 않습니다.

### 1.3 Reversal

이 프로젝트에서 Reversal은 승인 결과가 불확실하지만 VAN에는 승인 사실이 남아 있을 가능성이 있을 때 해당 승인을 명시적으로 되돌리기 위한 보상 거래로 다룹니다.

Payment Server에서는 원승인이 `UNKNOWN_TIMEOUT`인 경우에만 Reversal command를 허용합니다.

```text
UNKNOWN_TIMEOUT
→ Reversal 허용

APPROVED / DECLINED / PROCESSING
→ Reversal command 불허
```

VAN Simulator에서는:

```text
APPROVED / UNKNOWN
→ reversible

DECLINED
→ reversal 불가
```

로 처리합니다.

### 1.4 Payment State와 Recovery State

Recovery 실행 상태는 Payment Transaction 상태와 별도로 관리합니다.

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

`MANUAL_REVIEW`는 Payment Transaction의 terminal state가 아니라 자동화만으로 외부 거래 사실을 안전하게 확정하기 어려운 Recovery workflow 상태입니다.

---

## 2. Transaction Boundary

Approval / Cancel / Reversal은 VAN 통신을 DB transaction과 분리합니다.

```text
TX1
동일 거래 단위 직렬화
→ 멱등성 / 현재 상태 판단
→ PROCESSING / PENDING 저장
→ COMMIT

        ↓

VAN TCP Call
(no DB transaction)

        ↓

TX2
VAN 결과 조건부 반영
→ DB 상태 재확인
→ COMMIT
```

### 2.1 External I/O를 Transaction 밖으로 분리한 이유

외부 VAN 통신 시간만큼 DB transaction과 row lock이 유지되는 것을 피하기 위한 선택입니다.

### 2.2 Trade-off: Crash Gap

이 구조에서는:

```text
TX1 COMMIT
→ PROCESSING
→ process crash
```

또는:

```text
VAN APPROVED
→ Payment Server process crash
→ TX2 미실행
```

같은 중간 상태가 남을 수 있습니다.

외부 terminal fact가 확인되면 Payment 상태를 그 사실에 맞게 수렴시키고, 확인할 수 없다면 미확정 상태를 임의로 terminal로 변경하지 않은 채 Recovery lifecycle에서 `RETRY_WAIT` 또는 `MANUAL_REVIEW`로 관리합니다.

---

## 3. Idempotency & Concurrency

### 3.1 Approval

동일 `posTrx` 요청을 직렬화한 뒤 기존 attempt를 다시 확인합니다.

```text
same posTrx + same payload
→ 기존 거래 상태/결과를 기준으로 응답

same posTrx + different payload
→ conflict
```

검증:

```text
20 concurrent approval requests

Payment attempt       1
Payment external info 1
VAN Approval          1
```

### 3.2 Cancel

Cancel identity는 `cancelPosTrx`, 대상 원승인은 `originalPosTrx + originalAttemptSeq`입니다.

```text
same cancelPosTrx + same payload
→ 기존 결과 replay

same cancelPosTrx + different payload
→ conflict
```

동일 원승인 전체취소 20건 동시 요청에서:

```text
CANCELLED          1
ALREADY_CANCELLED 19
VAN Cancel         1
```

을 확인했습니다.

### 3.3 Reversal Idempotency

Reversal identity는 `reversalPosTrx`, 대상은 `originalPosTrx + originalAttemptSeq`입니다.

```text
same reversalPosTrx + same payload
→ 기존 결과 replay
→ VAN 재호출 없음

same reversalPosTrx + different payload
→ conflict
```

이미 동일 원승인에 성공한 Reversal이 존재하면:

```text
different reversalPosTrx
+ successful REVERSED already exists

→ ALREADY_REVERSED
→ 새 Payment row 없음
→ 새 VAN row 없음
→ VAN Reversal 호출 없음
```

으로 short-circuit합니다.

---

## 4. Network Failure Semantics

```text
응답을 받지 못함
≠
VAN에서 거래가 실패함
```

### 4.1 Response Loss

```text
VAN Approval COMMIT
→ response loss
→ Payment read timeout
→ UNKNOWN_TIMEOUT
```

이미 VAN에 승인 사실이 존재할 수 있으므로 동일 Approval command를 blind replay하지 않습니다.

### 4.2 Request-not-sent와 Delivery-unknown

`connect-before-send` 단계에서 요청 bytes가 전달되지 않았음을 확인할 수 있는 경우에만 `request-not-sent`로 분류합니다.

Approval의 검증된 request-not-sent 경로에서는:

```text
PROCESSING attempt 정리
+ external info 정리
→ 동일 payload의 후속 Approval 요청 허용
```

으로 처리합니다.

sequence와 감사 목적의 이력은 되돌리지 않습니다.

반대로:

```text
read timeout
connection reset
EOF
write failure
```

처럼 전달 여부를 확정할 수 없는 오류는 `request-not-sent`로 확대하지 않으며 동일 transaction command의 blind replay를 허용하지 않습니다.

---

## 5. Inquiry Recovery

### 5.1 Approval

```text
UNKNOWN_TIMEOUT
→ VAN Inquiry
→ Approval Ledger 확인
→ Payment 상태 확정
```

검증:

```text
VAN     : APPROVED
Payment : UNKNOWN_TIMEOUT
        ↓
Inquiry
Payment : APPROVED
```

### 5.2 Cancel

```text
VAN Cancel COMMIT
→ response DROP
→ Payment Cancel UNKNOWN_TIMEOUT
→ Cancel Inquiry
→ CANCELLED
```

### 5.3 Reversal

```text
stale Reversal PENDING
→ Reversal Inquiry
→ VAN Reversal Ledger 확인
```

오래됐다는 이유만으로 Reversal command를 재전송하지 않습니다.

---

## 6. Cancel vs Reversal Invariant

```text
successful(CANCELLED)
+
successful(REVERSED)
<= 1
```

처음에는 동일 `van_approval` row를 lock하면 충분하다고 판단했지만 실제 concurrency test에서 Cancel과 Reversal이 순차적으로 모두 성공하는 문제를 재현했습니다.

```text
serialization
≠
business invariant guarantee
```

수정 후에는 원승인 row lock 이후 반대편 successful ledger를 다시 확인합니다.

```text
Cancel 선행 성공
→ Reversal = ALREADY_CANCELLED

Reversal 선행 성공
→ Cancel = ALREADY_REVERSED
```

---

## 7. Automated Recovery

```text
Candidate Discovery
        ↓
Recovery Task
        ↓
Worker Claim
        ↓
Handler
        ↓
현재 Payment 상태 재확인
        ↓
필요 시 VAN Inquiry
        ↓
Conditional Finalization
        ↓
필요 시 DB Reread
        ↓
RESOLVED / RETRY_WAIT / MANUAL_REVIEW
```

### 7.1 Candidate Discovery

```text
Approval
- UNKNOWN_TIMEOUT
- stale PROCESSING

Cancel
- UNKNOWN_TIMEOUT
- stale PENDING

Reversal
- stale PENDING
```

핵심 원칙:

```text
stale != request not sent
```

시간 기준은:

```text
UNKNOWN_TIMEOUT
→ UPDATED_AT

PROCESSING / PENDING
→ CREATED_AT
```

입니다.

### 7.2 Recovery Task / History와 중복 생성 방지

Task 중복 방지는 DB constraint를 최종 authority로 사용합니다.

```text
Approval
UNIQUE (
  TARGET_TYPE,
  TARGET_TRX_NO,
  TARGET_ATTEMPT_SEQ
)

Cancel / Reversal
UNIQUE (
  TARGET_TYPE,
  TARGET_TRX_NO
)
```

```sql
INSERT ... ON CONFLICT DO NOTHING
```

`retryCount`는 자동 retry budget 소비 횟수이고, History `tryNo`는 Worker의 실제 실행 순번입니다.

Admin requeue가 `retryCount`를 초기화해도 기존 History는 유지되므로 `tryNo`는 이어집니다.

### 7.3 Worker / Handler / Recovery Finalization Responsibility

```text
Discovery
→ candidate 탐색 / Task 등록

Worker
→ Task Claim
→ Handler 실행
→ Task lifecycle 마무리

Handler
→ Approval / Cancel / Reversal별 상태 재확인
→ 필요한 경우 VAN Inquiry

Recovery finalization path
→ VAN에서 확인한 사실을 Payment DB에 조건부 반영
→ update miss 시 DB reread
→ persisted state 기준 결과 판정
```

Human Inquiry와 Recovery가 하나의 공통 Finalizer 컴포넌트를 공유하는 구조는 아닙니다.

두 경로는 서로 다른 conditional update 범위를 가지지만, update miss 이후 DB를 다시 읽어 persisted state를 기준으로 판단한다는 원칙을 공유합니다.

### 7.4 Worker Claim과 SKIP LOCKED

Claim 가능한 Task:

```text
PENDING
due RETRY_WAIT
expired RUNNING
```

동시 claim transaction에서는:

```sql
FOR UPDATE SKIP LOCKED
```

를 사용합니다.

`SKIP LOCKED`는 claim transaction 사이의 row lock 경합을 제어합니다.

```text
Claim TX
→ candidate row lock
→ RUNNING + claimToken + lease 설정
→ COMMIT
→ row lock 해제
```

Claim commit 이후 ownership은 row lock이 아니라:

```text
RUNNING
+ claimToken
+ lease
```

로 관리합니다.

### 7.5 Lease와 Reclaim

```text
Worker A Claim
→ RUNNING
→ 처리 중 중단

lease expiry

Worker B
→ Reclaim
```

### 7.6 claimToken Fencing

Task lifecycle update에는 다음 ownership 조건을 사용합니다.

```sql
ID = taskId
AND RECOVERY_STATUS = 'RUNNING'
AND CLAIM_TOKEN = currentToken
AND LEASE_EXPIRES_AT > now
```

exact expiry에서도 기존 Worker는 ownership을 잃습니다.

```text
Worker A lease expiry
→ Worker B reclaim
→ Worker A late update
→ OWNERSHIP_LOST
```

### 7.7 Conditional Finalization

```text
Approval
PROCESSING / UNKNOWN_TIMEOUT
→ APPROVED / DECLINED

Cancel
PENDING / UNKNOWN_TIMEOUT
→ CANCELLED / CANCEL_DECLINED

Reversal
PENDING
→ REVERSED / REVERSAL_DECLINED
```

```text
Conditional UPDATE
        │
        ├─ update = 1
        │      → APPLIED
        │
        └─ update = 0
               ↓
            DB Reread
```

재조회 결과는:

```text
동일 terminal
→ ALREADY_CONSISTENT

다른 terminal
→ TERMINAL_CONFLICT

아직 unresolved
→ STILL_UNRESOLVED

대상 없음
→ TARGET_NOT_FOUND
```

으로 구분합니다.

### 7.8 Failure Classification / Retry / MANUAL_REVIEW

Exception classification:

```text
RETRYABLE
- VAN Inquiry timeout
- Inquiry request-not-sent

MANUAL
- Recovery invariant violation

UNKNOWN
- 예상하지 못한 RuntimeException
```

`request-not-sent`의 retry는 transaction command 재전송이 아니라 **Recovery Inquiry execution 재시도**를 의미합니다.

UNKNOWN failure는 History에 `UNKNOWN_FAILURE`를 남기고 exception을 rethrow합니다. Task는 임의 상태 전이하지 않고 현재 RUNNING ownership을 유지하며 이후 lease expiry 후 reclaim될 수 있습니다.

Handler / retry outcome:

```text
TERMINAL_CONFLICT
→ MANUAL_REVIEW

TARGET_NOT_FOUND
→ MANUAL_REVIEW

retry budget exhausted
→ MANUAL_REVIEW
```

### 7.9 Recovery Transaction Boundary

```text
TX-R1
Recovery Task Claim
+ Recovery History START
COMMIT

        ↓

Handler
+ VAN Inquiry
(no DB transaction)

        ↓

Target Ledger Finalization TX
Payment 상태 조건부 반영
COMMIT

        ↓

TX-R2
Recovery Task lifecycle update
+ Recovery History FINISH
COMMIT
```

History START 저장 실패 시 Claim도 rollback되고, History FINISH 저장 실패 시 Task lifecycle transition도 commit되지 않습니다.

Target ledger finalization과 Recovery Task lifecycle update는 서로 다른 transaction입니다.

### 7.10 MANUAL_REVIEW Notification / Admin Requeue

`MANUAL_REVIEW` 전환 commit 이후 알림 처리를 수행합니다.

알림 실패가 이미 저장된 `MANUAL_REVIEW`를 rollback하지 않으며 `OWNERSHIP_LOST`는 MANUAL_REVIEW 알림 대상이 아닙니다.

Admin requeue:

```text
MANUAL_REVIEW
→ PENDING
```

underlying Payment ledger를 수정하거나 endpoint에서 Worker/VAN을 즉시 실행하지 않습니다.

### 7.11 Spring Batch / Scheduler

Spring Batch는:

```text
Candidate Discovery
→ Worker Processing
```

을 실행하는 orchestration 역할로 사용합니다.

Process Step은 `PROPAGATION_NOT_SUPPORTED`를 사용해 Batch transaction이 Worker의 VAN I/O를 감싸지 않도록 합니다.

Scheduler는 기본 비활성화되어 있습니다.

---

## 8. Race & Crash Scenarios

### 8.1 Human Approval Inquiry vs Recovery Finalization

대표 검증 범위는 Approval `UNKNOWN_TIMEOUT` 거래에서:

```text
Human Approval Inquiry path
vs
Recovery Approval finalization path
```

가 동일 `APPROVED` 결과를 반영하려는 두 실행 순서입니다.

```text
Human Approval Inquiry
FINAL_STATUS = 'UNKNOWN_TIMEOUT'

Recovery Approval finalization path
FINAL_STATUS IS NULL
OR FINAL_STATUS = 'UNKNOWN_TIMEOUT'
```

Recovery는 stale `PROCESSING`도 처리하므로 적용 범위가 더 넓습니다.

두 경로는 공통 Finalizer를 공유하지 않지만:

```text
conditional update
→ update miss
→ DB reread
→ persisted state 기준 판단
```

이라는 수렴 원칙을 공유합니다.

### 8.2 Stale Worker after Reclaim

```text
Worker A Claim
→ token A

lease expiry

Worker B Reclaim
→ token B

Worker A late return
→ fencing condition miss
→ OWNERSHIP_LOST
```

### 8.3 Terminal Commit 이후 Worker Crash

```text
VAN Inquiry
→ Payment terminal COMMIT
→ process crash
→ Recovery Task RUNNING
```

Reclaim한 Worker는 Payment DB를 먼저 다시 읽습니다.

```text
Task Reclaim
→ Payment DB reread
→ 이미 terminal
→ VAN Inquiry 재호출 없음
→ Task RESOLVED
```

### 8.4 Persisted-state Recovery

Recovery는 이전 Worker의 실행 위치를 추측하지 않고 현재 저장된 Payment 상태에서 다시 시작합니다.

---

## 9. Verification Scenarios

| Scenario | Failure / Race | Expected Result |
|---|---|---|
| Concurrent Approval ×20 | 동일 `posTrx` 동시 승인 | Payment attempt 1 / VAN Approval 1 |
| Approval Response Loss | VAN commit 후 response loss | `UNKNOWN_TIMEOUT → Inquiry → APPROVED` |
| Concurrent Cancel ×20 | 동일 원승인 동시 취소 | `CANCELLED 1 / ALREADY_CANCELLED 19 / VAN Cancel 1` |
| Cancel vs Reversal | 동일 원승인에서 두 operation 경쟁 | successful terminal operation 최대 1 |
| Lease Fencing | A lease expiry → B reclaim → A late write | A = `OWNERSHIP_LOST`, B 상태 유지 |
| Human Approval Inquiry vs Recovery Finalization | 동일 `APPROVED` 결과의 두 interleaving | persisted `APPROVED`로 수렴 |
| Recovery Crash Gap | Payment terminal commit 후 Worker crash | reclaim 후 VAN 재호출 없이 Task `RESOLVED` |

PostgreSQL integration test는 Testcontainers를 사용합니다.

이 검증은 거래 정합성, concurrency, failure recovery를 확인하기 위한 것이며 TPS / p95 성능 측정은 포함하지 않습니다.

---

## 10. Design Principles

```text
Timeout
→ failure가 아니라 uncertainty

Row Lock
→ serialization을 제공하지만 invariant 자체는 아님

Recovery
→ persisted state first

SKIP LOCKED
→ claim transaction 사이의 경쟁 제어

Lease
→ ownership expiry / reclaim

claimToken Fencing
→ stale Worker write 차단

Conditional UPDATE miss
→ 실패 단정 대신 DB reread

Automation Boundary
→ 안전하게 확정할 수 없으면 MANUAL_REVIEW
```

---

## 11. Known Limitations

- 실제 상용 VAN / PG / 카드사 운영 환경과 연동하지 않습니다.
- VAN Simulator를 사용하며 실제 proprietary 금융망 전문을 구현한 것은 아닙니다.
- 실제 다중 Payment Server instance 기반 HA 운영 환경을 검증하지 않았습니다.
- PostgreSQL 기반의 대표 concurrency primitive와 race 시나리오는 검증했지만 모든 경합·장애 조합을 포괄하는 검증은 아닙니다.
- MANUAL_REVIEW 알림은 structured logging 기반이며 Slack / Email / SMS 같은 외부 운영 채널과 연동하지 않았습니다.
- Human Approval Inquiry에서 VAN terminal과 persisted DB terminal이 충돌하는 경우 error log를 남기고 DB 상태를 기준으로 응답합니다. 별도의 `RECONCILIATION_CONFLICT` 응답이나 운영 알림 workflow는 구현하지 않았습니다.
- Force Resolve / 수기 정산 workflow는 구현하지 않았습니다.
- Partial Cancel은 구현하지 않았습니다.
- 매입 / 정산 / 대사 시스템은 구현하지 않았습니다.

이 문서는 현재 구현 범위의 거래 정합성과 failure recovery 설계를 설명하기 위한 것이며 실제 상용 결제 인프라 전체를 재현했다는 의미는 아닙니다.
