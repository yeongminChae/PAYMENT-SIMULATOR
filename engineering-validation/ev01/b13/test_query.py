"""psql JSON regression tests; no real database or Recovery invocation."""
import json
import subprocess
import unittest
from unittest.mock import patch
from isolation import query, command


class QueryTest(unittest.TestCase):
    cfg = dict(container='ev01-b13-payment', user='postgres',
               url='jdbc:postgresql://127.0.0.1:15432/payment_sim')

    def test_multiline_json_and_transaction_tags(self):
        with patch('isolation.docker', return_value='BEGIN\n[\n {"database":"payment_sim"}\n]\nCOMMIT\n') as mocked:
            self.assertEqual([{'database': 'payment_sim'}], query(self.cfg, 'SELECT fixed_json'))
        self.assertIn('ON_ERROR_STOP=1', mocked.call_args.args)
        self.assertIn('BEGIN READ ONLY; SELECT fixed_json; COMMIT;', mocked.call_args.args)

    def test_scalar_object_json(self):
        with patch('isolation.docker', return_value='{"database":"payment_sim"}\n'):
            self.assertEqual('payment_sim', query(self.cfg, 'SELECT fixed_json')['database'])

    def test_empty_malformed_extra_output_rejected(self):
        for output in ('', 'BEGIN\nCOMMIT\n', '{', '{}\n{}', 'WARNING: unexpected\n{}', 'ROLLBACK\n{}'):
            with self.subTest(output=output), patch('isolation.docker', return_value=output):
                with self.assertRaises(json.JSONDecodeError):
                    query(self.cfg, 'SELECT fixed_json')

    def test_connection_or_sql_failure_propagates(self):
        with patch('isolation.docker', side_effect=RuntimeError('Evidence command failed: docker')):
            with self.assertRaises(RuntimeError):
                query(self.cfg, 'SELECT fixed_json')

    def test_command_error_does_not_expose_credentials(self):
        failed = subprocess.CompletedProcess(['docker'], 1, 'secret stdout', 'secret stderr')
        with patch('isolation.subprocess.run', return_value=failed):
            with self.assertRaisesRegex(RuntimeError, '^Evidence command failed: docker$'):
                command(['docker', 'exec'])

    def test_command_timeout_fails_closed(self):
        with patch('isolation.subprocess.run', side_effect=subprocess.TimeoutExpired('docker', 60)):
            with self.assertRaises(subprocess.TimeoutExpired):
                query(self.cfg, 'SELECT fixed_json')


if __name__ == '__main__':
    unittest.main()
