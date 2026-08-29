# VAN Approval Protocol v1

## 1. 목적

Payment Server가 VAN Simulator에 카드 승인 요청을 전달하고,
VAN Simulator가 승인 처리 결과를 반환하기 위한 TCP 전문을 정의한다.

승인 요청과 응답은 `README.md`에 정의한 공통 TCP framing 규칙을 따른다.

```text
[4-byte Length][UTF-8 JSON]
```

---

## 2. 승인 요청

### Message Type

```text
APPROVAL
```

### 방향

```text
Payment Server → VAN Simulator
```

### 예시

```json
{
  "protocolVersion": "1",
  "messageType": "APPROVAL",
  "requestId": "0f44f65403ab44f48267d33f2b6e8112",
  "posTrx": "2301-20260808-9999-0001",
  "attemptSeq": 1,
  "amount": 10000,
  "pan": "1234567890123456",
  "expiryYyMm": "2808"
}
```

### 필드 정의

| 필드                | 타입      | 필수 | 설명                      |
|-------------------|---------|---:|-------------------------|
| `protocolVersion` | String  |  Y | VAN Protocol 버전. 현재 `1` |
| `messageType`     | String  |  Y | `APPROVAL`              |
| `requestId`       | String  |  Y | Payment Server 요청 추적 ID |
| `posTrx`          | String  |  Y | POS 거래번호                |
| `attemptSeq`      | Integer |  Y | 동일 POS 거래 내 승인 시도 순번    |
| `amount`          | Integer |  Y | 승인 요청 금액                |
| `pan`             | String  |  Y | 카드 PAN 원문               |
| `expiryYyMm`      | String  |  Y | 카드 유효기간 YYMM            |

---

## 3. cardBin / cardLast4 처리

Payment Server는 승인 요청 전문에 `cardBin`, `cardLast4`를 별도로 전송하지 않는다.

두 값은 PAN으로부터 파생 가능한 정보이기 때문이다.

```text
Payment Server
      │
      │ pan
      ▼
VAN Simulator
      │
      ├─ cardBin
      └─ cardLast4
```

원본 PAN과 파생 정보를 동시에 전문에 포함하여 서로 불일치할 가능성을 만들지 않는다.

VAN Simulator가 승인 원장에 카드 식별 정보를 보관할 필요가 있는 경우
PAN 원문 대신 `cardBin`, `cardLast4`만 저장한다.

---

## 4. 승인 응답

### Message Type

```text
APPROVAL_RESPONSE
```

### 방향

```text
VAN Simulator → Payment Server
```

### APPROVED 예시

```json
{
  "protocolVersion": "1",
  "messageType": "APPROVAL_RESPONSE",
  "requestId": "0f44f65403ab44f48267d33f2b6e8112",
  "posTrx": "2301-20260808-9999-0001",
  "attemptSeq": 1,
  "vanTrxId": "VAN-20260808-000001",
  "status": "APPROVED",
  "approvalNo": "12345678",
  "declineCode": null,
  "respondedAt": "2026-08-08T18:30:00"
}
```

### DECLINED 예시

```json
{
  "protocolVersion": "1",
  "messageType": "APPROVAL_RESPONSE",
  "requestId": "0f44f65403ab44f48267d33f2b6e8112",
  "posTrx": "2301-20260808-9999-0002",
  "attemptSeq": 1,
  "vanTrxId": "VAN-20260808-000002",
  "status": "DECLINED",
  "approvalNo": null,
  "declineCode": "05",
  "respondedAt": "2026-08-08T18:31:00"
}
```

### UNKNOWN 예시

```json
{
  "protocolVersion": "1",
  "messageType": "APPROVAL_RESPONSE",
  "requestId": "0f44f65403ab44f48267d33f2b6e8112",
  "posTrx": "2301-20260808-9999-0003",
  "attemptSeq": 1,
  "vanTrxId": "VAN-20260808-000003",
  "status": "UNKNOWN",
  "approvalNo": null,
  "declineCode": null,
  "respondedAt": "2026-08-08T18:32:00"
}
```

### 필드 정의

| 필드                | 타입      |  필수 | 설명                                |
|-------------------|---------|----:|-----------------------------------|
| `protocolVersion` | String  |   Y | VAN Protocol 버전. 현재 `1`           |
| `messageType`     | String  |   Y | `APPROVAL_RESPONSE`               |
| `requestId`       | String  |   Y | 요청에서 전달받은 Request ID              |
| `posTrx`          | String  |   Y | 승인 요청의 POS 거래번호                   |
| `attemptSeq`      | Integer |   Y | 승인 요청 시도 순번                       |
| `vanTrxId`        | String  |   Y | VAN이 생성한 거래 식별자                   |
| `status`          | String  |   Y | `APPROVED`, `DECLINED`, `UNKNOWN` |
| `approvalNo`      | String  | 조건부 | 승인 성공 시 승인번호                      |
| `declineCode`     | String  | 조건부 | 카드사 거절 시 거절 코드                    |
| `respondedAt`     | String  |   Y | VAN 응답 생성 시각                      |

`respondedAt`은 ISO-8601 Local DateTime 형식을 사용한다.

예:

```text
2026-08-08T18:30:00
```

---

## 5. 승인 상태

### APPROVED

VAN이 카드사 승인 결과를 확정적으로 알고 있는 상태다.

```text
status      = APPROVED
approvalNo  = 필수
declineCode = null
```

VAN 승인 원장에는 `APPROVED`로 기록한다.

---

### DECLINED

VAN이 카드사로부터 거절 결과를 확정적으로 받은 상태다.

```text
status      = DECLINED
approvalNo  = null
declineCode = 필수
```

VAN 승인 원장에는 `DECLINED`로 기록한다.

---

### UNKNOWN

VAN도 카드사의 최종 승인 결과를 확정하지 못한 상태다.

대표적인 예는 VAN → 카드사 구간의 timeout이다.

```text
status      = UNKNOWN
approvalNo  = null
declineCode = null
```

VAN 승인 원장에는 `UNKNOWN`으로 기록한다.

`UNKNOWN`은 Payment Server의 `UNKNOWN_TIMEOUT`과 동일한 개념이 아니다.

예:

```text
카드사 응답 timeout

Payment Server          VAN Simulator
UNKNOWN_TIMEOUT   ←     UNKNOWN
```

반면 VAN이 승인을 완료한 뒤 Payment Server에 응답을 전달하지 못한 경우에는 다음과 같이 서로 다른 상태를 갖는다.

```text
VAN 응답 유실

Payment Server          VAN Simulator
UNKNOWN_TIMEOUT   ←     APPROVED
```

이 상태 차이를 후속 Inquiry를 통해 복구하는 것이 Release 4의 핵심 장애 시나리오다.

---

## 6. 승인 요청 멱등성 기준

승인 요청의 논리적 식별자는 다음 두 값의 조합으로 정의한다.

```text
posTrx + attemptSeq
```

동일한 `posTrx`, `attemptSeq` 요청이 다시 들어온 경우
VAN은 새로운 승인을 생성하지 않고 기존 처리 결과를 조회하여 재응답해야 한다.

예:

```text
1. Payment → VAN 승인 요청
2. VAN 승인 완료
3. VAN 원장 APPROVED 저장
4. 응답 유실
5. Payment 동일 승인 재요청
6. VAN 기존 원장 조회
7. 기존 APPROVED 결과 재응답
```

이에 따라 VAN 승인 원장은 향후 다음 UNIQUE 제약을 가진다.

```text
UNIQUE(pos_trx, attempt_seq)
```

동일한 거래 식별자로 요청했지만 금액 등 주요 요청 값이 기존 거래와 다른 경우는
정상적인 멱등 재요청으로 처리해서는 안 된다.

구체적인 payload conflict 정책은 VAN 승인 Service 구현 단계에서 확정한다.

---

## 7. 네트워크 장애와 승인 상태

다음 값들은 승인 `status`가 아니다.

```text
DROP_RESPONSE
DELAY
DISCONNECT
CONNECTION_FAILURE
READ_TIMEOUT
```

이 값들은 Transaction Plane의 업무 결과가 아니라 네트워크 동작 또는 관찰 결과이다.

예:

```text
issuerResult      = APPROVED
transportBehavior = DROP_RESPONSE
```

VAN 내부 처리는 다음과 같다.

```text
승인 처리
   ↓
VAN_APPROVAL = APPROVED
   ↓
DB Commit
   ↓
응답 전송 단계에서 DROP
```

Payment Server에서는 응답을 받지 못하므로 `UNKNOWN_TIMEOUT`이 될 수 있다.

중요한 원칙은 다음과 같다.

```text
거래 결과의 DB commit
≠
응답 전달 성공 여부
```

이 둘을 분리해야 응답 유실 장애를 실제와 유사하게 재현할 수 있다.

---

## 8. 민감정보

승인 요청에는 PAN과 expiry가 포함되지만 다음 행위는 금지한다.

```text
PAN 원문 로그
expiry 원문 로그
PAN 원문 DB 저장
expiry 원문 DB 저장
```

VAN 업무 로그에는 필요한 경우 아래 값만 사용할 수 있다.

```text
requestId
posTrx
attemptSeq
vanTrxId
amount
cardBin
cardLast4
status
approvalNo
```

---

## 9. 이번 명세에서 다루지 않는 범위

다음 기능은 Approval Protocol v1에 포함하지 않는다.

```text
Inquiry 전문
Cancel 전문
Scenario Control API
TCP Server 구현
TCP Client 구현
VAN JPA Entity
VAN Repository
QueryDSL
네트워크 장애 주입 구현
```

각 기능은 이후 Release 4 단계에서 별도로 구현한다.
