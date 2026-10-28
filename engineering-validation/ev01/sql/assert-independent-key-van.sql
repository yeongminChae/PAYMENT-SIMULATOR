\set ON_ERROR_STOP on
-- ev01_requests는 요청 직전 intent 목록을 담은 세션 temp table이다.
-- 요청별 LEFT JOIN으로 원장 누락도 보존한다. 같은 posTrx의 다른 attemptSeq가 있어도 van_count로 탐지한다.
-- APPROVED/attemptSeq=1/승인번호/VAN 거래번호가 모두 맞아야 assertion_ok=true다.
-- 원장 row 1개는 TCP 호출 1회를 증명하지 않는다. Python의 checkpoint delta 로그 검증을 함께 사용한다.
-- false row도 CSV로 반환하고 최종 non-zero 판정은 evidence collector가 수행한다.
COPY (
 WITH requested AS (
   SELECT pos_trx, attempt_seq, count(*) AS request_count FROM ev01_requests GROUP BY pos_trx, attempt_seq
 ), evidence AS (
   SELECT r.*, (SELECT count(*) FROM van_approval v WHERE v.pos_trx=r.pos_trx) AS van_count,
     v.attempt_seq AS stored_attempt_seq, v.approval_status AS final_status,
     v.amount, v.approval_no, v.van_trx_id
   FROM requested r LEFT JOIN van_approval v ON v.pos_trx=r.pos_trx AND v.attempt_seq=r.attempt_seq
 ) SELECT *, coalesce(request_count=1 AND van_count=1 AND stored_attempt_seq=1
     AND final_status='APPROVED' AND approval_no IS NOT NULL AND van_trx_id IS NOT NULL,false) AS assertion_ok
 FROM evidence ORDER BY pos_trx
) TO STDOUT WITH (FORMAT csv, HEADER true);
