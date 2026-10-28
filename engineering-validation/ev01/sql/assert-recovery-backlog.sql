\set ON_ERROR_STOP on
-- manifest에서 만든 temp table을 기준으로 대상별 Task/History를 검증한다.
-- 입력 ev01_c_expected는 target 종류·원승인·기대 Task 상태다. fixture가 없어지더라도 행을 출력한다.
-- Task 수 1개뿐 아니라 원승인/attemptSeq 일치와 claimToken/lease 반납까지 확인한다.
-- History는 아직 실행 전이거나 종료되지 않은 경우, 결과가 기대 상태와 다른 경우를 실패로 남긴다.
-- 여기의 history_count>=1은 SQL 최소 조건이다. Python이 tryNo 연속성과 RESOLVED 재실행 중복을 추가 대조한다.
-- SQL은 상태를 바꾸지 않으며 assertion_ok=false도 보존한다. 원장·VAN 응답·TCP 횟수 판정은 별도 Python 경로다.
COPY (
  WITH evidence AS (
    SELECT f.*,
      (SELECT count(*) FROM payment_recovery_task t WHERE t.target_type=f.target_type AND t.target_trx_no=f.target) AS task_count,
      (SELECT count(*) FROM payment_recovery_task t WHERE t.target_type=f.target_type AND t.target_trx_no=f.target
        AND t.original_pos_trx=f.original AND t.original_attempt_seq=1
        AND ((f.target_type='APPROVAL' AND t.target_attempt_seq=1) OR (f.target_type<>'APPROVAL' AND t.target_attempt_seq IS NULL))
        AND t.recovery_status=f.expected_task AND t.claim_token IS NULL AND t.lease_expires_at IS NULL) AS valid_task_count,
      (SELECT count(*) FROM payment_recovery_history h JOIN payment_recovery_task t ON h.recovery_task_id=t.id
        WHERE t.target_type=f.target_type AND t.target_trx_no=f.target) AS history_count,
      (SELECT count(*) FROM payment_recovery_history h JOIN payment_recovery_task t ON h.recovery_task_id=t.id
        WHERE t.target_type=f.target_type AND t.target_trx_no=f.target AND
          (h.result IS NULL OR h.result<>f.expected_task OR h.finished_at IS NULL OR h.finished_at<h.started_at)) AS invalid_history_count
    FROM ev01_c_expected f
  ) SELECT *, (task_count=1 AND valid_task_count=1 AND history_count>=1 AND invalid_history_count=0) AS assertion_ok
    FROM evidence ORDER BY target
) TO STDOUT WITH (FORMAT csv, HEADER true);
