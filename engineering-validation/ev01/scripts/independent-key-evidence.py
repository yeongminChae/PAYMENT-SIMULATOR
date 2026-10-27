"""B 전용 표준 라이브러리 도구. production schema 변경 없이 영구 예약과 증거 수집."""
import collections
import csv
import datetime
import hashlib
import io
import json
import os
from pathlib import Path
import re
import subprocess
import sys


def reserve(root, date, vus):
    datetime.datetime.strptime(date, '%Y%m%d')
    if vus not in (10, 25, 50, 100, 200):
        raise ValueError('invalid VU count')
    directory = Path(root) / date
    directory.mkdir(parents=True, exist_ok=True)
    # 고정 200-posNo 슬롯의 mkdir는 원자적이다. 실패한 run도 슬롯을 반환하지 않는다.
    for base in range(0, 10000, 200):
        try:
            (directory / str(base)).mkdir()
            return base
        except FileExistsError:
            continue
    raise ValueError('daily posNo range exhausted; retain reservation records')


def checkpoint(path):
    with open(path, 'rb') as stream:
        stat = os.fstat(stream.fileno())
        prefix = stream.read(stat.st_size)
        if prefix and not prefix.endswith(b'\n'):
            raise ValueError('log baseline contains partial line')
        return dict(device=stat.st_dev, inode=stat.st_ino, size=stat.st_size,
                    sha256=hashlib.sha256(prefix).hexdigest())


def delta(path, baseline, output):
    with open(path, 'rb') as stream:
        stat = os.fstat(stream.fileno())
        if (stat.st_dev, stat.st_ino) != (baseline['device'], baseline['inode']) or stat.st_size < baseline['size']:
            raise ValueError('log rotated/replaced/truncated')
        if hashlib.sha256(stream.read(baseline['size'])).hexdigest() != baseline['sha256']:
            raise ValueError('log prefix changed')
        appended = stream.read(stat.st_size - baseline['size'])
        if appended and not appended.endswith(b'\n'):
            raise ValueError('log delta contains partial line')
        if os.stat(path).st_ino != stat.st_ino:
            raise ValueError('log replaced during capture')
        Path(output).write_bytes(appended)


def query(db, sql):
    result = subprocess.run(['psql', db, '-X', '-q', '-v', 'ON_ERROR_STOP=1'],
                            input=sql, text=True, capture_output=True)
    if result.returncode:
        raise RuntimeError('psql failed (exit %s); credentials omitted' % result.returncode)
    return result.stdout


def check_fresh(date, base):
    datetime.datetime.strptime(date, '%Y%m%d')
    if base not in range(0, 10000, 200): raise ValueError('invalid reservation')
    where = "pos_trx >= '2376-%s-%04d-0000' AND pos_trx <= '2376-%s-%04d-9999'" % (date, base, date, base + 199)
    for env, tables in [('PAYMENT_DB_URL', ('payment_attempt', 'payment_external_info', 'payment_attempt_seq')),
                        ('VAN_DB_URL', ('van_approval',))]:
        for table in tables:
            if query(os.environ[env], 'SELECT count(*) FROM %s WHERE %s;' % (table, where)).strip() != '0':
                raise ValueError('reserved range collides with existing %s; no load sent' % table)


def sql_evidence(out, ev_dir, keys, config=None):
    datasets, errors = {}, []
    for kind, env in [('payment', 'PAYMENT_DB_URL'), ('van', 'VAN_DB_URL')]:
        try:
            manifest = ''.join('%s,1\n' % k for k in keys)
            # 접속 세션의 temp table만 사용한다. 요청 중복을 SQL에서도 탐지한다.
            sql = ('CREATE TEMP TABLE ev01_requests(pos_trx text, attempt_seq int);\n'
                   'COPY ev01_requests FROM STDIN WITH (FORMAT csv);\n' + manifest + '\\.\n' +
                   Path(ev_dir, 'sql', 'assert-independent-key-%s.sql' % kind).read_text())
            data = query(os.environ[env], sql)
            (out / ('%s-assertion.csv' % kind)).write_text(data)
            rows = list(csv.DictReader(io.StringIO(data)))
            if len(rows) != len(set(keys)): raise ValueError('SQL evidence row count mismatch')
            datasets[kind] = {r['pos_trx']: r for r in rows}
            if config:
                tables = ('payment_attempt', 'payment_external_info', 'payment_attempt_seq') if kind == 'payment' else ('van_approval',)
                scoped = set()
                for table in tables:
                    text = query(os.environ[env], "SELECT DISTINCT pos_trx FROM %s WHERE pos_trx >= '2376-%s-%04d-0000' AND pos_trx <= '2376-%s-%04d-9999';" %
                                 (table, config['date'], config['base'], config['date'], config['base'] + 199))
                    scoped.update(text.splitlines())
                extra = sorted(scoped - set(keys))
                (out / ('%s-unexpected-identifiers.json' % kind)).write_text(json.dumps(extra))
                if extra: errors.append(kind + ' has unrequested identifiers in reserved range')
        except Exception as exc:
            errors.append(kind + ' evidence collection: ' + str(exc))
    return datasets, errors


def transaction_issues(p, v, response, tcp_count):
    issues = []
    if p.get('assertion_ok') != 't': issues.append('payment missing/duplicate/non-approved/mismatch')
    if v.get('assertion_ok') != 't': issues.append('van missing/duplicate/non-approved')
    for field in ('amount', 'approval_no', 'van_trx_id'):
        if not p.get(field) or p.get(field) != v.get(field): issues.append(field + ' mismatch')
    if tcp_count != 1: issues.append('TCP received missing/duplicate')
    if response.get('httpStatus') != 200: issues.append('HTTP failure/absent; approval not inferred')
    if response.get('finalStatus') != 'APPROVED' or response.get('resultCode') != 'OK': issues.append('non-baseline response')
    return issues


def collect(directory, ev_dir):
    out = Path(directory)
    config = json.loads((out / 'environment.json').read_text())
    errors, requests, responses = [], [], {}
    try:
        for line in (out / 'request-events.jsonl').read_text().splitlines():
            event = json.loads(line)
            if event['event'] == 'request':
                key = event['posTrx']
                if not re.fullmatch(r'2376-\d{8}-\d{4}-\d{4}', key) or event['attemptSeq'] != 1:
                    raise ValueError('invalid request identifier')
                if key[5:13] != config['date'] or not config['base'] <= int(key[14:18]) < config['base'] + config['vus']:
                    raise ValueError('request outside reserved range')
                requests.append(event)
            elif event['event'] == 'response':
                if event['posTrx'] in responses: errors.append('duplicate response event')
                responses[event['posTrx']] = event
    except Exception as exc:
        errors.append('request manifest: ' + str(exc))
    keys = [r['posTrx'] for r in requests]
    if len(keys) != len(set(keys)) or not keys: errors.append('empty or duplicate request identifiers')
    if set(responses) - set(keys): errors.append('response without request intent')
    with (out / 'requests.csv').open('w') as stream:
        writer = csv.writer(stream); writer.writerow(['pos_trx', 'attempt_seq']); writer.writerows((k, 1) for k in keys)
    datasets, sql_errors = sql_evidence(out, ev_dir, keys, config)
    errors.extend(sql_errors)
    counts = collections.Counter()
    try:
        delta(config['log'], config['checkpoint'], out / 'van-log-delta.txt')
        for line in (out / 'van-log-delta.txt').read_text().splitlines():
            if '[van-tcp][approval][received]' in line:
                match = re.search(r'posTrx=(2376-\d{8}-\d{4}-\d{4}), attemptSeq=(\d+)', line)
                if match and match[1][5:13] == config['date'] and config['base'] <= int(match[1][14:18]) <= config['base'] + 199:
                    counts[(match[1], int(match[2]))] += 1
        if set(counts) - {(k, 1) for k in keys}: errors.append('unexpected TCP identifiers in reserved range')
    except Exception as exc:
        errors.append('VAN log collection: ' + str(exc))
    details = []
    for key in sorted(set(keys)):
        p = datasets.get('payment', {}).get(key, {}); v = datasets.get('van', {}).get(key, {})
        response = responses.get(key, {})
        details.append(dict(pos_trx=key, attempt_seq=1, payment_status=p.get('final_status', 'MISSING'),
                            http_status=response.get('httpStatus', 'NO_RESPONSE'),
                            response_status=response.get('finalStatus', 'NO_RESPONSE'),
                            van_status=v.get('final_status', 'MISSING'), tcp_received=counts[(key, 1)],
                            issues='; '.join(transaction_issues(p, v, response, counts[(key, 1)]))))
    with (out / 'transaction-assertion.csv').open('w') as stream:
        writer = csv.DictWriter(stream, fieldnames=['pos_trx', 'attempt_seq', 'payment_status', 'http_status', 'response_status', 'van_status', 'tcp_received', 'issues'])
        writer.writeheader(); writer.writerows(details)
    if any(d['issues'] for d in details): errors.append('transaction correctness failed; see transaction-assertion.csv')
    try:
        summary = json.loads((out / 'k6-summary.json').read_text())
        if summary['ev01_request_count'] != len(keys): errors.append('k6 HTTP count differs from intents; dispatch uncertain')
    except Exception:
        errors.append('k6 summary absent or invalid')
    if 'FAIL' in (out / 'stage-status.txt').read_text(): errors.append('execution/sampler stage failed')
    seconds = config['seconds']
    window = [r for r in responses.values() if r.get('completedMs', seconds * 1000 + 1) <= seconds * 1000]
    approved = sum(p.get('final_status') == 'APPROVED' and p.get('assertion_ok') == 't' for p in datasets.get('payment', {}).values())
    result = dict(status='FAIL' if errors else 'PASS', errors=errors, request_intents=len(keys),
                  http_failures_or_no_response=sum(r.get('httpStatus') != 200 for r in [responses.get(k,{}) for k in keys]),
                  unknown_timeout_count=sum(d['payment_status']=='UNKNOWN_TIMEOUT' for d in details),
                  processing_count=sum(d['payment_status']=='PROCESSING' for d in details),
                  http_completed_in_window=len(window), http_req_per_second=len(window)/seconds,
                  approved_confirmed_per_second=sum(r.get('httpStatus') == 200 and r.get('finalStatus') == 'APPROVED' and r.get('resultCode') == 'OK' for r in window)/seconds,
                  final_db_approved=approved, final_cohort_approved_per_measurement_second=approved/seconds,
                  payment_statuses=dict(collections.Counter(d['payment_status'] for d in details)),
                  response_statuses=dict(collections.Counter(d['response_status'] for d in details)),
                  measurement_seconds=seconds, graceful_stop_seconds=15, settle_seconds=config['settle'],
                  cohort_rate_is_not_commit_time_throughput=True)
    (out / 'run-summary.json').write_text(json.dumps(result, indent=2) + '\n')
    (out / 'run-summary.txt').write_text('\n'.join('%s=%s' % (k,v) for k,v in result.items()) + '\n')
    return 1 if errors else 0


if __name__ == '__main__':
    command, *args = sys.argv[1:]
    if command == 'reserve': print(reserve(args[0], args[1], int(args[2])))
    elif command == 'fresh': check_fresh(args[0], int(args[1]))
    elif command == 'collect': raise SystemExit(collect(*args))
    elif command == 'checkpoint':
        path, output, date, base, vus, seconds, settle = args
        Path(output).write_text(json.dumps(dict(log=path, checkpoint=checkpoint(path), date=date,
            base=int(base), vus=int(vus), seconds=int(seconds), settle=float(settle)), indent=2))
    else: raise ValueError('unknown command')
