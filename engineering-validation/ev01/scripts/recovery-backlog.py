"""C fixture/manifest/검증. SQL은 fixture 준비에만 쓰고 복구는 실제 recoveryJob에 위임한다."""
import argparse
import collections
import csv
import datetime as dt
import importlib.util
import json
import os
import re
from pathlib import Path
import signal
import socket
import subprocess
import sys
import time
from urllib.parse import urlsplit

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
EV = HERE.parent
spec = importlib.util.spec_from_file_location('b_evidence', HERE / 'independent-key-evidence.py')
b = importlib.util.module_from_spec(spec)
spec.loader.exec_module(b)


def db_url(value, kind):
    parsed = urlsplit(value)
    name, port = ('payment_sim_ev01_c',5432) if kind=='payment' else ('van_sim_ev01_c',5433)
    if (parsed.scheme != 'postgresql' or parsed.hostname != 'localhost' or parsed.port != port or
            parsed.path != '/' + name or parsed.query or parsed.fragment or not parsed.username):
        raise ValueError('C writes require explicit localhost:%s/%s URL without options' % (port,name))
    return parsed


def query(kind, sql):
    value = os.environ['C_' + kind.upper() + '_DB_URL']
    db_url(value,kind)
    # 접속 대상을 바꾸는 libpq 환경변수와 psqlrc는 사용하지 않는다.
    env = {k:v for k,v in os.environ.items() if not k.startswith('PG')}
    env['PGCONNECT_TIMEOUT']='5'
    expected='payment_sim_ev01_c' if kind=='payment' else 'van_sim_ev01_c'
    # 같은 접속 세션에서 서버가 보고한 실제 DB를 검사한 뒤에만 SQL을 실행한다.
    guard="DO $$ BEGIN IF current_database() <> '%s' OR inet_server_port() <> 5432 THEN RAISE EXCEPTION 'C DB identity mismatch'; END IF; END $$;\n" % expected
    result = subprocess.run(
        ['psql',value,'-X','-q','-v','ON_ERROR_STOP=1'],input=guard+sql,text=True,capture_output=True,env=env,timeout=60)
    if result.returncode: raise RuntimeError(kind+' SQL failed; credentials/stderr omitted')
    return result.stdout


def rows(kind, select):
    return json.loads(query(kind,"SELECT coalesce(json_agg(x),'[]'::json) FROM ("+select+") x;").strip())


def verify_van_database():
    # macOS lsof의 실제 VAN PID 소켓을 PostgreSQL backend client_port와 대조한다.
    pid=os.environ['VAN_JAVA_PID']
    sockets=subprocess.check_output(['lsof','-nP','-a','-p',pid,'-iTCP','-sTCP:ESTABLISHED','-Fn'],text=True)
    ports=set()
    for line in sockets.splitlines():
        match=re.search(r':(\d+)->(?:127\.0\.0\.1|\[::1\]):5433$',line)
        if match: ports.add(int(match[1]))
    if not ports: raise ValueError('Cannot prove VAN PID connection to localhost:5433; refuse C writes/job')
    backends=rows('van',"SELECT datname,client_port FROM pg_stat_activity WHERE backend_type='client backend'")
    matched=[r for r in backends if r['client_port'] in ports]
    if {r['client_port'] for r in matched}!=ports or any(r['datname']!='van_sim_ev01_c' for r in matched):
        raise ValueError('VAN PID is not exclusively connected to dedicated C VAN DB')
    return matched


def manifest(date, base, count, mixed):
    dt.datetime.strptime(date,'%Y%m%d')
    if base not in range(0,10000,200) or count not in (10,100,500,1000): raise ValueError('fixture bounds')
    if mixed and count!=10: raise ValueError('C2 supports smoke count=10 only')
    cases = [('APPROVAL','APPROVED'),('APPROVAL','NOT_FOUND'),('APPROVAL','UNKNOWN'),
             ('CANCEL','CANCELLED'),('CANCEL','NOT_FOUND'),('REVERSAL','REVERSED'),('REVERSAL','NOT_FOUND')]
    result=[]
    for i in range(count):
        kind, outcome = cases[i%len(cases)] if mixed else ('APPROVAL','APPROVED')
        target='9376-%s-%04d-%04d' % (date,base,i+1)
        original=target if kind=='APPROVAL' else '9376-%s-%04d-%04d' % (date,base+1,i+1)
        resolved=outcome in ('APPROVED','CANCELLED','REVERSED')
        result.append(dict(type=kind,target=target,original=original,attempt_seq=1,
            initial='PENDING' if kind=='REVERSAL' else 'UNKNOWN_TIMEOUT',
            van_outcome=outcome,expected_payment=outcome if resolved else ('PENDING' if kind=='REVERSAL' else 'UNKNOWN_TIMEOUT'),
            expected_task='RESOLVED' if resolved else 'RETRY_WAIT',
            van_id='C-'+target,approval_no='C%06d' % (i+1),synthetic=True))
    return result


def literal(x):
    if x is None: return 'NULL'
    if isinstance(x,int): return str(x)
    return "'"+str(x).replace("'","''")+"'"


def insert(table, values, old=False):
    columns=list(values); vals=[literal(values[c]) for c in columns]
    if old:
        columns+=['created_at','updated_at']; vals += ["CURRENT_TIMESTAMP - INTERVAL '1 day'"]*2
    return 'INSERT INTO '+table+' ('+','.join(columns)+') VALUES ('+','.join(vals)+');\n'


def fixture_sql(items):
    payment='BEGIN;\n'; van='BEGIN;\n'
    for f in items:
        approved_original=f['type']!='APPROVAL'
        payment+=insert('payment_attempt_seq',dict(pos_trx=f['original'],last_seq=1),True)
        payment+=insert('payment_attempt',dict(pos_trx=f['original'],attempt_seq=1,amount=10000,
            card_bin='42424242',card_last4='4242',final_status='APPROVED' if approved_original else 'UNKNOWN_TIMEOUT',
            approval_no='ORIG-'+f['approval_no'] if approved_original else None,
            van_trx_id='ORIG-'+f['van_id'] if approved_original else None),True)
        payment+=insert('payment_external_info',dict(pos_trx=f['original'],attempt_seq=1,card_bin='42424242',
            card_last4='4242',masked_card_no='424242**-****-4242',card_brand='VISA',card_issuer='TEST',
            card_country='TEST',van_provider='TEST'),True)
        def approval(key,status,ident,number):
            return insert('van_approval',dict(van_trx_id=ident,pos_trx=key,attempt_seq=1,amount=10000,
                card_bin='42424242',card_last4='4242',approval_status=status,approval_no=number,
                decline_code=None,processed_at='2020-01-01 00:00:00'))
        if approved_original:
            van+=approval(f['original'],'APPROVED','ORIG-'+f['van_id'],'ORIG-'+f['approval_no'])
            operation=f['type'].lower()
            fields=dict(current_trx_no=f['target'],original_trx_no=f['original'],original_attempt_seq=1)
            fields[operation+'_status']=f['initial']
            if operation=='reversal': fields['amount']=10000
            payment+=insert('payment_'+operation,fields,True)
            if f['van_outcome']!='NOT_FOUND':
                fields=dict(original_pos_trx=f['original'],original_attempt_seq=1,
                    amount=10000,processed_at='2020-01-01 00:00:00',decline_code=None)
                if operation=='cancel':
                    fields.update(original_van_trx_id='ORIG-'+f['van_id'],original_approval_no='ORIG-'+f['approval_no'])
                fields.update({operation+'_pos_trx':f['target'],'van_'+operation+'_trx_id':f['van_id'],
                    operation+'_status':f['van_outcome'],operation+'_approval_no':f['approval_no']})
                van+=insert('van_'+operation,fields)
        elif f['van_outcome']!='NOT_FOUND':
            van+=approval(f['target'],f['van_outcome'],f['van_id'],f['approval_no'] if f['van_outcome']=='APPROVED' else None)
    return payment+'COMMIT;\n',van+'COMMIT;\n'


def snapshot(out,label,items):
    keys={f['target'] for f in items}|{f['original'] for f in items}
    where=','.join(literal(k) for k in sorted(keys))
    data={}
    payment_tables={'attempts':('payment_attempt','pos_trx'),'external':('payment_external_info','pos_trx'),
        'sequences':('payment_attempt_seq','pos_trx'),'cancels':('payment_cancel','current_trx_no'),
        'reversals':('payment_reversal','current_trx_no'),'tasks':('payment_recovery_task','target_trx_no')}
    # PAN/fingerprint를 수집하지 않는다. fixture에 fingerprint 원문도 생성하지 않는다.
    selects={'attempts':'pos_trx,attempt_seq,amount,final_status,approval_no,van_trx_id',
             'external':'pos_trx,attempt_seq','sequences':'pos_trx,last_seq'}
    for name,(table,key) in payment_tables.items():
        data[name]=rows('payment','SELECT '+selects.get(name,'*')+' FROM '+table+' WHERE '+key+' IN ('+where+')')
    data['history']=rows('payment','SELECT h.* FROM payment_recovery_history h JOIN payment_recovery_task t ON h.recovery_task_id=t.id WHERE t.target_trx_no IN ('+where+')')
    for name,key in [('approval','pos_trx'),('cancel','cancel_pos_trx'),('reversal','reversal_pos_trx')]:
        data['van_'+name]=rows('van','SELECT * FROM van_'+name+' WHERE '+key+' IN ('+where+')')
    data['global_backlog']=rows('payment',"SELECT recovery_status,count(*) AS count FROM payment_recovery_task WHERE recovery_status<>'RESOLVED' GROUP BY recovery_status")
    data['batch']=rows('payment','SELECT job_execution_id,status,start_time,end_time FROM batch_job_execution ORDER BY job_execution_id')
    if label!='pre':
        # INSERT는 세션 temp table에만 수행한다. production Task를 SQL로 완료시키지 않는다.
        sql='CREATE TEMP TABLE ev01_c_expected(target_type text,target text,original text,expected_task text);\n'
        for f in items:
            sql+='INSERT INTO ev01_c_expected VALUES ('+','.join(literal(f[k]) for k in ('type','target','original','expected_task'))+');\n'
        evidence=query('payment',sql+(EV/'sql/assert-recovery-backlog.sql').read_text())
        (out/(label+'-assertion.csv')).write_text(evidence)
        data['sql_assertions']=list(csv.DictReader(__import__('io').StringIO(evidence)))
    (out/(label+'.json')).write_text(json.dumps(data,indent=2))
    return data


def validate(items, data, before=False):
    issues=[]
    if not before and any(row.get('assertion_ok')!='t' for row in data.get('sql_assertions',[])):
        issues.append('SQL task/history assertion failed')
    for f in items:
        def one(name,key,value):
            matches=[r for r in data[name] if r[key]==value]
            if len(matches)!=1: issues.append(f['target']+': '+name+' missing/duplicate'); return {}
            return matches[0]
        original=one('attempts','pos_trx',f['original'])
        external=one('external','pos_trx',f['original']); seq=one('sequences','pos_trx',f['original'])
        if original.get('attempt_seq')!=1 or external.get('attempt_seq')!=1 or seq.get('last_seq')!=1:
            issues.append(f['target']+': attempt/external/sequence mismatch')
        target=original if f['type']=='APPROVAL' else one('cancels' if f['type']=='CANCEL' else 'reversals','current_trx_no',f['target'])
        field='final_status' if f['type']=='APPROVAL' else f['type'].lower()+'_status'
        if target.get(field)!=(f['initial'] if before else f['expected_payment']): issues.append(f['target']+': payment status mismatch')
        if f['type']!='APPROVAL' and (target.get('original_trx_no')!=f['original'] or target.get('original_attempt_seq')!=1 or original.get('final_status')!='APPROVED'):
            issues.append(f['target']+': original identity/status mismatch')
        if f['type']!='APPROVAL':
            originals=[r for r in data['van_approval'] if r['pos_trx']==f['original']]
            if (len(originals)!=1 or original.get('approval_no')!='ORIG-'+f['approval_no'] or
                original.get('van_trx_id')!='ORIG-'+f['van_id'] or originals[0].get('approval_no')!=original.get('approval_no') or
                originals[0].get('van_trx_id')!=original.get('van_trx_id') or originals[0].get('attempt_seq')!=1):
                issues.append(f['target']+': original Payment/VAN mismatch')
        if original.get('amount')!=10000: issues.append(f['target']+': original amount mismatch')
        vname='van_'+f['type'].lower(); vkey='pos_trx' if f['type']=='APPROVAL' else f['type'].lower()+'_pos_trx'
        vrows=[r for r in data[vname] if r[vkey]==f['target']]
        if len(vrows)!=(0 if f['van_outcome']=='NOT_FOUND' else 1): issues.append(f['target']+': VAN missing/duplicate')
        if vrows and vrows[0].get('approval_status' if f['type']=='APPROVAL' else field)!=f['van_outcome']: issues.append(f['target']+': VAN status mismatch')
        if not before:
            task=one('tasks','target_trx_no',f['target'])
            if (task.get('recovery_status')!=f['expected_task'] or task.get('target_type')!=f['type'] or
                task.get('original_pos_trx')!=f['original'] or task.get('original_attempt_seq')!=1 or
                task.get('target_attempt_seq')!=(1 if f['type']=='APPROVAL' else None) or
                task.get('claim_token') is not None or task.get('lease_expires_at') is not None):
                issues.append(f['target']+': task/ownership mismatch')
            histories=[r for r in data['history'] if r['recovery_task_id']==task.get('id')]
            if not histories or [r['try_no'] for r in sorted(histories,key=lambda h:h['try_no'])]!=list(range(1,len(histories)+1)):
                issues.append(f['target']+': history missing/nonsequential')
            for h in histories:
                if h['result']!=f['expected_task'] or not h['finished_at'] or h['finished_at']<h['started_at']:
                    issues.append(f['target']+': history result/time mismatch')
            if f['expected_task']=='RESOLVED':
                for name,expected in [('approval_no' if f['type']=='APPROVAL' else f['type'].lower()+'_approval_no',f['approval_no']),
                    ('van_trx_id' if f['type']=='APPROVAL' else 'van_'+f['type'].lower()+'_trx_id',f['van_id'])]:
                    if target.get(name)!=expected: issues.append(f['target']+': final VAN identity mismatch')
            elif not task.get('next_retry_at') or task.get('retry_count',0)<1: issues.append(f['target']+': retry policy missing')
    return issues


def validate_job(evidence, items, first=True):
    issues=[]
    if evidence.get('status')!='COMPLETED' or not evidence.get('end'): issues.append('Job incomplete')
    expected={'discoverAndRegisterRecoveryTasksStep','processRecoveryTasksStep'}
    steps=evidence.get('steps',[])
    if {s['name'] for s in steps}!=expected or any(s['status']!='COMPLETED' or s['durationMs'] is None or s['durationMs']<0 for s in steps):
        issues.append('Step completion/timing missing')
    if first:
        candidates=sum((evidence.get(k,[]) for k in ('approvalCandidates','cancelCandidates','reversalCandidates')),[])
        if collections.Counter(c['targetTrxNo'] for c in candidates)!=collections.Counter(f['target'] for f in items):
            issues.append('actual Repository candidates differ from manifest')
        tasks=[t for t in evidence.get('discoverAndRegisterRecoveryTasksStepTasks',[]) if t.get('target_trx_no') in {f['target'] for f in items}]
        if len(tasks)!=len(items) or any(t['recovery_status']!='PENDING' for t in tasks):
            issues.append('Discovery did not register each fixture as PENDING exactly once')
    return issues


def run_command(command, env, output, timeout):
    # timeout 뒤에도 결과 수집을 계속한다. process group을 종료해 Gradle 자식 JVM도 남기지 않는다.
    with open(output,'w') as stream:
        process=subprocess.Popen(command,env=env,cwd=ROOT,stdout=stream,stderr=subprocess.STDOUT,start_new_session=True)
        try: return dict(exit_code=process.wait(timeout=timeout),timeout=False)
        except subprocess.TimeoutExpired:
            os.killpg(process.pid,signal.SIGTERM)
            try: process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                os.killpg(process.pid,signal.SIGKILL); process.wait()
            return dict(exit_code=process.returncode,timeout=True)
        finally:
            if process.poll() is None:
                os.killpg(process.pid,signal.SIGTERM)
                try: process.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    os.killpg(process.pid,signal.SIGKILL); process.wait()


def job(out, number, timeout, prepare=False):
    verify_van_database()
    url=db_url(os.environ['C_PAYMENT_DB_URL'],'payment')
    from urllib.parse import unquote
    env=dict(os.environ,EV01_C_ALLOW_WRITE='YES',EV01_C_JOB_OUTPUT=str(out/('job-%s.json'%number)),
        EV01_C_RUN_ID=out.name,EV01_C_PREPARE_ONLY='YES' if prepare else 'NO',
        SPRING_DATASOURCE_URL='jdbc:postgresql://localhost:5432/payment_sim_ev01_c',
        SPRING_DATASOURCE_USERNAME=unquote(url.username),SPRING_DATASOURCE_PASSWORD=unquote(url.password or ''),
        POSTGRES_PASSWORD=unquote(url.password or ''),CARD_SECRET_KEY=os.environ.get('CARD_SECRET_KEY','ev01-c-public-synthetic-fixture-secret'),
        PAYMENT_VAN_MODE='tcp',PAYMENT_VAN_TCP_HOST='localhost',PAYMENT_VAN_TCP_PORT='9090',LOG_DIR=str(out/'payment-logs'))
    command=['bash','gradlew','--no-daemon','--console=plain','-I',str(EV/'recovery-launcher.gradle'),':payment-server:ev01RecoveryLaunch']
    # 샘플러는 Gradle launcher가 기록한 실제 Payment Java PID를 사용한다.
    stop=__import__('threading').Event()
    def sample():
        while not stop.wait(0.5):
            try:
                pidfile=out/('job-%s.json.pid'%number)
                if not pidfile.exists(): continue
                pid=pidfile.read_text().strip()
                observed=subprocess.run(['ps','-p',pid,'-o','%cpu=,rss='],text=True,capture_output=True)
                if observed.returncode!=0: continue  # Job JVM이 정상 종료한 뒤 Gradle이 정리 중일 수 있다.
                cpu=observed.stdout.strip()
                with (out/'payment-resource-samples.jsonl').open('a') as stream:
                    stream.write(json.dumps(dict(observed_at=dt.datetime.now(dt.timezone.utc).isoformat(),job=number,pid=pid,cpu_rss=cpu))+'\n')
                if not prepare:
                    sampled=rows('payment','SELECT id,target_trx_no,recovery_status,claim_token,lease_expires_at FROM payment_recovery_task')
                    with (out/'task-state-samples.jsonl').open('a') as stream:
                        stream.write(json.dumps(dict(observed_at=dt.datetime.now(dt.timezone.utc).isoformat(),tasks=sampled))+'\n')
            except Exception as exc:
                with (out/'sampler-errors.txt').open('a') as stream: stream.write(type(exc).__name__+'\n')
    thread=__import__('threading').Thread(target=sample,daemon=True); thread.start()
    try: result=run_command(command,env,out/('job-%s.log'%number),timeout)
    finally: stop.set(); thread.join(timeout=65)
    (out/('job-%s-exit.json'%number)).write_text(json.dumps(result))
    if result['timeout'] or result['exit_code']!=0: raise RuntimeError('Job launcher failed/timeout: '+str(number))
    evidence=json.loads((out/('job-%s.json'%number)).read_text())
    if evidence.get('status')!=('SCHEMA_PREPARED' if prepare else 'COMPLETED'): raise ValueError('Job did not terminate COMPLETED')
    return evidence


def assert_isolated():
    # recoveryJob는 global scan이므로 fixture 이외의 미확정 거래/활성 Task를 절대로 처리하지 않는다.
    checks=["SELECT pos_trx FROM payment_attempt WHERE final_status IS NULL OR final_status='UNKNOWN_TIMEOUT'",
        "SELECT current_trx_no FROM payment_cancel WHERE cancel_status IN ('PENDING','UNKNOWN_TIMEOUT')",
        "SELECT current_trx_no FROM payment_reversal WHERE reversal_status='PENDING'",
        "SELECT id FROM payment_recovery_task WHERE recovery_status<>'RESOLVED'",
        "SELECT job_execution_id FROM batch_job_execution WHERE status IN ('STARTING','STARTED','STOPPING','UNKNOWN')"]
    for select in checks:
        if rows('payment',select): raise ValueError('foreign/unresolved backlog or running batch job; use clean dedicated C DB')


def summarize(out,items,errors,first=None,second=None):
    result=dict(status='FAIL' if errors else 'PASS',errors=errors,count=len(items),
                synthetic_network_failure=True,workers=1,p95_p99='not computed without verified per-task duration cohort')
    if first:
        result['task_statuses']=dict(collections.Counter(t['recovery_status'] for t in first['tasks']))
        result['remaining_backlog']=first['global_backlog']
        durations=[(dt.datetime.fromisoformat(h['finished_at'])-dt.datetime.fromisoformat(h['started_at'])).total_seconds()
                   for h in first['history'] if h['finished_at']]
        result['history_duration_count']=len(durations)
        with (out/'task-durations.csv').open('w') as stream:
            writer=csv.writer(stream); writer.writerow(['task_id','try_no','started_at','finished_at','result','duration_seconds'])
            for h in first['history']:
                writer.writerow([h['recovery_task_id'],h['try_no'],h['started_at'],h['finished_at'],h['result'],
                    (dt.datetime.fromisoformat(h['finished_at'])-dt.datetime.fromisoformat(h['started_at'])).total_seconds() if h['finished_at'] else ''])
    try:
        execution=json.loads((out/'job-1.json').read_text()); result['job']=execution
        result['discovered_candidates']=sum(len(execution.get(k,[])) for k in ('approvalCandidates','cancelCandidates','reversalCandidates'))
        result['new_registered_tasks']=len([t for t in execution.get('discoverAndRegisterRecoveryTasksStepTasks',[]) if t['target_trx_no'] in {f['target'] for f in items}])
        steps=execution['steps']; worker=next(s for s in steps if s['name']=='processRecoveryTasksStep')
        result['discovery_duration_ms']=next(s for s in steps if s['name']=='discoverAndRegisterRecoveryTasksStep')['durationMs']
        result['worker_duration_ms']=worker['durationMs']; result['job_duration_ms']=execution['durationMs']
        result['resolved_tasks_per_worker_second']=sum(t['recovery_status']=='RESOLVED' for t in first['tasks'])/(worker['durationMs']/1000) if first and worker['durationMs'] else None
    except Exception: result['job_metrics']='unavailable'
    (out/'run-summary.json').write_text(json.dumps(result,indent=2))
    (out/'run-summary.txt').write_text('\n'.join('%s=%s'%(k,v) for k,v in result.items())+'\n')
    metrics={k:v for k,v in result.items() if k!='job'}
    (out/'report.md').write_text('# EV-01 C '+out.name+'\n\nSynthetic UNKNOWN_TIMEOUT fixture; not a measured network outage. Single Worker.\n\n```json\n'+json.dumps(metrics,indent=2)+'\n```\n\nSee fixture-manifest.json, pre/post/replay.json, job-*.json, task-durations.csv and task-state-samples.jsonl. Wait states are samples, not cumulative lock wait duration.\n')


def trial(args):
    date=dt.datetime.now(dt.timezone.utc).strftime('%Y%m%d')
    ledger=os.environ.get('EV01_C_RESERVATION_DIR',str(Path.home()/'.payment-simulator/ev01-c-reservations'))
    base=b.reserve(ledger,date,200)
    out=EV/'results/C'/('%s-p%d-%s'%(dt.datetime.now(dt.timezone.utc).strftime('%Y%m%dT%H%M%SZ'),base,'mixed' if args.mixed else 'approval'))
    out.mkdir(parents=True,exist_ok=False)
    items=manifest(date,base,args.count,args.mixed)
    (out/'fixture-manifest.json').write_text(json.dumps(items,indent=2))
    errors=[]; samplers=[]; first=None; second=None; prepared=False
    def attempt(label,action):
        try: return action()
        except Exception as exc:
            errors.append(label+': '+str(exc)); return None
    try:
        checkpoint=b.checkpoint(args.van_log)
        van_connection=verify_van_database()
        (out/'van-db-identity.json').write_text(json.dumps(van_connection,indent=2))
        (out/'environment.json').write_text(json.dumps(dict(head=subprocess.check_output(['git','rev-parse','HEAD'],cwd=ROOT,text=True).strip(),
            checkpoint=checkpoint,van_log=args.van_log,reservation=ledger,base=base,date=date,synthetic=True,timeout=args.timeout),indent=2))
        # 초기 schema 생성도 전용 빈 DB에서만 test launcher로 수행한다.
        if not rows('payment',"SELECT tablename FROM pg_tables WHERE schemaname='public'"):
            job(out,'prepare',args.timeout,True)
        assert_isolated()
        keys={f['target'] for f in items}|{f['original'] for f in items}
        # 모든 생성 식별자에 대한 충돌 검사. fixture 삽입은 각각 한 transaction이며 cross-DB 원자성은 없다.
        for kind,tables in [('payment',[('payment_attempt','pos_trx'),('payment_external_info','pos_trx'),('payment_attempt_seq','pos_trx'),('payment_cancel','current_trx_no'),('payment_reversal','current_trx_no'),('payment_recovery_task','target_trx_no')]),
                            ('van',[('van_approval','pos_trx'),('van_cancel','cancel_pos_trx'),('van_reversal','reversal_pos_trx')])]:
            for table,key in tables:
                if rows(kind,'SELECT '+key+' FROM '+table+' WHERE '+key+' IN ('+','.join(literal(k) for k in keys)+')'):
                    raise ValueError('fixture identifier already exists: '+table)
        payment,van=fixture_sql(items)
        (out/'fixture-payment.sql').write_text(payment); (out/'fixture-van.sql').write_text(van)
        query('van',van); query('payment',payment)
        pre=snapshot(out,'pre',items)
        pre_errors=validate(items,pre,True)
        if pre_errors: raise ValueError('; '.join(pre_errors))
        if pre['tasks']: raise ValueError('fixture already has Recovery Tasks')
        prepared=True
        # DB sampler는 기존 A 스크립트를 재사용한다.
        stream=(out/'db-waits.stderr').open('w')
        samplers.append((subprocess.Popen(['bash',str(HERE/'sample-payment-db-waits.sh'),os.environ['C_PAYMENT_DB_URL'],str(out/'db-waits.csv'),'0.5'],stderr=stream),stream))
        # VAN PID와 PostgreSQL 자원은 별도로 관찰한다. Payment는 launcher PID로 수집한다.
        stream=(out/'system-resources.stderr').open('w')
        samplers.append((subprocess.Popen(['bash',str(HERE/'sample-recovery-resources.sh'),str(out/'van-postgres-resources.csv')],stderr=stream),stream))
        completed=attempt('job-1',lambda:job(out,1,args.timeout))
        if completed: errors.extend(validate_job(completed,items))
        first=attempt('post snapshot',lambda:snapshot(out,'post',items))
        if first: errors.extend(validate(items,first))
        if first:
            for name in ('van_approval','van_cancel','van_reversal'):
                if sorted(pre[name],key=str)!=sorted(first[name],key=str): errors.append('Recovery mutated VAN ledger: '+name)
        # 명시적인 두 번째 invocation은 별도 JobParameters로 1회만 실행한다. 승인 재전송은 없다.
        if completed and first and not errors:
            replay_job=attempt('job-2 replay',lambda:job(out,2,args.timeout))
            if replay_job: errors.extend(validate_job(replay_job,items,False))
            second=attempt('replay snapshot',lambda:snapshot(out,'replay',items))
            if second:
                errors.extend(validate(items,second))
                for f in items:
                    if f['expected_task']=='RESOLVED':
                        task=next(t for t in second['tasks'] if t['target_trx_no']==f['target'])
                        if len([h for h in second['history'] if h['recovery_task_id']==task['id']])!=1:
                            errors.append(f['target']+': replay duplicated resolved history')
                for name in ('attempts','external','sequences','cancels','reversals','van_approval','van_cancel','van_reversal'):
                    if sorted(first[name],key=str)!=sorted(second[name],key=str): errors.append('replay changed ledger: '+name)
    except Exception as exc: errors.append('prepare/execution: '+str(exc))
    finally:
        for process,stream in samplers:
            if process.poll() is not None: errors.append('sampler exited early')
            process.terminate()
            try: process.wait(timeout=5)
            except subprocess.TimeoutExpired: process.kill(); process.wait()
            stream.close()
        # 준비 중 실패해도 가능한 한 부분 fixture/Job/로그 증거를 보관한다.
        attempt('final snapshot',lambda:snapshot(out,'final',items))
        if 'checkpoint' in locals(): attempt('VAN delta',lambda:b.delta(args.van_log,checkpoint,out/'van-log-delta.txt'))
        if prepared and (out/'van-log-delta.txt').exists():
            text=(out/'van-log-delta.txt').read_text()
            log_evidence=[]
            for f in items:
                lines=[line for line in text.splitlines() if ('targetTrxNo='+f['target']+',') in line and '[van-tcp][inquiry][received]' in line]
                histories=(second or first or {}).get('history',[])
                tasks=(second or first or {}).get('tasks',[])
                ids={t['id'] for t in tasks if t['target_trx_no']==f['target']}
                if len(lines)!=len([h for h in histories if h['recovery_task_id'] in ids]) or not lines:
                    errors.append(f['target']+': Inquiry/history count mismatch')
                results=[line for line in text.splitlines() if ('targetTrxNo='+f['target']+',') in line and '[van-tcp][inquiry][result]' in line]
                expected_code='NOT_FOUND' if f['van_outcome']=='NOT_FOUND' else 'SUCCESS'
                if (len(results)!=len(lines) or any('resultCode='+expected_code+',' not in line or
                    (expected_code=='SUCCESS' and 'status='+f['van_outcome']+',' not in line) for line in results) or
                    any('targetType='+f['type']+',' not in line for line in lines)):
                    errors.append(f['target']+': Inquiry result differs from manifest')
                log_evidence.append(dict(target=f['target'],expected_result=expected_code,inquiry_received=len(lines),
                    inquiry_results=len(results),history_count=len([h for h in histories if h['recovery_task_id'] in ids])))
                if any(any(('posTrx='+key) in line for key in (f['target'],f['original'])) and '[van-tcp][approval][received]' in line for line in text.splitlines()):
                    errors.append(f['target']+': blind approval replay observed')
            if any(tag in text for tag in ('[van-tcp][cancel][received]','[van-tcp][reversal][received]')):
                errors.append('blind cancel/reversal replay observed')
            (out/'van-log-assertion.json').write_text(json.dumps(log_evidence,indent=2))
        for name in ('db-waits.stderr','system-resources.stderr','sampler-errors.txt'):
            if (out/name).exists() and (out/name).stat().st_size: errors.append(name+' has errors')
        if (out/'task-state-samples.jsonl').exists():
            running=[]
            for line in (out/'task-state-samples.jsonl').read_text().splitlines():
                sample=json.loads(line)
                for task in sample['tasks']:
                    if task['recovery_status']=='RUNNING':
                        running.append(task)
                        if not task['claim_token'] or not task['lease_expires_at']: errors.append('observed RUNNING without owner/lease')
            (out/'ownership-observation.json').write_text(json.dumps(dict(running_samples=len(running),
                every_task_claim_observed=False,fencing_test_reference='PostgresRecoveryLeaseFencingIntegrationTest'),indent=2))
        summarize(out,items,errors,first,second)
    print(('FAIL' if errors else 'PASS')+': '+str(out),flush=True)
    return bool(errors)


def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--count',type=int,choices=[10,100,500,1000],default=100)
    parser.add_argument('--repeat',type=int,default=3)
    parser.add_argument('--smoke',action='store_true'); parser.add_argument('--mixed',action='store_true')
    parser.add_argument('--timeout',type=int,default=900)
    parser.add_argument('--van-log',default=str(ROOT/'logs/van-simulator.log'))
    args=parser.parse_args()
    if args.smoke: args.count=10; args.repeat=1
    if args.mixed and not args.smoke: parser.error('C2 supports --mixed --smoke only')
    if not 1<=args.repeat<=3 or not 1<=args.timeout<=3600: parser.error('bounded repeat/timeout required')
    if os.environ.get('EV01_C_ALLOW_WRITE')!='YES': parser.error('Set EV01_C_ALLOW_WRITE=YES for dedicated test DB only')
    for kind in ('payment','van'): db_url(os.environ['C_'+kind.upper()+'_DB_URL'],kind)
    pid=os.environ.get('VAN_JAVA_PID','')
    if not pid.isdigit() or 'java' not in subprocess.check_output(['ps','-p',pid,'-o','comm='],text=True).lower():
        parser.error('VAN_JAVA_PID must identify the dedicated VAN Java process')
    if not subprocess.check_output(['docker','context','show'],text=True).strip().startswith('colima'):
        parser.error('Select Colima Docker context first')
    subprocess.run(['docker','inspect','payment-postgres','van-postgres'],stdout=subprocess.DEVNULL,check=True)
    # 8080을 점유하는 Payment 서버를 중지해야 한다. C 중에는 B 서버를 기동할 수 없다.
    guard=socket.socket(); guard.bind(('localhost',8080)); guard.listen(1)
    active=Path.home()/'.payment-simulator/ev01-c-active'
    active.parent.mkdir(parents=True,exist_ok=True); active.mkdir()
    retain_marker=False
    try:
        processes=subprocess.check_output(['ps','-Ao','args='],text=True)
        if 'independent-key-load.js' in processes or 'run-independent-key-load.sh' in processes:
            raise ValueError('B workload is active; stop it before C')
        for _ in range(args.repeat):
            if trial(args): retain_marker=True; return 1
        return 0
    except BaseException:
        retain_marker=True
        raise
    finally:
        # timeout Job은 DB metadata에 남아 다음 실행의 사전 검사에서 차단된다.
        if not retain_marker: active.rmdir()
        guard.close()


if __name__=='__main__': raise SystemExit(main())
