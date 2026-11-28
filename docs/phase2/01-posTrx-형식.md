# 01. posTrx 새 형식

- 상태: 결정
- 날짜: 2026-11-26
- 플랫폼 근거: ADR-0002, ADR-0045 (Retail-Platform)
- 해소하는 차이: D1 · D2 · D3 (`00-플랫폼-차이.md`)

## 형식

```text
posTrx = 점포(4)-영업일(8)-POS(4)-거래번호(4)-결제순번(2)
예)      2301-20261126-0003-0042-01
정규식   ^\d{4}-\d{8}-\d{4}-\d{4}-\d{2}$
```

- 앞 네 부분이 Retail-POS의 거래 키, 마지막이 결제순번이다 (ADR-0002, 용어 · 자릿수는 ADR-0045).
- 번호 용어: 거래번호 = 4자리, 거래 키 = `점포-영업일-POS-거래번호`, 영수증번호 = `점포-발행일-POS-거래번호`, 결제순번 = 한 거래 안의 카드 결제 건, attemptSeq = 같은 posTrx 안의 승인 시도 순번(Payment가 매김).
- 영업일은 `uuuuMMdd` STRICT로 실제 날짜인지 검사한다.
- 결제순번은 `01`~`99`. `00`은 거부한다.
- Payment는 posTrx를 **문자열 그대로** 저장 · 비교한다. 점포 · 영업일을 따로 컬럼으로 꺼내 쓰지 않는다. 점포 · 영업일 단위 조회가 필요해지면(매입 · 정산, 07) 그때 다시 본다.

## 적용 위치

| 위치 | 대상 필드 |
|---|---|
| `ApproveRequest` `@Pattern` | `posTrx` |
| `CancelRequestValidator` | `posTrx`(반품 거래의 posTrx), `originalPosTrx` |
| `ReversalRequest` 검증 | `posTrx`, `originalPosTrx` |
| `InquiryRequestValidator` · 취소 조회 | `posTrx` / `originalPosTrx` |
| VAN Simulator `PosTrxProtocolValidator` | 승인 · 취소 · 망취소 · 조회 전문의 모든 posTrx |

- 검증 실패 코드는 기존 `INVALID_POS_TRX`를 쓴다.
- Payment가 직접 만드는 운영 취소 posTrx도 같은 형식이다 (점포별 운영 전용 POS 번호, 04 · ADR-0047). 형식 검증을 따로 둘 필요가 없다.
- 정규식은 한 곳(공용 상수)에 두고, Payment Server와 VAN Simulator는 DTO를 공유하지 않으므로(DESIGN.md 1장) 각자 같은 값을 가진다. 계약 테스트로 둘이 같은지 확인한다.

## 재결제 규칙

| 직전 시도 결과 | 다시 결제할 때 | Payment 동작 (현행 유지) |
|---|---|---|
| `DECLINED` (거절, 확실) | **같은 posTrx** | 새 `attemptSeq`로 승인 |
| `UNKNOWN_TIMEOUT` → 망취소 | **같은 거래 키 + 다음 결제순번** (새 posTrx) | 같은 posTrx로 오면 같은 내용은 저장된 결과 재응답, 다른 내용은 `POS_TRX_ALREADY_USED` |
| `APPROVED` | 재결제 아님 | 같은 내용은 재응답 |

- 같은 posTrx의 새 시도는 결과가 **확실히 실패**(`DECLINED`)한 뒤에만 허용한다. 결과를 모르는 시도 위에 같은 키로 새 시도를 얹지 않는다.
- 그래서 **Payment 코드 변경은 없다.** 바뀌는 것은 ADR-0025 맥락의 "같은 posTrx로 재결제" 서술이며, ADR-0045에 정리했다.
- 결과: 한 거래 안에 posTrx가 여러 개일 수 있다 (`-01` 망취소, `-02` 승인). 합산 상태(02)는 posTrx별로 나간다. `-01`의 망취소가 실패해 돈이 빠져 있으면, 판매에 쓰이지 않은 승인으로 ERP 대사가 찾는다 (ADR-0027). 같은 posTrx 안에서 빠진 승인이 2건 이상인 경우(ADR-0025 이중 결제)는 `DECLINED` 뒤 재시도에서만 생길 수 있다.

## 발급 API 제거

플랫폼은 `/api/v1/pos-trx/issue` · `/eot`를 쓰지 않는다 (ADR-0002). 체번의 주인을 Retail-POS 하나로 두기 위해 제거한다.

- `PosTrxController`, `PosTrxService(Impl)`, `PosTrxIssueRequest` · `PosTrxIssueResponse` · `PosTrxEotResponse`
- `PosTrxSequenceRepository`와 매퍼, `POS_TRX_SEQUENCE` 테이블 (`schema-postgres.sql`, `db/schema.sql`)
- `PosTrxServiceImplTest`, `PostgresPosTrxConcurrencyIntegrationTest`
- README · DESIGN.md의 EOT 발급 설명, `.run` 설정 등 남은 참조

## 구현 작업

1. 공용 posTrx 정규식 · 영업일 검사 (Payment Server, VAN Simulator 각각)
2. 위 표의 모든 입구에 적용, 테스트 픽스처의 posTrx를 새 형식으로 교체
3. VAN 전문 문서(`docs/docs/van-protocol/`) 예시 갱신
4. 계약 테스트: Payment가 보낸 새 형식 posTrx를 VAN이 받는다 (승인 · 취소 · 망취소 · 조회)
5. 발급 API 제거
6. 테스트: `DECLINED` 뒤 같은 posTrx 재승인 허용, `UNKNOWN_TIMEOUT` · 망취소 뒤 같은 posTrx 다른 내용은 `POS_TRX_ALREADY_USED`, 다음 결제순번은 정상 승인
