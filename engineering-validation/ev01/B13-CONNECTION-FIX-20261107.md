# B13 Connection 점유 최소 수정 및 회귀 결과 — 2026-11-07

실제 실패 B13 DB·원본 B/C에 연결하거나 Recovery를 재실행하지 않았다. 기존 FAIL은 유지한다.
커밋·push 없이 수정과 신규 Testcontainers PostgreSQL의 합성 데이터 테스트만 수행했다.

## 수정 범위

- RecoveryApprovalReader: 별도 Spring bean의 짧은 readOnly REQUIRED 조회에서 attempt를 materialize한다.
- ApprovalRecoveryHandler: 해당 reader를 호출한 뒤 VAN Inquiry와 기존 Finalizer를 호출한다.
- RecoveryWorker: UNKNOWN 실패의 History 기록이 실패하면 원래 Handler 예외에 suppressed로 붙이고 원래 예외를 던진다.
- 기존 Finalizer/claim/Lease/Fencing/멱등성/retry 정책과 실제 Hikari 설정은 변경하지 않았다.
- 승인 복구 조회에 한정한 변경이다. 취소·망취소 Handler의 비트랜잭션 조회 경로는 이번 수정 범위가 아니다.

## Connection 흐름

수정 전: NOT_SUPPORTED의 empty synchronization → Handler MyBatis 조회 → SqlSession/C1 유지 →
VAN 중 C1 점유 → Finalizer REQUIRED가 C2 요구 → pool1 timeout → 실패 기록도 timeout.

수정 후: claim TX 종료 → 별도 reader REQUIRED TX 시작·조회·종료 → SqlSession/C1 반환 →
VAN 중 actual TX없음/active0 → 기존 Finalizer REQUIRED가 반환된 Connection 획득 →
기존 소유권·Lease 검사 후 확정 → Task/History 완료 TX.

전역 synchronization 정책, pool 크기, timeout 증설을 해결책으로 적용하지 않았다.

## 격리 회귀 결과

새 Testcontainers DB와 mock VanGateway를 사용했다. 기존 B13 데이터·TCP19090을 사용하지 않았다.
기존 업무 transaction manager와 recoveryJob으로 pool1 검증을 수행했다.
테스트의 짧은 connection-timeout은 실패를 빨리 검출하는 테스트 전용 설정이다.

- 대조군: 기존처럼 Repository를 NOT_SUPPORTED에서 직접 조회하면 active1, scope 종료 후 active0.
- 합성13건: COMPLETED, APPROVED11/PROCESSING2, RESOLVED11/RETRY_WAIT2.
- Inquiry13회 각각 actualTransactionActive=false와 Hikari active0을 assertion으로 검사.
- claim_token/lease 해제, retry_count=1 및 정확한 첫1분 backoff 확인.
- 합성 replay도 COMPLETED, Inquiry/History 추가0. 승인·취소·망취소 호출0.
- Handler 오류와 History 저장 오류를 동시에 주입하면 원래 예외 객체와 suppressed 후속 예외를 모두 보존.
- 기존 승인 Handler/Worker/Finalizer/RecoveryExecutionTransactionService/RetryPolicy 단위 테스트 통과.

| 테스트 클래스 | 통과 수 |
|---|---:|
| RecoveryWorkerTest | 24 |
| ApprovalRecoveryHandlerTest | 21 |
| RecoveryExecutionTransactionServiceTest | 11 |
| DefaultRecoveryRetryPolicyTest | 7 |
| RecoveryPoolOneIntegrationTest | 1 |
| RecoveryFinalizationServiceImplTest | 25 |

총 89개 통과. `git diff --check` 통과.

## 보존·한계·승인 지점

기존 증빙1241개와 실제 B13 실패 증빙47개 SHA-256이 모두 동일하다.
원래 미추적 FETCH_HEAD와 기존 분석 문서를 보존했다. 실제 B13 Task/History/claim/DB를 초기화하지 않았다.
이번 결과는 mock VAN·합성 데이터의 격리 검증이며 실제 TCP/실제13건 복구 성공을 뜻하지 않는다.
실데이터 재검증에는 새 격리 회차와 증거·연결 identity·실행 명령을 확정하고 사용자 승인을 별도로 받아야 한다.
기존 run-once/실패 DB를 그대로 재실행하지 않는다. 위 내용은 11월7일 수정·격리 회귀 당시 기록이다. 이후 승인된 R2 단일 실행 결과는 [최종 보고서](FINAL-REPORT-20261029.md)에 별도 PASS로 추가했다.
