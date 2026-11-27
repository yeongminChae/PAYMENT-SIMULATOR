# 05. Recovery 스케줄러 · 알림 · 추적 ID

- 상태: 결정
- 날짜: 2026-11-27
- 플랫폼 근거: ADR-0017, ADR-0021, ADR-0042, ADR-0043, standards.md (Retail-Platform)
- 해소하는 차이: D11 · D12 · D14 (`00-플랫폼-차이.md`)

## 요점

- **DB는 PostgreSQL 하나.** SQLite를 없앤다. Recovery는 기본으로 켠다.
- **알림은 앱이 Slack 웹훅으로 직접 보낸다.** 웹훅 주소가 없으면 로그만 남긴다.
- **백그라운드 작업도 추적 ID를 가진다.** 스케줄러 실행마다 ID를 만들어 로그와 ERP 호출에 싣는다.

## 1. PostgreSQL로 통일 · Recovery 켜기

- Recovery · 아웃박스 · 운영 요청이 쓰는 `FOR UPDATE SKIP LOCKED`, 부분 유니크 인덱스(04 4장)를 SQLite가 제대로 지원하지 않는다. 스키마를 두 벌 관리하지 않는다.
- 바꿀 것:
  - `application.yml` 기본 데이터소스를 PostgreSQL로, `application-postgres.yml` 내용을 기본으로 합친다.
  - `db/schema.sql` · `db/data.sql` · `sqlite-jdbc` 의존성 제거. 스키마는 `schema-postgres.sql` 하나.
  - Recovery · 관리 API의 `@Profile("postgres")` 제거.
  - SQLite로 돌던 통합 테스트는 Testcontainers PostgreSQL로 옮긴다.
  - 로컬 실행은 `compose.yml`의 PostgreSQL을 띄운 뒤 `bootRun`.
- `payment.recovery.schedule.enabled` 기본값을 `true`로 바꾼다 (ADR-0021). 단위 · 슬라이스 테스트 프로파일에서만 끈다. 주기는 1분 그대로 (ADR-0042).
- 같은 방식으로 결제 상태 전송(02) · 운영 요청 수집(04) 스케줄러도 기본 `true`, 테스트에서 끈다.

## 2. 알림

### 2.1 무엇을 언제

| 알림 | 계기 | 근거 |
|---|---|---|
| `MANUAL_REVIEW` 전환 | Recovery Task 전환 직후 | ADR-0021 |
| 아웃박스 격리 (결제 상태 · 종료 신호) | 격리 직후 | ADR-0043 |
| 아웃박스 지연 | 가장 오래된 미전송 건이 30분 넘음 | ADR-0043 |
| 운영 확정 충돌 (`TERMINAL_CONFLICT`) | 04 3.1 | 04 |
| 사람 조회 vs 저장 상태 충돌 | `/inquiry` 결과가 저장된 terminal과 다름 | DESIGN.md 11장 알려진 한계 해소 |
| ERP 연결 실패 지속 | 운영 요청 수집이 30분 연속 실패 | 04 |

### 2.2 보내는 방법

- 알림도 **아웃박스처럼** 다룬다. 계기 트랜잭션에서 `OPS_ALERT`에 한 줄 적고, 1분 주기 발송 작업이 Slack으로 보낸다.
  - 알림 때문에 업무 트랜잭션이 실패하지 않는다. 업무가 롤백되면 알림도 함께 사라진다.
  - 기존 `RecoveryNotificationService` 인터페이스를 넓혀 쓰고, 구현을 로그 → `OPS_ALERT` 기록으로 바꾼다.
- 기준값 알림(아웃박스 30분, ERP 연결 30분)은 각 전송 · 수집 작업이 실행 끝에 조건을 확인해 `OPS_ALERT`에 적는다.
- **중복 억제:** 같은 알림 키(종류 + 대상)는 1시간에 한 번만 보낸다. 지연이 풀렸다가 다시 생기면 새로 보낸다.
- **발송 실패:** 간격을 늘리며 재시도한다(최대 5분). 24시간 지나도 실패하면 로그만 남기고 포기한다. 알림은 업무 데이터가 아니다.
- 웹훅 주소는 환경변수 `PAYMENT_ALERT_SLACK_WEBHOOK_URL`로 받는다. 없으면 발송 작업이 로그로만 남긴다.

### 2.3 메시지 (예)

```text
[결제] MANUAL_REVIEW 전환
posTrx 1000-20261126-0001-0199-01 · 승인 시도 1 · 재시도 5회 · 사유 RETRY_EXHAUSTED
→ VAN · 카드사 확인 후 ERP에서 운영 확정 요청
```

- 카드 정보는 넣지 않는다 (ADR-0006). posTrx · 사유 · 다음 할 일만.

## 3. 지표 (대시보드용, 최소)

standards.md의 지표 항목만 Actuator + Micrometer로 노출한다 (`/actuator/prometheus`). 알림은 2장이 맡으므로 지표 쪽 알림 규칙은 두지 않는다.

- 승인 결과별 건수 (승인 · 거절 · 결과 불명)
- Recovery Task 상태별 건수, `MANUAL_REVIEW` 건수
- 아웃박스 미전송 건수 · 가장 오래된 대기 시간 (결제 상태 · 종료 신호)

## 4. 추적 ID

HTTP 요청은 지금처럼 `X-REQUEST-ID`를 받아 쓴다 (ADR-0017). 백그라운드 작업은 스스로 만든다.

| 작업 | MDC `requestId` | ERP 호출의 `X-REQUEST-ID` |
|---|---|---|
| Recovery 실행 1회 | `rec-{UUID 8자리}` | — |
| Recovery Task 처리 1건 | `rec-{taskId}-{tryNo}` | — |
| 결제 상태 · 종료 신호 전송 1회 | `push-{UUID 8자리}` | 같은 값 |
| 운영 요청 수집 1회 | `ops-poll-{UUID 8자리}` | 같은 값 |
| 운영 요청 실행 1건 | `ops-{ERP 요청 ID}` | — |
| 알림 발송 1회 | `alert-{UUID 8자리}` | — |

- 로그에서 `rid=ops-OPR-…`로 검색하면 운영 요청 하나의 수집 · 검증 · VAN 호출 · 종료 신호가 한 줄로 이어진다.
- 작업이 끝나면 MDC를 지운다 (`RequestIdFilter`와 같은 규칙).

## 5. 구현 작업

1. PostgreSQL 통일 (설정 · 스키마 · 의존성 · 프로파일 제거 · 테스트 이전)
2. 스케줄러 기본 켜기, 테스트 프로파일에서 끄기
3. `OPS_ALERT` · 알림 발송 작업 · Slack 클라이언트 · 중복 억제
4. 각 계기에서 알림 기록 (Recovery, 아웃박스 격리 · 지연, 운영 확정 충돌, 조회 충돌, ERP 연결 실패)
5. Actuator · Micrometer 지표
6. 백그라운드 작업 MDC 추적 ID, ERP 호출 헤더
7. 테스트: 웹훅 주소 없음 → 로그만, WireMock Slack 실패 → 재시도, 같은 알림 1시간 내 1회, 업무 롤백 시 알림 없음, 운영 요청 하나가 같은 `rid`로 이어짐
