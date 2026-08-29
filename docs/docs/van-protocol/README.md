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
