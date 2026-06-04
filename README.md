# 카드결제 시뮬레이터

> POS → 결제서버 → VAN 시뮬레이터 흐름을 단순화하여 구현한 카드결제 백엔드 MVP입니다.
> 승인(Approve), 조회(Inquiry), 취소(Cancel)의 핵심 상태 전이와 멱등성, 정합성, UNKNOWN_TIMEOUT, 중복 취소 방어를 구현하고 테스트했습니다.

---

## 1. 프로젝트 개요

이 프로젝트는 실제 카드결제 업무에서 발생할 수 있는 승인, 조회, 취소 흐름을 학습하고 포트폴리오로 정리하기 위해 만든 결제 시뮬레이터입니다.

단순 CRUD가 아니라, 결제 서버에서 중요한 아래 관심사를 직접 설계하고 구현하는 것을 목표로 했습니다.

* 동일 결제시도 중복 승인 방지
* 승인/거절/미확정 상태 저장
* UNKNOWN_TIMEOUT 거래 조회
* APPROVED 원거래 취소
* DECLINED 원거래 취소 차단
* 동일 원거래 중복 취소 방어
* API 응답과 DB 상태 정합성 검증

---

## 2. 기술 스택

| 구분            | 기술                                       |
| ------------- | ---------------------------------------- |
| Language      | Java 17                                  |
| Framework     | Spring Boot                              |
| Persistence   | MyBatis                                  |
| Database      | SQLite                                   |
| Test          | JUnit5, Mockito, SpringBootTest, MockMvc |
| API Test      | Postman                                  |
| Documentation | Notion, Markdown                         |

---

## 3. 핵심 설계 포인트

### 3.1 멱등성

승인 요청은 `(posTrx, attemptSeq)` 기준으로 결제시도를 식별합니다.

동일 결제시도에 대한 요청이 반복되면 VAN을 다시 호출하지 않고, DB에 저장된 결과를 기준으로 재응답합니다.

```text
동일 posTrx + attemptSeq 재요청
→ PAYMENT_ATTEMPT 조회
→ 기존 결과 재응답
→ VAN 재호출 없음
```

취소 요청은 `(originalPosTrx, originalAttemptSeq)` 기준으로 중복 여부를 판단합니다.

동일 원거래에 대한 취소 row가 이미 존재하면 VAN cancel을 다시 호출하지 않고, 기존 `PAYMENT_CANCEL` row 기준으로 재응답합니다.

```text
동일 originalPosTrx + originalAttemptSeq 재취소
→ PAYMENT_CANCEL 기존 row 조회
→ 기존 cancel row 재응답
→ 신규 cancel row 생성 없음
```

---

### 3.2 정합성

결제 서버에서 중요한 것은 API 응답만 정상으로 보이는 것이 아니라, 실제 DB 상태가 다음 API에서 참조 가능한 형태로 저장되어 있는지입니다.

따라서 본 프로젝트에서는 API 응답과 DB row 상태를 함께 검증했습니다.

```text
Approve 응답
finalStatus = APPROVED
approvalNo != null

PAYMENT_ATTEMPT
FINAL_STATUS = APPROVED
APPROVAL_NO = API 응답 approvalNo
```

```text
Cancel 응답
cancelStatus = CANCELLED
cancelApprovalNo != null

PAYMENT_CANCEL
CANCEL_STATUS = CANCELLED
CANCEL_APPROVAL_NO = API 응답 cancelApprovalNo
```

---

### 3.3 UNKNOWN_TIMEOUT

`UNKNOWN_TIMEOUT`은 승인 실패가 아니라 결과 미확정 상태입니다.

따라서 서버는 해당 거래를 `DECLINED`로 확정하지 않고, `PAYMENT_ATTEMPT.FINAL_STATUS = UNKNOWN_TIMEOUT`으로 저장합니다.

1차 MVP에서는 Inquiry를 호출해도 `cardLast4 = 7777` 거래는 계속 `UNKNOWN_TIMEOUT` 상태를 유지하도록 정책을 고정했습니다.

```text
Approve UNKNOWN_TIMEOUT
→ PAYMENT_ATTEMPT.FINAL_STATUS = UNKNOWN_TIMEOUT 저장
→ Inquiry 재조회
→ UNKNOWN_TIMEOUT 유지
→ DB 상태 유지
```

---

## 4. MVP 구현 범위

### 1차 MVP 포함 범위

* Approve API 구현
* Inquiry API 구현
* Cancel API 구현
* attemptSeq 발급 및 결제시도 저장
* VAN 시뮬레이터 기반 승인/거절/미확정 응답
* 확정 거래 Inquiry 시 DB 재응답
* UNKNOWN_TIMEOUT 거래 Inquiry 유지
* APPROVED 원거래 Cancel 처리
* DECLINED 원거래 Cancel 차단
* 동일 원거래 재취소 시 기존 cancel row 재응답
* 단위 테스트 / 통합 테스트 / Postman 수동 테스트

### 1차 MVP 제외 범위

* 실제 VAN/PG 연동
* ISO8583 전문 처리
* Reversal/망취소
* 정산/매입
* Redis/Kafka
* 부분취소
* BIN 기반 카드사 라우팅 고도화

---

## 5. API 요약

### Approve

```http
POST /api/v1/payments/approve
```

승인 요청을 처리하고 `APPROVED`, `DECLINED`, `UNKNOWN_TIMEOUT` 중 하나의 결과를 반환합니다.

---

### Inquiry

```http
POST /api/v1/payments/inquiry
```

기존 결제시도 상태를 조회합니다.

* 확정건은 DB 결과를 재응답합니다.
* UNKNOWN_TIMEOUT 거래는 VAN Inquiry 시뮬레이터를 통해 다시 확인합니다.

---

### Cancel

```http
POST /api/v1/payments/cancel
```

APPROVED 원거래를 기준으로 전체취소를 수행합니다.

동일 원거래 재취소 요청은 기존 cancel row를 재응답합니다.

---

## 6. DB 테이블 요약

| 테이블                 | 역할                         |
| ------------------- | -------------------------- |
| POS_TRX_SEQUENCE    | 포스 거래번호 발번 기준 테이블          |
| PAYMENT_ATTEMPT_SEQ | posTrx 단위 attemptSeq 발급    |
| PAYMENT_ATTEMPT     | 승인/조회 상태를 저장하는 결제시도 정본 테이블 |
| PAYMENT_CANCEL      | 취소 요청 및 취소 결과 저장 테이블       |
| BIN_CATALOG         | BIN 검증/카드사 식별 확장용 테이블      |
| PAYMENT_EVENT_LOG   | 이벤트/운영 로그 확장용 테이블          |

---

## 7. 테스트

### 7.1 단위 테스트

* Approve 서비스 단위 테스트
* Inquiry 서비스 단위 테스트
* Cancel 서비스 단위 테스트

주요 검증 항목:

* 요청 검증 실패
* 원거래 없음
* 승인/거절/UNKNOWN 상태 분기
* DB 재응답
* VAN 호출 여부
* 취소 가능/불가 분기
* 중복 취소 방어

---

### 7.2 통합 테스트

통합 테스트는 `@SpringBootTest + MockMvc` 기반으로 작성했습니다.

검증 범위:

```text
Controller
→ Service
→ Repository
→ MyBatis
→ SQLite
→ API Response
```

| IT_ID      | 시나리오                                          | 결과   |
| ---------- | --------------------------------------------- | ---- |
| IT-APP-001 | 승인 성공 → PAYMENT_ATTEMPT 저장 확인                 | PASS |
| IT-APP-002 | 승인 거절 → PAYMENT_ATTEMPT 저장 확인                 | PASS |
| IT-APP-003 | 승인 UNKNOWN_TIMEOUT → Inquiry 재조회 → UNKNOWN 유지 | PASS |
| IT-APP-004 | 이미 확정된 거래 Inquiry → DB 재응답                    | PASS |
| IT-APP-005 | APPROVED 원거래 → Cancel 성공                      | PASS |
| IT-APP-006 | DECLINED 원거래 → Cancel 불가                      | PASS |
| IT-APP-007 | Cancel 성공 후 동일 원거래 재취소 → 기존 cancel row 재응답    | PASS |

---

### 7.3 Postman 대표 E2E 수동 테스트

Postman은 자동 통합 테스트의 대체가 아니라, 실제 앱 실행 증거로 보조 기록했습니다.

대표 E2E 흐름:

```text
Approve APPROVED
→ Inquiry 확정건 조회
→ Cancel CANCELLED
→ 동일 원거래 재취소
→ DB 확인
```

검증 결과:

* Approve APPROVED 응답 확인
* Inquiry에서 동일 approvalNo 재응답 확인
* Cancel CANCELLED 응답 확인
* 동일 원거래 재취소 시 cancelApprovalNo 동일 확인
* PAYMENT_CANCEL row count = 1 확인

---

## 8. 실행 방법

### 애플리케이션 실행

```bash
./gradlew bootRun
```

### 전체 테스트 실행

```bash
./gradlew test
```

### 통합 테스트 클래스 실행

```bash
./gradlew test --tests "*PaymentFlowIntegrationTest"
```

---

## 9. 문서 산출물

상세 설계와 테스트 기록은 Notion에 정리했습니다.

* 정책서
* VAN 시뮬레이터 및 확장 정책서
* 승인(A) 프로세스 흐름도
* 조회(Q) 프로세스 흐름도
* 취소(C) 프로세스 흐름도
* API 명세서
* 테이블 정의서
* 기능 구현 매핑표
* 단위 테스트 문서
* 통합 테스트 문서
* Postman 수동 테스트 결과
* 결함관리대장
* Decision Log

---

## 10. 1차 MVP 완료 기준

* [x] Approve 구현
* [x] Inquiry 구현
* [x] Cancel 구현
* [x] APPROVED / DECLINED / UNKNOWN_TIMEOUT 상태 처리
* [x] UNKNOWN_TIMEOUT Inquiry 유지 정책 반영
* [x] APPROVED 원거래 Cancel 처리
* [x] DECLINED 원거래 Cancel 차단
* [x] 동일 원거래 재취소 방어
* [x] 단위 테스트 작성
* [x] 통합 테스트 7개 PASS
* [x] Postman 대표 E2E 수동 테스트 완료
* [x] 주요 문서 산출물 정리

---

## 11. Known Issue / 2차 MVP 계획

### 11.1 C5 PENDING insert unique 충돌 복구

동일 원거래에 대한 cancel 요청이 동시에 들어오면 `PAYMENT_CANCEL`의 unique 제약으로 인해 insert 충돌이 발생할 수 있습니다.

2차 MVP에서는 해당 케이스를 통합 테스트로 재현하고, 예외 발생 시 기존 cancel row를 재조회하여 상태별로 재응답하는 복구 로직을 추가할 예정입니다.

### 11.2 PAYMENT_EVENT_LOG 기록

현재 이벤트 로그 테이블은 설계되어 있으나, 1차 MVP에서는 주요 이벤트 기록 고도화까지는 포함하지 않았습니다.

2차 MVP에서는 승인/조회/취소 요청과 응답 이벤트를 `PAYMENT_EVENT_LOG`에 기록할 예정입니다.

### 11.3 BIN_CATALOG 기반 카드사 식별

1차 MVP에서는 BIN_CATALOG를 확장용 테이블로 유지합니다.

2차 MVP에서는 BIN 기반 카드사 식별을 추가하고, 카드사/VAN 라우팅의 기초를 구현할 예정입니다.

예:

```text
LOTTE_CARD route selected
```

### 11.4 UNKNOWN_TIMEOUT 확정 케이스 확장

1차 MVP에서는 Inquiry 이후에도 UNKNOWN_TIMEOUT을 유지하는 흐름을 우선 구현했습니다.

2차 MVP에서는 아래 케이스를 추가할 수 있습니다.

| Approve 결과      | Inquiry 결과  |
| --------------- | ----------- |
| UNKNOWN_TIMEOUT | APPROVED 확정 |
| UNKNOWN_TIMEOUT | DECLINED 확정 |

### 11.5 Redis 적용 검토

Redis는 1차 MVP 범위에 포함하지 않았습니다.

후속 확장에서는 아래 용도를 검토할 수 있습니다.

* 짧은 TTL 기반 idempotency cache
* cancel 동시 요청 lock
* 요청 추적/중복 요청 차단

---

## 12. 회고

이번 1차 MVP에서는 단순히 API를 구현하는 것보다, 결제 서버에서 중요한 상태 관리와 중복 방어 정책을 코드와 문서로 함께 정리하는 데 집중했습니다.

특히 `UNKNOWN_TIMEOUT`, `Inquiry`, `Cancel`, `동일 원거래 재취소` 흐름을 통해 결제 서버가 외부 VAN 응답에만 의존하는 것이 아니라, DB에 저장된 상태를 기준으로 후속 API가 일관되게 동작해야 한다는 점을 학습했습니다.
