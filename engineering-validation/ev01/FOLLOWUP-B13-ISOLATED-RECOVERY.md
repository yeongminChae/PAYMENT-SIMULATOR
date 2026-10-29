# EV-01 실제 B 미확정 13건의 격리 Recovery 검증

상태: **OPEN / 이번 Git 정리 작업에서는 미실행**. 이 문서는 후속 과제이며 자동 실행 지시가 아니다.

## 입력과 마지막 확인 상태

[최종 보고서](FINAL-REPORT-20261029.md)와 [고정 요약·해시](EVIDENCE-INDEX-20261029.json)를 기준으로 한다.
아래 상태는 2026-10-30 읽기 조사 당시 기록이다. 실행 전에 다시 확인한다.
당시 원본 B는 보존·정지 상태이며 실제 상태는 읽기 전용 물리 복사본에서 확인했다.
승인 후보 13건, Task/History/Batch 실행 0건이다. VAN 승인 11건과 VAN row 미확인 2건을 구분한다.
별도의 양쪽 미생성 2건은 이 13건에 포함하지 않는다.

## 선행 조건

1. 원본 B 및 기존 C DB·volume·ledger·원본 결과를 보존한다. 호스트 포트를 임의 전환하지 않는다.
2. 새 쓰기 허용 격리 복사본을 준비하고 원본과 다른 container/volume/network/접속 경계를 기록한다.
   물리 복사본은 system identifier가 원본과 같을 수 있으므로 ID 하나만으로 격리를 증명하지 않는다.
3. 전체 후보가 정확히 승인 13건, 취소·망취소 0건인지 다시 읽는다. 외부 backlog가 섞이면 중단한다.
4. 전용 런처와 실제 JDBC/backend guard를 검증한다. 현행 C fixture runner는 count13과 외부 backlog를
   허용하지 않으므로 기존 가드를 우회하거나 fixture로 원장을 덮어쓰지 않는다.
5. Sleep 방지와 시계 공백 감시를 적용한다. 연결 대상과 실행 범위가 확인돼야 실제 Inquiry/Job을 시작한다.

## 실행 및 수용 기준

- 격리 복사본에서 Recovery 1회를 실행하고 Task/History/Inquiry/TCP/원장 상태를 보존한다.
- VAN 승인 11건: 실제 Inquiry의 동일 거래·attempt·승인번호·금액·VAN ID를 검증한 뒤
  기존 attempt를 확정해 RESOLVED가 되는지 확인한다. 승인 재전송과 VAN ledger 변경은 0건이어야 한다.
- VAN 미확인 2건: 실제 Inquiry가 NOT_FOUND이면 STILL_UNRESOLVED → RETRY_WAIT를 기대한다.
  NOT_FOUND를 거절 확정이나 승인 재시도 허가로 해석하지 않는다.
- 두 건의 요청 이벤트 INSERT 예외는 해당 호출의 gateway 진입 전 중단 증거이지만,
  현재 PROCESSING 상태의 승인 재시도를 허용하는 정책은 아니다.
- 별도 JobParameters로 재실행해 RESOLVED 11건의 원장/History 불변을 확인한다.
  남은 2건의 Inquiry backoff/retry 소진/MANUAL_REVIEW는 설정을 몰래 단축하지 않고 별도 검증한다.
- claim token/lease, 중복 처리 방어, 실제 대상 집합을 검증한다. 실패하면 증빙을 남기고 중단한다.

## 범위와 완료 조건

일반 Inquiry API의 PROCESSING 분기는 retryLater이며 이 검증을 대체하지 않는다.
C1 4,800건은 synthetic UNKNOWN_TIMEOUT 검증으로 실제 PROCESSING 13건의 복구 증명이 아니다.
위 검증이 완료돼도 원본 B의 복구 실행은 별도 범위·연결·절차 검토가 필요하다.
원본의 Inquiry/Job/거래 변경·재승인, 새 부하, Production 코드 변경은 이 문서만으로 실행하지 않는다.

완료 시 원본과 복사본의 무결성/격리 증명, 13건별 전후 상태, Inquiry/TCP 원본,
Task/History 및 replay 검증, 실패/미검증 항목을 보고서에 추가한다.
