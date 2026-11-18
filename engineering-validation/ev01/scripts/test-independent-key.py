"""실제 HTTP 없이 예약 병렬성, SQL 누락/중복, 실패 증거 수집을 검증한다."""
import concurrent.futures
import importlib.util
import json
import os
from pathlib import Path
import sqlite3
import tempfile
import unittest
from unittest.mock import patch

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location('evidence', HERE / 'independent-key-evidence.py')
evidence = importlib.util.module_from_spec(spec)
spec.loader.exec_module(evidence)
KEY = '2376-20261027-0000-0001'


def allocate(root):
    return evidence.reserve(root, '20261027', 10)


class EvidenceTests(unittest.TestCase):
    def test_parallel_process_reservations_and_exhaustion(self):
        with tempfile.TemporaryDirectory() as root:
            with concurrent.futures.ProcessPoolExecutor(max_workers=8) as pool:
                bases = list(pool.map(allocate, [root] * 50))
            self.assertEqual(set(bases), set(range(0, 10000, 200)))
            with self.assertRaisesRegex(ValueError, 'exhausted'): allocate(root)
            self.assertEqual(evidence.reserve(root, '20261028', 200), 0)

    def test_fresh_checks_every_table_and_stops_on_collision(self):
        with patch.dict(os.environ, PAYMENT_DB_URL='p', VAN_DB_URL='v'):
            with patch.object(evidence, 'query', return_value='0') as query:
                evidence.check_fresh('20261027', 9800)
                self.assertEqual(query.call_count, 4)
                self.assertIn('9999-9999', query.call_args[0][1])
            with patch.object(evidence, 'query', return_value='1'):
                with self.assertRaisesRegex(ValueError, 'collides'): evidence.check_fresh('20261027', 0)

    def test_log_delta_and_truncation(self):
        with tempfile.TemporaryDirectory() as root:
            path = Path(root, 'log'); path.write_text('old\n')
            baseline = evidence.checkpoint(path)
            path.write_text('old\nnew\n')
            evidence.delta(path, baseline, Path(root, 'delta'))
            self.assertEqual(Path(root, 'delta').read_text(), 'new\n')
            path.write_text('x\n')
            with self.assertRaises(ValueError): evidence.delta(path, baseline, Path(root, 'delta'))

    def test_sql_missing_duplicate_wrong_seq_and_status(self):
        # 쿼리는 동일 SQL SELECT 본문을 SQLite로 실행한다. PostgreSQL COPY/temp-table 통합은 별도 확인 필요.
        for kind in ('payment', 'van'):
            source = (HERE.parent / 'sql' / ('assert-independent-key-%s.sql' % kind)).read_text()
            select = source.split('COPY (', 1)[1].rsplit(') TO STDOUT', 1)[0]
            db = sqlite3.connect(':memory:')
            db.executescript('''CREATE TABLE ev01_requests(pos_trx text,attempt_seq int);
              CREATE TABLE payment_attempt(pos_trx text,attempt_seq int,final_status text,amount int,approval_no text,van_trx_id text);
              CREATE TABLE payment_external_info(pos_trx text,attempt_seq int);
              CREATE TABLE payment_attempt_seq(pos_trx text,last_seq int);
              CREATE TABLE van_approval(pos_trx text,attempt_seq int,approval_status text,amount int,approval_no text,van_trx_id text);''')
            db.execute('INSERT INTO ev01_requests VALUES (?,1)', (KEY,))
            self.assertFalse(db.execute(select).fetchone()[-1])
            db.execute('INSERT INTO payment_attempt VALUES (?,1,\'APPROVED\',10000,\'a\',\'v\')', (KEY,))
            db.execute('INSERT INTO payment_external_info VALUES (?,1)', (KEY,))
            db.execute('INSERT INTO payment_attempt_seq VALUES (?,1)', (KEY,))
            db.execute('INSERT INTO van_approval VALUES (?,1,\'APPROVED\',10000,\'a\',\'v\')', (KEY,))
            self.assertTrue(db.execute(select).fetchone()[-1])
            db.execute('INSERT INTO ev01_requests VALUES (?,1)', (KEY,))
            self.assertFalse(db.execute(select).fetchone()[-1])
            db.execute('DELETE FROM ev01_requests WHERE rowid=2')
            table, status = ('payment_attempt', 'final_status') if kind == 'payment' else ('van_approval', 'approval_status')
            db.execute('UPDATE %s SET attempt_seq=2' % table)
            self.assertFalse(db.execute(select).fetchone()[-1])
            db.execute('UPDATE %s SET attempt_seq=1, %s=\'UNKNOWN_TIMEOUT\'' % (table,status))
            self.assertFalse(db.execute(select).fetchone()[-1])
            db.execute('UPDATE %s SET %s=\'APPROVED\'' % (table,status))
            db.execute('INSERT INTO %s SELECT * FROM %s' % (table,table))
            self.assertTrue(all(not row[-1] for row in db.execute(select)))
            db.close()

    def test_cross_database_mismatch(self):
        good = dict(assertion_ok='t', amount='10000', approval_no='a', van_trx_id='v')
        response = dict(httpStatus=200, finalStatus='APPROVED', resultCode='OK')
        self.assertEqual(evidence.transaction_issues(good,good,response,1), [])
        self.assertTrue(evidence.transaction_issues(good,dict(good,approval_no='wrong'),response,2))
        self.assertTrue(evidence.transaction_issues(good,good,{},1))

    def test_collection_attempts_both_databases_on_failure(self):
        with tempfile.TemporaryDirectory() as root:
            with patch.dict(os.environ, PAYMENT_DB_URL='p', VAN_DB_URL='v'):
                with patch.object(evidence, 'query', side_effect=RuntimeError('offline')) as query:
                    datasets, errors = evidence.sql_evidence(Path(root), HERE.parent, [KEY])
                    self.assertEqual(query.call_count, 2)
                    self.assertEqual(len(errors), 2)
                    self.assertEqual(datasets, {})

    def test_failed_k6_keeps_manifest_log_and_final_failure(self):
        with tempfile.TemporaryDirectory() as root:
            out = Path(root); log = out / 'van.log'; log.write_text('baseline\n')
            config = dict(log=str(log), checkpoint=evidence.checkpoint(log), date='20261027',
                          base=0, vus=10, seconds=5, settle=2)
            (out / 'environment.json').write_text(json.dumps(config))
            (out / 'stage-status.txt').write_text('k6_exit_code=99\nk6=FAIL\n')
            (out / 'request-events.jsonl').write_text(json.dumps(dict(event='request',posTrx=KEY,attemptSeq=1,startedMs=0))+'\n')
            log.write_text('baseline\n[van-tcp][approval][received] posTrx='+KEY+', attemptSeq=1\n')
            with patch.object(evidence, 'sql_evidence', return_value=({}, ['payment offline', 'van offline'])):
                self.assertEqual(evidence.collect(root, HERE.parent), 1)
            result = json.loads((out / 'run-summary.json').read_text())
            self.assertEqual(result['status'], 'FAIL')
            self.assertEqual(result['http_failures_or_no_response'], 1)
            self.assertTrue((out / 'requests.csv').exists())
            self.assertTrue((out / 'van-log-delta.txt').exists())
            self.assertTrue((out / 'transaction-assertion.csv').exists())


if __name__ == '__main__': unittest.main()
