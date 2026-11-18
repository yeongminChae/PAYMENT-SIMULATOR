\set ON_ERROR_STOP on
-- ev01_requests는 요청 직전 intent 목록을 담은 세션 temp table이다.
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
