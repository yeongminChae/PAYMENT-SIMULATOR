\set ON_ERROR_STOP on
-- 요청 목록에서 LEFT JOIN하므로 누락도 출력한다. PAN/fingerprint는 조회하지 않는다.
-- 입력 ev01_requests는 Python이 intent 목록으로 만든 세션 temp table이다. production table은 갱신하지 않는다.
-- request_count는 같은 요청 식별자 중복, attempt_count는 다른 attemptSeq로 늘어난 시도까지 탐지한다.
-- MISSING과 PROCESSING(NULL)은 구분하며 baseline의 APPROVED/순번 1/참조 row 1개를 모두 요구한다.
-- 조건 실패는 assertion_ok=false로 CSV에 남긴다. psql 성공 종료 자체가 correctness PASS는 아니다.
-- 양쪽 DB의 승인번호·VAN 거래번호·금액 일치는 Python의 교차 대조에서 추가 확인한다.
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
