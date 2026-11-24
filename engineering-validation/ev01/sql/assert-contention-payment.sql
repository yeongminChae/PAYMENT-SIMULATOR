\set ON_ERROR_STOP on

-- Usage:
-- psql ... -v pos_trx='2376-20261007-9911-0001' -f assert-contention-payment.sql

WITH evidence AS (
    SELECT
        :'pos_trx'::text AS pos_trx,
        (SELECT COUNT(*) FROM PAYMENT_ATTEMPT WHERE POS_TRX = :'pos_trx') AS payment_attempt_count,
        (SELECT COUNT(*) FROM PAYMENT_EXTERNAL_INFO WHERE POS_TRX = :'pos_trx') AS external_info_count,
        (SELECT COUNT(*) FROM PAYMENT_ATTEMPT_SEQ WHERE POS_TRX = :'pos_trx') AS attempt_seq_row_count,
        (SELECT MAX(LAST_SEQ) FROM PAYMENT_ATTEMPT_SEQ WHERE POS_TRX = :'pos_trx') AS last_seq,
        (SELECT MAX(FINAL_STATUS) FROM PAYMENT_ATTEMPT WHERE POS_TRX = :'pos_trx' AND ATTEMPT_SEQ = 1) AS final_status,
        (SELECT COUNT(DISTINCT APPROVAL_NO)
           FROM PAYMENT_ATTEMPT
          WHERE POS_TRX = :'pos_trx'
            AND ATTEMPT_SEQ = 1
            AND APPROVAL_NO IS NOT NULL) AS approval_no_count
)
SELECT
    1 / CASE
        WHEN payment_attempt_count = 1
         AND external_info_count = 1
         AND attempt_seq_row_count = 1
         AND last_seq = 1
         AND final_status = 'APPROVED'
         AND approval_no_count = 1
        THEN 1 ELSE 0
    END AS assertion_ok,
    *
FROM evidence;
