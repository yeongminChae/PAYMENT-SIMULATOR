# B13 수정 후 재검증 준비 — 2026-11-11

실제 Recovery Job은 승인 전 미실행이며 commit/push하지 않는다. 이전 실패 결과는 유지한다.
현재 HEAD7f4076f와 미커밋 Connection 수정 및 기존 분석 문서를 보존했다.
원본 증빙1241개와 실제 B13 실패 증빙47개의 SHA256이 모두 일치했다.

## 환경 복구 관측

기존 Colima(QEMU x86_64/2CPU/4GiB)가 중지돼 있었다. 기존 프로필로 기동했고 모든 B/C/B13 volume과
컨테이너가 보존됐음을 확인했다. 기존 DB들은 모두 정지 상태였고 시작하지 않았다.
첫 기동 명령 세션 종료 후 VM socket 연결이 사라졌다. 원인은 단정하지 않으며 유지되는 PTY로 재확인 중이다.
첫 현재 회귀 실행은 단위88개PASS, 신규 Testcontainers 통합1개 환경 초기화FAIL이었다.
과거 2026-11-07의89개PASS와 구분하며, 현재 환경에서 전체PASS를 추정하지 않는다.
첫 복사 준비는 docker inspect 실패로 DB/volume 생성 전 종료됐다. 해당 실패 로그와 테스트 XML을 보존했다.

## 새 회차 계획

- 소스는 정지·보존된 원본 B이며 기존 실패 B13의 Task/History를 초기화하거나 복사해 실행하지 않는다.
- 신규 network ev01-b13r2-20261111, 예정 CIDR172.29.0.0/24.
- 신규 Payment/VAN container ev01-b13r2-payment-20261111 / ev01-b13r2-van-20261111.
- 신규 volume은 위 이름에-data, 예정 host loopback16432/16433. 원본 포트5432/5433 및 실패회차15432/15433을 재사용하지 않는다.
- readonly source mount로 물리 복사 후 시작 전 내용 hash 일치와 source clean shutdown을 확인한다.
- 원본 B/C와 실패 B13 총6개 container/mount/volume을 보호 목록에 포함한다.
- 기존 하네스는 첫 회차의 이름/포트/PID를 고정하므로 새 회차를 현재 그대로 실행할 수 없다.
  검증 조건을 삭제하지 않고 새 회차의 명시적인 endpoint·PID·identity 프로필과 회귀 검사가 필요하다.
- 현재 위 항목은 계획이며 DB 준비·VAN 기동·JDBC 증명·실제 Inquiry를 완료했다고 표시하지 않는다.
- 새 환경과 단발 실행 명령까지 검증한 뒤 사용자에게 별도 Recovery 승인을 요청한다.

## 현재 추가 확인 결과

PTY를 유지한 Colima 재기동에서는 연결이 유지됐다. 회귀89개를 새 Testcontainers DB에서 다시 실행해 모두PASS였다.
첫88PASS/환경FAIL1 기록은 덮어쓰지 않았다. VM이 사라진 근본 원인은 확정하지 않는다.

신규 두 DB는 준비됐다. 정지 원본 B를 readonly로 마운트해 별도 volume에 복사했고,
PG cluster clean shutdown과 복사 전 파일 내용 SHA256 일치를 확인한 뒤 복사본만 기동했다.

| 항목 | 실제 준비값 |
|---|---|
| Payment | ev01-b13r2-payment-20261111 / ev01-b13r2-payment-20261111-data / loopback16432 |
| VAN | ev01-b13r2-van-20261111 / ev01-b13r2-van-20261111-data / loopback16433 |
| Network | ev01-b13r2-20261111 /172.29.0.0/24, DB IP .2/.3 |
| system identifier | 양쪽7700965760585932837. 물리 복사본이므로 같으며 별도 ID/volume/network로 격리 |
| 내부 READ ONLY 조회 | payment_sim/van_sim, 후보정확히13, Task/History/Batch0 |
| VAN 대상 원장 | APPROVED11, 나머지2 row 부재. 실제 NOT_FOUND 응답은 아직 관측하지 않음 |

기존 B/C 및 실패 B13 총6개 container/state/mount가 준비 전후 동일했다. 기존 volume/ledger/원본 결과는 삭제하지 않았다.
현재 신규 DB 준비 단계만PASS다. 새 VAN Java 프로세스 기동·실제 host JDBC/backend 증명·새 회차용 하네스
명시 프로필 및 그 회귀 검사가 남아 전체 실행 preflight는NOT_READY다. actual Recovery/Inquiry0회, commit/push0회다.
기존 guard는 첫 회차의 PID/이름/포트에 고정돼 있어 새 회차를 그대로 허용하지 않는다. 이를 우회하지 않는다.
전체 검증과 정확한 실행 명령이 완성되기 전에는 실제 Recovery 승인 요청 또는 실행으로 넘어가지 않는다.
