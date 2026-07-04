# 카드결제 시뮬레이터

> POS 거래번호 발급부터 카드 결제 승인, 조회, 취소까지 이어지는 결제 서버 흐름을 구현한 Spring Boot 백엔드 프로젝트입니다.
> 단순 CRUD가 아니라 결제 도메인의 **멱등성, 미확정 거래, 중복 취소 방어, 카드 원문 미저장, DB 상태 정합성**을 다루는 것을 목표로 했습니다.

## 핵심 구현

- POS 거래번호 발급 API
- 카드 승인 / 조회 / 취소 API
- 동일 승인 요청 멱등 처리
- `UNKNOWN_TIMEOUT` 미확정 거래 저장 및 후속 조회
- 승인 완료 원거래 전체취소
- 동일 원거래 중복 취소 방어
- 취소 요청 카드와 원승인 카드 동일성 검증
- HMAC-SHA256 기반 `cardFingerprint` 저장 및 비교
- 카드 원문/PAN 미저장
- 승인/취소 주요 이벤트 로그 기록
- DB 조건부 UPDATE와 UNIQUE 제약 기반 상태 정합성 방어
- 단위 테스트 / 통합 테스트 기반 회귀 검증

## 기술 스택

| 구분 | 기술 |
| --- | --- |
| Language | Java 21 |
| Framework | Spring Boot 3.5.9 |
| API | Spring Web, Validation |
| Persistence | MyBatis, Spring JDBC |
| Database | SQLite |
| Test | JUnit 5, Spring Boot Test, MyBatis Test |
| Logging | Logback, MDC RequestId |

## 시스템 흐름

```text
POS Client
→ Payment API
→ Service
→ Repository / MyBatis
→ SQLite
→ VAN Simulator
```

VAN은 실제 외부 연동이 아니라, 승인/조회/취소 결과를 재현하기 위한 `SimulatedVanGateway`로 구현했습니다.

## 주요 업무 흐름

### 1. POS 거래번호 발급

```text
POS 거래번호 발급 요청
→ storeCd / bizDate / posNo 검증
→ 점포 + 영업일 + POS 번호 기준 시퀀스 증가
→ POS 거래번호 반환
```

`storeCd`, `bizDate`, `posNo` 기준으로 POS 거래번호를 발급합니다. 요청값 검증 실패와 시퀀스 범위 이상은 `BusinessException` 기반으로 표준 응답합니다.

### 2. 승인

```text
승인 요청
→ 입력값 검증
→ 기존 결제시도 조회
→ 신규 attemptSeq 발급
→ PAYMENT_ATTEMPT 저장
→ VAN 승인 요청
→ FINAL_STATUS 조건부 확정
→ 응답 반환
```

승인 멱등성은 `posTrx` 기준 기존 결제시도를 조회한 뒤, 금액과 카드 fingerprint가 같은 요청이면 DB 결과를 재응답하고, 다른 payload면 거래번호 재사용으로 차단합니다.

### 3. 조회

```text
조회 요청
→ 결제시도 조회
→ 확정 상태면 DB 기준 재응답
→ UNKNOWN_TIMEOUT이면 VAN Inquiry 후속조회
→ 결과 반환
```

`UNKNOWN_TIMEOUT`은 실패가 아니라 결과 미확정 상태로 관리합니다. 확정된 거래는 외부 조회 없이 DB 기준으로 재응답하고, 미확정 거래만 VAN Inquiry 대상으로 처리합니다.

### 4. 취소

```text
취소 요청
→ 원승인 거래 조회
→ 원승인 APPROVED 여부 검증
→ 취소 요청 카드 동일성 검증
→ PAYMENT_CANCEL PENDING 선저장
→ VAN 취소 요청
→ CANCEL_STATUS 조건부 확정
→ 응답 반환
```

동일 원거래에 대한 취소 요청이 반복되면 기존 `PAYMENT_CANCEL` row를 기준으로 재응답합니다. 취소 요청 카드가 원승인 카드와 다르면 VAN 취소를 호출하지 않고 차단합니다.

## 설계 포인트

### 멱등성

동일 요청이 반복되면 외부 VAN을 다시 호출하지 않고 DB에 저장된 결과를 재응답합니다.

- 승인: `posTrx` 기준 기존 결제시도를 조회하고, 금액과 카드 fingerprint가 같으면 재응답
- 취소: `originalPosTrx + originalAttemptSeq` 기준 기존 취소 row가 있으면 재응답

### DB 기준 상태 정합성

승인과 취소 결과는 단순히 VAN 응답을 그대로 반환하지 않고, DB에 확정 저장된 상태를 기준으로 응답합니다.

- 승인 확정: `FINAL_STATUS IS NULL` 조건부 UPDATE
- 취소 확정: `CANCEL_STATUS = 'PENDING'` 조건부 UPDATE
- 중복 방어: UNIQUE 제약과 재조회 복구 로직 사용

이를 통해 중복 확정, 중복 취소, update miss 상황을 방어합니다.

### 카드 원문 미저장

카드 원문/PAN은 DB, 로그, 응답에 저장하지 않습니다.

승인 시 HMAC-SHA256 기반 `cardFingerprint`를 저장하고, 취소 시 요청 카드의 fingerprint를 다시 생성해 원승인 카드와 동일한지 비교합니다.

fingerprint가 없는 기존 거래에 한해서만 BIN8/last4 fallback을 허용합니다.

### 상태값 DB 제약

정본 상태 컬럼에는 CHECK 제약을 적용했습니다.

- `PAYMENT_ATTEMPT.FINAL_STATUS`
  - `APPROVED`
  - `DECLINED`
  - `UNKNOWN_TIMEOUT`
  - `NULL`은 처리 중 상태로 사용
- `PAYMENT_CANCEL.CANCEL_STATUS`
  - `PENDING`
  - `CANCELLED`
  - `CANCEL_DECLINED`

이벤트 로그의 상태 스냅샷은 확장 가능성을 고려해 CHECK 제약을 두지 않았습니다.

### 이벤트 로그와 요청 추적

승인/취소 처리 과정의 주요 이벤트를 `PAYMENT_EVENT_LOG`에 기록합니다. rollback되는 충돌 이벤트는 별도 listener를 통해 기록합니다.

또한 `RequestIdFilter`와 MDC를 사용해 요청 단위 로그 추적이 가능하도록 구성했습니다.

## API 요약

| API | Method | Path | 설명 |
| --- | --- | --- | --- |
| POS 거래번호 발급 | POST | `/api/v1/pos-trx/issue` | 일반 POS 거래번호 발급 |
| POS EOT 거래번호 발급 | POST | `/api/v1/pos-trx/eot` | EOT용 POS 거래번호 발급 |
| 승인 | POST | `/api/v1/payments/approve` | 카드 승인 요청 처리 |
| 조회 | POST | `/api/v1/payments/inquiry` | 결제시도 상태 조회 |
| 취소 | POST | `/api/v1/payments/cancel` | 승인 완료 원거래 전체취소 |

## 주요 테이블

| 테이블 | 역할 |
| --- | --- |
| `POS_TRX_SEQUENCE` | 점포/영업일/POS 번호 기준 거래번호 시퀀스 |
| `PAYMENT_ATTEMPT` | 승인 시도 및 최종 승인 상태 저장 |
| `PAYMENT_EXTERNAL_INFO` | 카드 BIN, last4, masked card number, 카드사 식별 정보, VAN provider 스냅샷 저장 |
| `PAYMENT_CANCEL` | 취소 요청 및 취소 결과 저장 |
| `PAYMENT_EVENT_LOG` | 승인/취소 주요 이벤트 기록 |
| `BIN_CATALOG` | BIN 기반 카드사 식별 |

## 테스트

전체 테스트 실행:

```bash
CARD_SECRET_KEY=local-dev-card-fingerprint-secret-key-32bytes ./gradlew test
```

주요 검증 시나리오:

- POS 거래번호 발급 및 입력값 검증
- 승인 성공 / 거절 / `UNKNOWN_TIMEOUT`
- 동일 승인 요청 재응답
- 다른 payload의 동일 거래번호 재사용 차단
- 확정 거래 조회 시 DB 재응답
- `UNKNOWN_TIMEOUT` 거래 후속조회
- 승인 완료 원거래 취소 성공
- 거절 원거래 취소 차단
- 동일 원거래 중복 취소 방어
- C5 insert conflict 복구
- C7 update empty 복구
- 취소 요청 카드 fingerprint mismatch 차단
- 이벤트 로그 기록 및 rollback 이후 충돌 이벤트 기록

주요 테스트 클래스:

- `PaymentFlowIntegrationTest`
- `Mvp2PaymentFlowIntegrationTest`
- `PaymentApprovalServiceImplIdempotencyTest`
- `PaymentCancelServiceImplC5ConflictTest`
- `PaymentCancelServiceImplC7UpdateEmptyTest`
- `CardFingerprintPolicyTest`
- `PosTrxServiceImplTest`

## 실행 방법

로컬 실행 시 `CARD_SECRET_KEY` 환경변수가 필요합니다.

```bash
CARD_SECRET_KEY=local-dev-card-fingerprint-secret-key-32bytes ./gradlew bootRun
```

`CARD_SECRET_KEY`는 HMAC 기반 `cardFingerprint` 생성에 사용됩니다. 실제 운영 secret은 코드나 저장소에 커밋하지 않습니다.

## 한계와 개선 방향

현재 프로젝트는 결제 흐름 학습과 검증을 위한 로컬 시뮬레이터입니다.

- DB는 SQLite 기반으로 구성되어 운영 DB의 동시성/락 특성과는 차이가 있습니다.
- VAN은 실제 외부 연동이 아니라 `SimulatedVanGateway` 기반 시뮬레이터입니다.
- 승인/취소 흐름에서 외부 VAN 호출과 DB 트랜잭션 경계는 실무 확장 시 분리할 필요가 있습니다.
- 부분취소, 망취소/Reversal, 정산/매입, 실제 VAN 전문 연동은 후속 개선 범위입니다.
- 장기 미확정 `UNKNOWN_TIMEOUT` 거래에 대한 운영 재처리 배치/API는 후속 개선 후보입니다.
