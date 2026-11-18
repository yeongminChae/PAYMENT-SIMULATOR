\set ON_ERROR_STOP on
-- 요청 목록에서 LEFT JOIN하므로 누락도 출력한다. PAN/fingerprint는 조회하지 않는다.
COPY (
 WITH requested AS (
   SELECT pos_trx, attempt_seq, count(*) AS request_count FROM ev01_requests GROUP BY pos_trx, attempt_seq
 ), evidence AS (
   SELECT r.*,
     (SELECT count(*) FROM payment_attempt p WHERE p.pos_trx=r.pos_trx) AS attempt_count,
     (SELECT count(*) FROM payment_external_info e WHERE e.pos_trx=r.pos_trx) AS external_count,
     (SELECT count(*) FROM payment_external_info e WHERE e.pos_trx=r.pos_trx AND e.attempt_seq=r.attempt_seq) AS matched_external_count,
     (SELECT count(*) FROM payment_attempt_seq s WHERE s.pos_trx=r.pos_trx) AS seq_count,
     (SELECT max(last_seq) FROM payment_attempt_seq s WHERE s.pos_trx=r.pos_trx) AS last_seq,
     p.attempt_seq AS stored_attempt_seq, CASE WHEN p.pos_trx IS NULL THEN 'MISSING' ELSE coalesce(p.final_status,'PROCESSING') END AS final_status,
     p.amount, p.approval_no, p.van_trx_id
   FROM requested r LEFT JOIN payment_attempt p ON p.pos_trx=r.pos_trx AND p.attempt_seq=r.attempt_seq
 ) SELECT *, coalesce(request_count=1 AND attempt_count=1 AND stored_attempt_seq=1
     AND external_count=1 AND matched_external_count=1 AND seq_count=1 AND last_seq=1
     AND final_status='APPROVED' AND approval_no IS NOT NULL AND van_trx_id IS NOT NULL,false) AS assertion_ok
 FROM evidence ORDER BY pos_trx
) TO STDOUT WITH (FORMAT csv, HEADER true);
