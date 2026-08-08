# 로깅 및 Request ID 정책

## 적용 범위와 책임

Payment Server와 VAN Simulator는 Java 로깅 모듈, Logback 설정, 로그 파일을 공유하지 않는다. 각 애플리케이션이 자체 로깅 구현을 관리하고 서로 다른 로그 파일에 기록한다. 두 애플리케이션 사이에서는 로그 필드와 Request ID 전파 정책만 동일하게 맞춘다.

공통 로그 필드는 timestamp, thread, level, logger, application name, request ID, message로 구성한다. 각 로그가 어느 애플리케이션에서 발생했는지와 요청 흐름을 식별할 수 있도록 application과 request ID는 각각 `[app=...]`, `[rid=...]` 형식으로 출력한다.

콘솔 로그는 root level `INFO`를 사용한다. 애플리케이션 파일 로그는 `com.chaeyeongmin` 패키지를 `DEBUG` 레벨로 기록하고, 일 단위로 rolling하며 30일간 보관한다. 기본 로그 디렉터리는 `./logs`이며, `LOG_DIR` 환경변수로 변경할 수 있어 특정 절대경로에 실행 환경이 종속되지 않도록 한다.

Payment Server는 `payment-sim.log`, VAN Simulator는 `van-simulator.log`를 사용한다. 두 JVM이 동일한 로그 파일에 기록해서는 안 된다.

## Request ID 전파

Payment Server의 HTTP 요청과 VAN Simulator Control Plane의 HTTP 요청은 서로 독립적으로 동일한 Request ID 정책을 사용한다.

- Header 이름: `X-REQUEST-ID`
- 요청 헤더가 존재하고 공백이 아니면 해당 값을 그대로 사용한다.
- 요청 헤더가 없거나 공백이면 UUID를 생성하고 하이픈(`-`)을 제거해서 사용한다.
- 요청 처리 중에는 해당 값을 MDC의 `requestId` key에 저장한다.
- 응답의 `X-REQUEST-ID` 헤더에도 동일한 값을 반환한다.
- 요청 처리가 끝나면 `finally` 블록에서 MDC의 requestId를 반드시 제거한다.

Payment Server와 VAN Simulator 사이에 동일한 Request ID를 전달하면 코드나 로그 파일을 공유하지 않더라도 하나의 요청 흐름을 두 시스템에 걸쳐 추적할 수 있다.

향후 Transaction Plane의 TCP 프로토콜에서도 동일한 Request ID를 전달할 예정이다. TCP Request ID 전파는 이번 변경에서는 의도적으로 구현하지 않는다.

## 민감정보 로깅 정책

PAN 원문, 유효기간(expiry) 원문, secret key, 데이터베이스 비밀번호 및 기타 credential은 로그에 기록하지 않는다.

특히 Hibernate bind parameter `TRACE` 로깅은 민감한 값이 노출될 수 있으므로 활성화하지 않는다.

향후 거래 로그를 구현할 때 로그에 기록할 수 있는 식별자와 값은 다음으로 제한한다.

- `posTrx`
- `attemptSeq`
- `vanTrxId`
- `status`
- `amount`
- `cardBin`
- `cardLast4`
- `approvalNo`
- `requestId`

이번 정책 변경에는 실제 거래 로그 구현 자체는 포함하지 않는다.