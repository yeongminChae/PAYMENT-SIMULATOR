# VAN TCP Protocol

## 1. 목적

이 문서는 Payment Server와 VAN Simulator 사이의 Transaction Plane TCP 통신 규칙을 정의한다.

Payment Server와 VAN Simulator는 Java DTO나 공통 모듈을 공유하지 않는다.
양쪽 애플리케이션은 이 문서에 정의된 네트워크 계약을 각각 독립적으로 구현한다.

VAN Simulator의 Control Plane HTTP API는 테스트 시나리오를 설정하기 위한 별도 인터페이스이며,
실제 승인·조회·취소 거래는 Transaction Plane TCP 통신을 통해 처리한다.

---

## 2. 기본 통신 규칙

| 항목                 | 값                    |
|--------------------|----------------------|
| Transport          | TCP                  |
| Payload Format     | JSON                 |
| Character Encoding | UTF-8                |
| Framing            | 4-byte Length Prefix |
| Byte Order         | Big Endian           |
| Protocol Version   | `1`                  |
| Request Tracking   | `requestId`          |

운영체제별 줄바꿈 차이에 의존하지 않도록 `\n`, `\r\n` 등의 delimiter는 메시지 경계로 사용하지 않는다.

---

## 3. Message Framing

각 TCP 메시지는 다음과 같이 구성한다.

```text
┌───────────────────────┬─────────────────────────────┐
│ 4-byte Payload Length │ UTF-8 JSON Payload          │
└───────────────────────┴─────────────────────────────┘
```

앞의 4 byte는 뒤따르는 JSON payload의 길이를 나타낸다.

Payload Length는 문자열의 문자 수가 아니라
UTF-8 인코딩 후 실제 byte 배열의 길이를 기준으로 계산한다.

예:

```java
byte[] payload = json.getBytes(StandardCharsets.UTF_8);
int payloadLength = payload.length;
```

Length 값은 Big Endian byte order로 전달한다.

수신 측에서는 다음 순서로 메시지를 읽는다.

```text
1. 정확히 4 byte를 읽는다.
2. Big Endian 정수로 payload 길이 N을 계산한다.
3. 정확히 N byte를 읽는다.
4. N byte를 UTF-8 문자열로 변환한다.
5. JSON payload를 역직렬화한다.
```

TCP의 `read()` 한 번이 요청한 전체 byte를 반환한다는 보장은 없으므로,
수신 구현은 필요한 길이만큼 모두 읽을 때까지 처리해야 한다.

---

## 4. 요청-응답 방식

Protocol v1에서는 하나의 거래 요청에 하나의 거래 응답이 대응한다.

```text
Payment Server
      │
      │ Request Frame
      ▼
VAN Simulator
      │
      │ Response Frame
      ▼
Payment Server
```

동일 TCP connection의 재사용 여부는 구현에서 결정할 수 있다.

다만 Protocol v1에서는 하나의 connection에서 여러 요청을 동시에 전송하는
request pipelining은 사용하지 않는다.

---

## 5. 공통 필드

모든 Transaction Plane 요청에는 다음 정보를 포함한다.

### `protocolVersion`

현재 프로토콜 버전이다.

```json
"protocolVersion": "1"
```

Protocol v1을 구현하는 애플리케이션은 지원하지 않는 버전을 정상 거래로 처리해서는 안 된다.

### `messageType`

현재 전문의 종류를 식별한다.

예:

```text
APPROVAL
APPROVAL_RESPONSE
```

향후 Inquiry와 Cancel 전문도 동일한 방식으로 별도 messageType을 정의한다.

### `requestId`

Payment Server의 요청 추적 ID이다.

Payment Server의 HTTP 요청에서 사용하던 `requestId`를 VAN TCP 전문까지 전달한다.

```text
POS / Client
      │
      │ X-REQUEST-ID
      ▼
Payment Server
      │
      │ requestId
      ▼
VAN Simulator
```

VAN Simulator는 TCP 요청을 처리하는 동안 전달받은 requestId를 MDC에 설정하여
Payment Server와 VAN Simulator의 로그를 동일한 ID로 추적할 수 있도록 한다.

실제 TCP requestId → MDC 적용은 Transaction Plane 구현 단계에서 추가한다.

---

## 6. 업무 결과와 통신 장애의 분리

거래의 업무 결과와 네트워크 전달 상태는 서로 다른 개념으로 취급한다.

예:

```text
VAN 업무 결과       TCP 전달 상태
---------------------------------
APPROVED            NORMAL
APPROVED            DROP_RESPONSE
APPROVED            DELAY
APPROVED            DISCONNECT
DECLINED            NORMAL
UNKNOWN             NORMAL
```

`DROP_RESPONSE`, `DELAY`, `DISCONNECT` 같은 값은 VAN 거래 결과가 아니다.

따라서 Transaction Plane의 승인 응답 status에는 포함하지 않는다.

이러한 네트워크 장애는 향후 VAN Control Plane의 Scenario 설정을 통해 별도로 제어한다.

예를 들어:

```text
VAN 승인 결과 = APPROVED
TCP 동작       = DROP_RESPONSE
```

이면 VAN의 승인 원장에는 `APPROVED`가 저장되지만,
Payment Server는 응답을 받지 못해 거래를 미확정 상태로 처리할 수 있다.

---

## 7. 민감정보 정책

승인 요청 전문에는 카드사 승인을 시뮬레이션하기 위해 PAN과 유효기간이 포함될 수 있다.

하지만 전문에 포함된다는 사실이 로그 또는 데이터베이스 저장을 허용한다는 의미는 아니다.

다음 값은 원문 로그 기록을 금지한다.

```text
PAN
expiry
secret key
DB password
credential
```

VAN은 필요한 경우 PAN으로부터 다음 값만 파생하여 사용할 수 있다.

```text
cardBin
cardLast4
```

PAN과 expiry 원문을 VAN 거래 원장에 저장하지 않는다.

---

## 8. 현재 Protocol v1 범위

현재 단계에서는 승인(Approval) 전문만 상세 정의한다.

```text
approval-v1.md
```

다음 항목은 이후 별도 명세에서 정의한다.

```text
Inquiry
Cancel
공통 Protocol Error Response
Control Plane Scenario API
```

Java DTO, TCP Server/Client, JPA Entity, Repository 및 업무 Service 구현은
이 프로토콜 문서와 별도의 구현 단계에서 진행한다.

```

---

## `docs/van-protocol/approval-v1.md`

```markdown
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
  "posTrx": "202608080001",
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
  "posTrx": "202608080001",
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
  "posTrx": "202608080002",
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
  "posTrx": "202608080003",
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
