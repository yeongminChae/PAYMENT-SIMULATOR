# 로깅 및 Request ID 정책

## 적용 범위와 책임

Payment Server와 VAN Simulator는 Java 로깅 모듈, Logback 설정, 로그 파일을 공유하지 않는다. 각 애플리케이션이 자체 로깅 구현과 파일을 관리하며, 거래 추적에 필요한 식별자와 민감정보 정책만 일관되게 맞춘다.

공통 로그 형식은 timestamp, thread, level, logger, application name, HTTP request ID, message를 포함한다. application과 HTTP request ID는 각각 `[app=...]`, `[rid=...]` 형식으로 출력한다.

Payment Server는 `payment-sim.log`, VAN Simulator는 `van-simulator.log`를 사용한다. 애플리케이션 파일 로그는 일 단위로 rolling하며 30일간 보관하고, 기본 로그 디렉터리는 `./logs`이다. `LOG_DIR` 환경변수로 경로를 변경할 수 있다.

콘솔은 INFO 이상을 출력하고, `com.chaeyeongmin` 애플리케이션 패키지는 파일에 DEBUG 이상을 기록한다.

## Correlation ID 정책

### HTTP Request ID

Payment Server의 HTTP 요청과 VAN Simulator Control Plane의 HTTP 요청은 각각 동일한 HTTP Request ID 규칙을 사용한다.

- Header: `X-REQUEST-ID`
- 요청 헤더가 존재하고 공백이 아니면 해당 값을 사용한다.
- 요청 헤더가 없거나 공백이면 UUID를 생성하고 하이픈(`-`)을 제거해서 사용한다.
- 요청 처리 중에는 MDC의 `requestId` key에 저장한다.
- 응답의 `X-REQUEST-ID` 헤더에도 동일한 값을 반환한다.
- 요청 처리가 끝나면 `finally`에서 MDC의 `requestId`를 제거한다.

HTTP Request ID는 같은 JVM의 HTTP 요청 흐름을 추적하기 위한 식별자다.

### TCP protocol correlation ID

Payment Server와 VAN Simulator 사이 Transaction Plane의 TCP 전문에는 별도의 protocol `requestId`를 포함한다.

Approval / Inquiry / Cancel / Reversal operation은 TCP 요청에 correlation ID를 넣고, 응답의 protocol version, message type과 거래 식별자뿐 아니라 request ID도 현재 요청과 일치하는지 검증한다.

이 TCP 식별자는 HTTP MDC의 `requestId`와 같은 개념으로 취급하지 않는다.

- HTTP correlation: 로그 패턴의 `[rid=...]`
- TCP protocol correlation: 거래 로그의 `vanRequestId=...`

따라서 현재 구현은 HTTP Request ID를 그대로 VAN Transaction Plane까지 전파하는 구조가 아니라, HTTP 요청 추적과 TCP 거래 correlation을 분리한다.

## 거래 로그 범위

거래 상태 자체의 source of truth는 DB이며, 로그는 처리 구간과 실패 위치를 추적하기 위한 관측 수단으로 사용한다.

Payment Server는 주요 흐름을 다음 prefix로 기록한다.

- `[van]`: Approval / Inquiry / Cancel / Reversal TCP 요청, 응답, request-not-sent, timeout
- `[reversal]`: prepare 결과, reuse/conflict, VAN 호출 결과, timeout, finalization/update miss
- `[inquiry]`: Approval UNKNOWN_TIMEOUT 조회와 finalization/reread
- `[cancel-inquiry]`: Cancel UNKNOWN_TIMEOUT 조회와 finalization/reread
- `[recovery]`: claim, handler result, resolved, retry-wait, manual-review, ownership-lost

VAN Simulator는 `[van-tcp]` prefix로 TCP 요청 수신과 각 거래 처리 결과를 기록하며, Approval의 의도적인 response drop 시나리오도 별도 로그로 남긴다. Dispatcher 수준의 메시지 분류 로그는 DEBUG로 둔다.

Approval / Cancel의 주요 업무 이벤트는 `PAYMENT_EVENT_LOG`에도 저장하며, 애플리케이션 로그는 TCP/Reversal/Inquiry/Recovery의 실행 흐름과 장애 구간을 보완한다.

## 로그 레벨 원칙

- INFO: 정상적인 거래 단계와 주요 상태 흐름
- DEBUG: dispatcher처럼 상세 추적에 유용하지만 정상 운영에서 필수는 아닌 정보
- WARN: timeout, request-not-sent, MANUAL_REVIEW 등 운영 확인이 필요한 비정상 또는 불확실 상황
- ERROR: persisted terminal state와 외부 확인 결과가 모순되는 등 정상적인 재조회 수렴으로 해석할 수 없는 상황

동시 처리로 conditional update가 실패한 뒤 DB reread로 정상 수렴할 수 있는 경우는 그 자체만으로 WARN/ERROR로 올리지 않는다.

## 민감정보 로깅 정책

다음 값은 애플리케이션 로그에 기록하지 않는다.

- PAN 원문
- expiry 원문
- card fingerprint
- secret key
- 데이터베이스 비밀번호와 기타 credential
- raw TCP / HTTP payload
- request / response DTO 전체 `toString()`

거래 추적에는 필요한 최소 식별자와 상태 값만 사용한다. 예시는 다음과 같다.

- `posTrx`
- `attemptSeq`
- `vanTrxId`
- `status`
- `resultCode`
- `requestId` / `vanRequestId`
- Recovery `taskId`, `targetType`, `targetTrxNo`

Hibernate bind parameter TRACE처럼 민감한 값이 노출될 수 있는 로깅은 활성화하지 않는다.

## 로그 안전성

외부 입력에서 유래한 식별자나 메시지에 newline 등 제어문자가 포함되면 로그 라인을 위조하거나 수집 파이프라인을 깨뜨릴 수 있다.

두 애플리케이션의 Logback pattern은 MDC `requestId`와 최종 message에 포함된 제어문자(`\p{Cntrl}`)를 `_`로 치환한 뒤 출력한다.

이 처리는 로그 출력 안전성을 위한 방어이며, 거래 correlation 검증이나 비즈니스 상태 판단을 대신하지 않는다.
