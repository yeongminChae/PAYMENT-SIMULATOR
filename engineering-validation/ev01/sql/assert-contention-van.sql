\set ON_ERROR_STOP on

-- Usage:
-- psql ... -v pos_trx='2376-20261007-9911-0001' -f assert-contention-van.sql
--
-- This verifies the VAN ledger only.
-- A separate log assertion must prove that [van-tcp][approval][received]
-- occurred exactly once for the same posTrx.

WITH evidence AS (
    SELECT
        :'pos_trx'::text AS pos_trx,
        COUNT(*) AS van_approval_count,
        COUNT(*) FILTER (WHERE approval_status = 'APPROVED') AS approved_count,
        COUNT(DISTINCT approval_no) FILTER (WHERE approval_no IS NOT NULL) AS approval_no_count
    FROM van_approval
    WHERE pos_trx = :'pos_trx'
)
SELECT
    1 / CASE
        WHEN van_approval_count = 1
         AND approved_count = 1
         AND approval_no_count = 1
        THEN 1 ELSE 0
    END AS assertion_ok,
    *
FROM evidence;
