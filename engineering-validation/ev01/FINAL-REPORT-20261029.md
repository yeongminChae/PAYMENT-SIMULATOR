# EV-01 최종 결과 고정

실험: 2026-10-26~29(KST), 읽기 전용 장애 분석: 2026-10-30(KST).

측정 기준 HEAD는 `695e0db30240523ca996342a73b43cb1ac57f3c3`이다. C 측정에는 당시 미커밋이던
격리 컨테이너 선택 하네스 2행이 포함됐다. 그 diff와 파일 해시는 [요약·해시 인덱스](EVIDENCE-INDEX-20261029.json)에 고정했다.
하네스 고정 커밋은 `e77f97850b59c8c426ef4cc454f9cd6b4c358a48`이며 문서 고정은 별도 커밋으로 관리한다.
이 문서의 결과를 전체 PASS로 해석하지 않는다. 기존 FAIL/INVALID는 유지하며 B 본 측정은 미완료다.

## 최종 판정

| 실험 | 판정 | 범위·제한 |
|---|---|---|
| A | 기존 PASS 기록 14건 보존, 미완성 시도 1건 BLOCKED | 재실행 없음. 과거 DB identity를 소급 입증하지 않음 |
| B Smoke / B1 10 VU | 기존 유효 PASS 보존 | B1 3회 재실행 없음 |
| 과거 DB 경계 불일치 B Smoke | INVALID 유지 | raw summary의 PASS와 최종 유효성 판정을 구분 |
| B 25 / 50 VU | 각 3회 PASS | HTTP·Payment/VAN·TCP 대조 |
| 원래 B 100 VU | 1회 PASS / 2회 FAIL | HTTP 10초 deadline 초과 1건 |
| 추가 B 100 VU | 유효 PASS 2회 / INVALID 1회 | 독립 관측기의 원시 identity 증빙 한계 1회 |
| B 200 VU | 1회 FAIL / 2회 FAIL 및 환경 INVALID / 3회 BLOCKED | 두 번째 회차의 미확정 거래·환경 이상으로 중단 |
| C1 100/500/1000건 × 각 3회 | 9회 PASS, 총 4,800건 RESOLVED | synthetic UNKNOWN_TIMEOUT, 단일 Worker |
| C2 Mixed | 설계된 10건 PASS | RESOLVED 4 / RETRY_WAIT 6, 대량 Mixed 성능 측정 아님 |
| 실제 B PROCESSING 13건 복구 | 미실행 | 후보 분석만 수행. 후속 과제로 분리 |

PASS는 해당 실험의 정의된 검증 통과, FAIL은 실행·응답·정합성 기준 미충족, INVALID는 환경/증빙 유효성 부족,
BLOCKED는 안전 조건 때문에 미실행 또는 판정 가능한 증빙 미완성을 뜻한다.

## 증빙과 보존 정책

- Git에는 이 보고서, 후속 과제, 선별한 요약·SHA-256 인덱스와 하네스 변경만 포함한다.
- 원본 로그·k6 결과·DB snapshot·ledger·volume은 로컬에 보존하며 이번 커밋에 포함하지 않는다.
- 아래 `results/...`는 `engineering-validation/ev01/` 기준의 **로컬 원본 위치**다. 원격 저장소에 포함된 링크가 아니다.
- 원본이 없는 checkout에서는 인덱스의 요약과 hash만 확인할 수 있다. hash는 로컬 원본 대조용이며 원본 재배포를 대신하지 않는다.
- 원래 B100 FAIL 23개 파일, 이전 캠페인 증빙 및 마지막 읽기 조사 때의 기존 증빙 1,219개 파일 무결성 검증 기록을 보존했다.
- 이 Git 정리에서는 새 부하·Inquiry·Recovery/C Job·DB 변경을 실행하지 않았다. 아래 환경 상태는 **마지막 읽기 조사 당시 기록**이다.

### 로컬 증빙 위치

| 범위 | 위치 |
|---|---|
| A 원본 | `results/A/<run-id>/` |
| B 원본·독립 감사 | `results/B/<run-id>/` |
| B1 보고서 | `results/B/b1-campaign-20261028T140609Z/report.md` |
| 초기 본 측정 | `results/final-20261029/` |
| B 후속·C 본 측정 | `results/followup-20261029/` |
| 13건 읽기 분석 | `results/incident-analysis-20261030/` |
| C 원본 | `results/C/<run-id>/` |

## A 기존 경합 측정 기록

A는 같은 거래번호의 동시 요청/중복 응답 정합성 실험이다. 아래 값은 HTTP 요청 처리율이며
신규 거래 승인 TPS인 B와 직접 비교하지 않는다. 과거 assertion PASS를 보존하며 DB identity의 소급 입증은 하지 않았다.
미완성 `20261026T133548Z-c20-r1`은 최종 summary가 없어 판정 불가/BLOCKED로 별도 유지한다.

| Run | 동시 요청 | 기존 판정 | HTTP 요청/s | p50/p95/p99/max ms |
|---|---|---|---|---|
| 20261026T133636Z-c20-r1 | 20 | PASS 기록 | 21.028 | 671.62/897.52/936.27/945.96 |
| 20261026T134232Z-c20-r1 | 20 | PASS 기록 | 36.433 | 350.27/523.31/537.76/541.38 |
| 20261026T134234Z-c20-r2 | 20 | PASS 기록 | 28.633 | 366.18/652.65/687.39/696.07 |
| 20261026T134236Z-c20-r3 | 20 | PASS 기록 | 38.388 | 329.99/503.61/515.74/518.77 |
| 20261026T134719Z-c50-r1 | 50 | PASS 기록 | 38.431 | 579.67/1166.10/1256.12/1292.63 |
| 20261026T134722Z-c50-r2 | 50 | PASS 기록 | 50.311 | 543.55/951.87/983.10/990.45 |
| 20261026T134724Z-c50-r3 | 50 | PASS 기록 | 49.805 | 544.70/953.65/990.38/1000.46 |
| 20261026T134859Z-c100-r1 | 100 | PASS 기록 | 44.213 | 1183.13/2145.56/2234.30/2254.53 |
| 20261026T134902Z-c100-r2 | 100 | PASS 기록 | 37.864 | 1605.73/2529.91/2601.15/2630.83 |
| 20261026T134905Z-c100-r3 | 100 | PASS 기록 | 48.464 | 1135.30/1977.66/2040.91/2056.15 |
| 20261026T135005Z-c200-r1 | 200 | PASS 기록 | 45.361 | 2468.63/4177.01/4333.80/4369.29 |
| 20261026T135104Z-c200-r1 | 200 | PASS 기록 | 39.405 | 2398.02/4850.78/5015.72/5056.89 |
| 20261026T135110Z-c200-r2 | 200 | PASS 기록 | 39.249 | 2304.08/4807.48/5016.82/5056.69 |
| 20261026T135116Z-c200-r3 | 200 | PASS 기록 | 36.868 | 2533.39/5216.97/5372.05/5409.07 |

## B 처리량·지연 및 실패

| Run ID | 최종 판정 | VU/초 | 요청 / HTTP승인 / DB승인 | HTTP오류 | 승인/s | p50/p95/p99/max ms |
|---|---|---|---|---|---|---|
| 20261027T140646Z-b10-p400-r1 | INVALID | 10/5 | 1203/1203/1203 | 0 | 238.600 | 34.40/71.71/114.81/384.85 |
| 20261027T143340Z-b10-p600-r1 | PASS | 10/5 | 69/69/69 | 0 | 11.800 | 637.74/1350.72/1353.97/1353.99 |
| 20261028T135152Z-b10-p0-r1 | PASS | 10/5 | 91/91/91 | 0 | 16.200 | 492.71/1118.31/1192.65/1242.63 |
| 20261028T140617Z-b10-p200-r1 | PASS | 10/60 | 1365/1365/1365 | 0 | 22.583 | 420.94/664.44/819.65/954.38 |
| 20261028T140732Z-b10-p400-r1 | PASS | 10/60 | 1336/1336/1336 | 0 | 22.100 | 422.76/695.08/801.44/1014.10 |
| 20261028T140847Z-b10-p600-r1 | PASS | 10/60 | 1382/1382/1382 | 0 | 22.867 | 410.00/661.71/767.99/884.67 |
| 20261029T125620Z-b25-p0-r1 | PASS | 25/60 | 1152/1152/1152 | 0 | 18.783 | 1220.75/2030.52/2412.36/3344.36 |
| 20261029T125738Z-b25-p200-r1 | PASS | 25/60 | 1297/1297/1297 | 0 | 21.200 | 1108.94/1596.03/1784.00/2271.61 |
| 20261029T125855Z-b25-p400-r1 | PASS | 25/60 | 1359/1359/1359 | 0 | 22.233 | 1070.35/1570.76/1946.27/2108.46 |
| 20261029T130013Z-b50-p600-r1 | PASS | 50/60 | 1321/1321/1321 | 0 | 21.183 | 2216.32/3144.39/3494.79/4144.69 |
| 20261029T130131Z-b50-p800-r1 | PASS | 50/60 | 1353/1353/1353 | 0 | 20.767 | 2212.19/3234.46/4636.06/5508.87 |
| 20261029T130251Z-b50-p1000-r1 | PASS | 50/60 | 1395/1395/1395 | 0 | 22.417 | 2069.02/2996.25/3298.92/3799.80 |
| 20261029T130409Z-b100-p1200-r1 | PASS | 100/60 | 1564/1564/1564 | 0 | 24.400 | 3892.41/4996.70/5598.55/6664.34 |
| 20261029T130528Z-b100-p1400-r1 | FAIL | 100/60 | 1321/1320/1321 | 1 | 20.333 | 4619.69/6372.23/7158.43/10021.32 |
| 20261029T131558Z-b100-p1600-r1 | PASS | 100/60 | 1254/1254/1254 | 0 | 19.233 | 4689.81/7663.11/8551.66/9542.21 |
| 20261029T131719Z-b100-p1800-r1 | INVALID | 100/60 | 1399/1399/1399 | 0 | 21.617 | 4362.10/5899.94/6490.57/8459.30 |
| 20261029T132003Z-b100-p2000-r1 | PASS | 100/60 | 1360/1360/1360 | 0 | 21.000 | 4526.25/5693.87/6360.65/7715.74 |
| 20261029T132222Z-b200-p2200-r1 | FAIL | 200/60 | 1520/1342/1520 | 178 | 19.200 | 8399.05/10000.59/10001.86/10002.25 |
| 20261029T132342Z-b200-p2400-r1 | FAIL (환경 INVALID) | 200/60 | 1605/1530/1590 | 75 | 19.883 | 7926.26/69451.85/70014.56/70016.52 |

승인/s는 측정 60초 안에 완료된 HTTP APPROVED/OK 건수/60이다. Smoke는 5초를 분모로 쓴다.
DB 최종 건수와 지연 통계에는 drain이 포함돼 DB commit 시각별 TPS와 다르다.
PASS 회차 percentile 평균도 pooled percentile이 아니다. 10 VU는 전일 B1이고 진단 계측·캐시·호스트 상태가 달라
동시성만의 인과 효과로 비교하지 않는다. 원본의 모든 회차는 인덱스에 보존했다.

### 원래 100 VU 실패

`2376-20261029-1451-0007`은 client 10,022ms에 HTTP status 0을 기록했고, 서버 Controller는 10,559ms에 응답했다.
Controller→VAN 호출 전 7.495초, VAN 왕복 약46ms, VAN 응답→Controller 응답 3.018초였다.
Payment/VAN 승인 및 TCP 1회 수신은 일치하지만 정상 HTTP 응답을 확인하지 못해 FAIL을 유지한다.
당시 pool acquire/SQL/commit span·thread dump·상세 GC pause가 없어 원인별 정확한 시간 분해는 미완성이다.

### 200 VU 두 회차의 구분

- 1회차: HTTP timeout 178/1,520건. 최종 Payment/VAN 승인 1,520건, TCP 거래당 1회와 일치한다. 응답 기준 FAIL이며 해당 power log 구간에 Sleep 기록은 없다.
- 2회차: 22:23:45 Idle Sleep(약3초), 22:24:33 Maintenance Sleep(약63초), 22:25:36 DarkWake가 실험과 겹쳤다.
  명목 60초+15초 graceful 조건에서 실제 k6는 124.900초였고 HTTP 200=1,530 / status 0=62 / 500=13이었다.
  Hikari 연결 획득 예외와 미확정 상태가 남아 FAIL을 유지하며 capacity 비교에서는 환경 INVALID로 제외한다.
- 첫 회차의 약10초 timeout 지연은 censor된 값이다. 둘째 회차의 약70초 관측은 Sleep 중 timer 처리 지연을 포함하며 설정된 HTTP timeout은 10초 그대로였다.
- 3회차는 실행하지 않았다. 다음 B 실행을 PASS로 추정하거나 실패를 새 결과로 덮어쓰지 않는다.

### Hikari·WAL·CPU·GC: 사실과 가설

후속 100 VU snapshot에서는 HTTP worker 87~90/100개가 Hikari borrow 대기였고 첫 200 VU에서는 약190/200개가 대기했다.
이는 대기 지점의 직접 증거이며 누적 대기시간은 아니다. 13건에서는 연결 획득 예외가 다음 단계 진입을 막았다.
Hikari queue는 즉각적인 지연 원인이면서 downstream service time과 제한된 connection 수의 결과일 수 있다.
pool10이 단일 근본 원인인지, pool 증설로 TPS가 개선되는지는 통제 실험 없이 확정하지 않는다.

WalSync/WALWrite 표본과 WAL counter 증가, JDBC 처리/commit stack이 관측됐다. fsync/synchronous_commit는 on,
wal_sync_method는 fdatasync였다. timing 수집이 꺼져 시간 counter 0을 I/O 지연 0으로 해석할 수 없다.
WAL flush·스토리지·VM scheduling·왕복 비용 중 지배적 요소는 미확인이다. LWLock을 동일 거래 row lock과 혼동하지 않는다.
CPU는 ps/Docker 표본으로 짧은 saturation이나 scheduling을 배제하지 못하며 관측행 수를 누적 wait 시간으로 바꾸지 않는다.
후속 jstat 관측 구간의 GC 누적 증가는 수십ms 수준이고 Full GC 증가는 0이었으나 상세 pause/safepoint나 원래 실패 시점의 GC 인과는 미검증이다.
원시 자원/DB wait CSV와 WAL·GC·thread 집계는 로컬 원본 및 인덱스의 hash로 대조한다.

## B 미확정 13건: 읽기 전용 최종 분석

양쪽 미생성 2건과 PROCESSING 13건을 구분한다. 승인 정본 `PAYMENT_ATTEMPT.FINAL_STATUS=NULL`이 PROCESSING이며
13건 모두 amount=10000, attempt/external/last_seq=1, Payment approval_no/van_trx_id=NULL이었다.

| posTrx (attempt 1) | VAN | TCP 수신/결과 | 중단 지점 | Hikari 경과 ms | HTTP |
|---|---|---|---|---|---|
| 2376-20261029-2416-0007 | APPROVED | 1/1 | VAN 결과 이벤트 INSERT 실패 | 62762 | 500 |
| 2376-20261029-2428-0007 | APPROVED | 1/1 | VAN 결과 이벤트 INSERT 실패 | 62548 | 500 |
| 2376-20261029-2447-0007 | ROW_ABSENT | 0/0 | VAN 요청 이벤트 INSERT 실패 | 62764 | 500 |
| 2376-20261029-2459-0007 | APPROVED | 1/1 | VAN 결과 이벤트 INSERT 실패 | 62746 | 500 |
| 2376-20261029-2463-0006 | APPROVED | 1/1 | TX2 시작용 connection 획득 실패 | 62536 | 0 |
| 2376-20261029-2533-0007 | APPROVED | 1/1 | TX2 시작용 connection 획득 실패 | 62771 | 0 |
| 2376-20261029-2535-0007 | APPROVED | 1/1 | VAN 결과 이벤트 INSERT 실패 | 62590 | 500 |
| 2376-20261029-2537-0007 | APPROVED | 1/1 | VAN 결과 이벤트 INSERT 실패 | 62778 | 500 |
| 2376-20261029-2544-0007 | ROW_ABSENT | 0/0 | VAN 요청 이벤트 INSERT 실패 | 62583 | 500 |
| 2376-20261029-2565-0007 | APPROVED | 1/1 | VAN 결과 이벤트 INSERT 실패 | 62787 | 500 |
| 2376-20261029-2571-0007 | APPROVED | 1/1 | TX2 시작용 connection 획득 실패 | 62761 | 500 |
| 2376-20261029-2574-0007 | APPROVED | 1/1 | VAN 결과 이벤트 INSERT 실패 | 62776 | 500 |
| 2376-20261029-2575-0007 | APPROVED | 1/1 | VAN 결과 이벤트 INSERT 실패 | 62593 | 500 |

정상 경계는 `TX1 prepare commit → 요청 event INSERT → VAN → 결과 event INSERT → TX2 finalize commit`이다.
[승인 서비스](../../payment-server/src/main/java/com/chaeyeongmin/payment_sim/payment/application/approval/service/impl/PaymentApprovalServiceImpl.java#L64)와 DB event/stack을 대조했다.

- VAN 승인 11건: 결과 event INSERT 실패 8건과 TX2 시작용 connection 획득 실패 3건이다. TX1 이후 원장 확정 경로가 중단됐다.
- VAN row 미확인 2건: 요청 event INSERT 예외가 동기 호출상 gateway 진입 전에 발생했다. 로그 부재만으로 미전송을 단정한 것이 아니다.
  이 요청 경로의 증거를 다른 writer/모든 시점의 미전송 보증이나 승인 재시도 허가로 해석하지 않는다.
- DB 예외들은 gateway의 request-not-sent/timeout catch 바깥이므로 cleanup 또는 UNKNOWN_TIMEOUT 확정이 실행됐다고 볼 수 없다.
- 약62.5~62.8초 Hikari 경과는 Sleep 구간을 포함하며 PostgreSQL이 SQL을 그 시간만큼 수행했다는 뜻이 아니다.
- DB EVENT_TIME은 일부 transaction의 CURRENT_TIMESTAMP이므로 실제 INSERT·commit 완료 시각과 구분한다.

### 후보와 미실행 사실

원본은 clean shutdown 상태를 유지했고 RO 마운트로 만든 byte-identical 물리 복사본에서만 SELECT했다.
복사본은 network=none/공개 포트 없음/TCP listen off/default_transaction_read_only=on/BEGIN READ ONLY로 격리했다.
기동·종료 시 제어파일/WAL/통계 변경은 복사본 내부에만 발생했고 원본 volume hash는 전후 동일했다.
system identifier가 같은 물리 복사본은 container/volume/network로도 구분했다. 두 복사본은 정지·보존했다.

2026-10-30 00:30:55 KST 읽기 결과는 global 승인 후보13, 취소0, 망취소0, Task0, History0, B Batch 실행0이다.
이는 [Repository predicate](../../payment-server/src/main/resources/mybatis/mapper/RecoveryCandidateMapper.xml#L9)의 기본 cutoff(PROCESSING 1분/UNKNOWN_TIMEOUT 30초)에 맞춘 읽기 검증이며 Job 실행 결과가 아니다.
승인11건은 향후 실제 Inquiry와 identity/claim 검사가 통과하면 RESOLVED를 기대할 수 있다.
미확인2건은 Inquiry가 NOT_FOUND라면 STILL_UNRESOLVED → RETRY_WAIT를 기대한다. 실제 Inquiry는 하지 않았다.
[Recovery handler](../../payment-server/src/main/java/com/chaeyeongmin/payment_sim/recovery/application/handler/impl/ApprovalRecoveryHandler.java#L110)는 승인 재전송 대신 결과를 조회한다.
기본 retry_count<3의 1/5/30분 backoff 이후 MANUAL_REVIEW는 Inquiry 재시도 정책이며 승인 재시도 허가가 아니다.
일반 Inquiry API의 PROCESSING 분기는 retryLater라서 복구 실행을 대신하지 않는다.
양쪽 미생성 `2446-0008`, `2580-0007`은 prepare connection 획득 실패와 대응하며 attempt가 없어 이13건과 후보에 포함하지 않는다.

## C Recovery 본 측정

| Run | 건수 | 결과 | Discovery / Process / Job ms | 복구율 건/s |
|---|---|---|---|---|
| 20261029T143105Z-p0-approval | 100 | RESOLVED100 | 681 / 8250 / 9182 | 12.121 |
| 20261029T143158Z-p200-approval | 100 | RESOLVED100 | 659 / 8092 / 9052 | 12.358 |
| 20261029T143250Z-p400-approval | 100 | RESOLVED100 | 663 / 8248 / 9179 | 12.124 |
| 20261029T143342Z-p600-approval | 500 | RESOLVED500 | 2875 / 37565 / 40714 | 13.310 |
| 20261029T143522Z-p800-approval | 500 | RESOLVED500 | 2658 / 36684 / 39653 | 13.630 |
| 20261029T143701Z-p1000-approval | 500 | RESOLVED500 | 2768 / 39737 / 42821 | 12.583 |
| 20261029T143846Z-p1200-approval | 1000 | RESOLVED1000 | 5329 / 81850 / 87583 | 12.217 |
| 20261029T144154Z-p1400-approval | 1000 | RESOLVED1000 | 5109 / 81603 / 87102 | 12.254 |
| 20261029T144500Z-p1600-approval | 1000 | RESOLVED1000 | 4989 / 79311 / 84658 | 12.609 |
| 20261029T144803Z-p1800-mixed | 10 | RESOLVED4 / RETRY_WAIT6 | 194 / 1180 / 1637 | 3.390 |

C1 총4,800건 RESOLVED, C2 RESOLVED4/RETRY_WAIT6이며 Inquiry/History 합계는4,810건이다.
복구율은 RESOLVED/Process step 시간으로 schema·fixture·JVM 기동·Discovery·replay 검증 시간을 포함하지 않는다.
B 승인 처리율과 workload·분모가 다르다. C1 평균 복구율은100건12.201, 500건13.174, 1000건12.360건/s였다.

각 회차는 새 빈 DB 쌍에서 시작했고 완료 후 DB 이름을 보존용으로 변경했다. host/internal identity와 VAN per-process marker/socket/backend,
Payment launcher의 실제 JDBC DB를 확인했다. 원본 B/C backlog와 새 C가 섞이지 않게 별도 cluster/volume을 사용했다.
pre/post/replay, Task/History/Inquiry/TCP, VAN ledger 불변과 별도 JobParameters 재실행을 검증했고
완료된 DB10쌍을 직접 읽어 Task 집합, History 수, 각 DB의 COMPLETED JobExecution 2건도 재대조했다.
해당 C 실행에서는 sleep 방지와 clock-gap 감시를 적용했으며 감시 기준을 넘는 공백은 기록되지 않았다.

C1은 synthetic UNKNOWN_TIMEOUT 검증이며 실제 B PROCESSING13건 복구나 실제 네트워크 장애의 대체 증거가 아니다.
C2는 기존 설계의 Mixed Smoke10건이다. 대량 Mixed throughput, 실제 DROP_RESPONSE, multi-worker/lease 탈취,
retry 소진 MANUAL_REVIEW에 대한 새 실험은 하지 않았다. 순차 replay 불변성을 동시 worker fencing 증명으로 확대하지 않는다.
과거 C1 Smoke의 최초 summary timestamp 파싱 오류와 보존 원본 재검증/요약 재생성 이력도 유지한다.

## 환경 및 재현

측정 환경은 macOS 호스트의 Java21.0.4, Colima QEMU x86_64(2 CPU/4GiB), PostgreSQL17.11,
Python3.10.0, k6 v2.3.0이었다. 도구 버전은 해당 원본 environment 기록이며 다른 환경의 capacity로 일반화하지 않는다.
Docker Desktop과 Colima의 중복 DB/호스트 listener 경계 문제가 과거 B Smoke INVALID의 근거였다.
network.address=false만으로 호스트 forwarding 불가를 일반화하지 않는다.

마지막 읽기 조사 당시 host5432/5433은 새 C cluster를 가리켰고 원본 B container는 정지 상태였다.
B의 system identifier는두 cluster 모두7700965760585932837이었고, 새 C Payment/VAN은각각
7702096414673420327 / 7702096418920124454였다. ID 하나만으로 DB 경계를 판정하지 않는다.
이 기록을 현재 환경 상태로 가정하지 말고 재현 시 PID/JDBC/host·container DB identity와 실제 포트 소유자를 다시 확인한다.

B 재현은 [기존 wrapper](scripts/run-independent-key-load.sh)의 `--vus N --repeat 1` 경로이며 duration60초, HTTP timeout10초,
gracefulStop15초, settle2초와 새 reservation 영역을 사용한다. 이번 Git 정리는 재현 실험을 실행하지 않았다.
C 재현은 [README](README.md)의 전용 DB/런처/identity 절차를 따르되 기존 RETRY_WAIT가 있는 DB를 재사용하지 않는다.
격리 C resource/inspect 대상은 `EV01_C_PAYMENT_CONTAINER`, `EV01_C_VAN_CONTAINER`로 지정할 수 있고 기본 이름은 유지된다.
이 설정은 DB URL/JDBC/backend guard를 바꾸지 않는다.

## 미검증 및 후속 과제

[실제 B13건 격리 Recovery 검증 과제](FOLLOWUP-B13-ISOLATED-RECOVERY.md)를 별도로 고정했다. 이번에는 실행하지 않는다.
원본 B13건의 실제 복구·양쪽 미생성2건 처리·B200 유효3회·정확한 acquire/SQL/commit span과 단일 throughput root cause는 미완료다.
이벤트 기록 실패가 business finalization을 막는 결합과 pre-gateway DB 실패의 보상 정책, 신뢰 가능한 outbox/transaction 경계는 향후 검토 대상이다.
audit 오류를 무조건 무시하거나 pool/timeout을 늘리는 변경을 해결책으로 확정하지 않았다.

## Git 정리 검증

하네스 회귀: `test-recovery-backlog.py` 9개, `test-independent-key.py` 7개,
`test-independent-key-pos-trx.mjs`, 관련 shell `bash -n`을 통과했다. 최종 `git diff --check`, 상대 링크 9개, 민감정보 패턴·개인 홈 경로 검사, 원본 1,241개 파일 hash 대조가 통과했다.
이 Git 정리의 변경은 기존 C 컨테이너 선택 하네스2개 파일과 문서/요약 인덱스에 한정한다.
기존 `test-recovery-backlog.py`의 반환 dict·timestamp·NAT guard 보강은 기준 커밋에 이미 포함되어 중복 수정하지 않는다.
원본 증빙·DB·volume·reservation ledger는 삭제하거나 초기화하지 않는다.
측정 이후의 문서 수정은 결과 판정을 바꾸지 않으며, Git 정리 커밋은 새로운 실험 결과가 아니다.
