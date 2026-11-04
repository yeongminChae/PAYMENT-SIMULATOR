"""Mac host-VAN/B13 live evidence. No SQL, exec, start or stop against protected B/C."""
import json, subprocess, sys, pathlib, re, os
if not __debug__:
    raise RuntimeError('Python -O disables guards; refused')


def command(args):
    # Do not include output in exceptions: process environments may contain credentials.
    result = subprocess.run(args, text=True, capture_output=True, timeout=60)
    if result.returncode:
        raise RuntimeError('Evidence command failed: '+args[0])
    return result.stdout


def docker(*args):
    return command(['docker', *args])  # Honor Mac Docker context; record daemon identity below.


def overlap(left, right):
    return any(a == b or a.startswith(b.rstrip('/')+'/') or b.startswith(a.rstrip('/')+'/')
               for a in left for b in right)


def validate(copies, protected, roles):
    assert len(copies) == 2 and sorted(roles) == ['B', 'B', 'C', 'C'], 'B/C inventory required'
    ids = [x['Id'] for x in copies+protected]
    assert len(ids) == len(set(ids)), 'container overlap/alias'
    assert all(not x['State']['Running'] for x, role in zip(protected, roles) if role == 'B'), 'B original running'
    # C may be running. It is inspected only; no connections or commands are sent to C.
    assert all(x['State']['Running'] for x in copies), 'copy stopped'
    mounts = lambda c: {m['Source'] for m in c['Mounts']}
    original = set().union(*(mounts(c) for c in protected))
    assert original and all(not overlap(mounts(c), original) for c in copies), 'protected mount overlap'
    assert not overlap(mounts(copies[0]), mounts(copies[1])), 'copy mount overlap'
    for c in copies:
        data = [m for m in c['Mounts'] if m.get('Destination') == '/var/lib/postgresql/data']
        assert len(data) == 1 and data[0]['Type'] == 'volume' and data[0]['RW'], 'writable dedicated PGDATA required'
        h = c['HostConfig']
        assert not any(h.get(k) for k in ('Privileged', 'PidMode', 'CapAdd', 'Devices')), 'privileged copy'
        assert not any('docker.sock' in m['Source'] for m in c['Mounts']), 'Docker socket mount'
        for bindings in c['NetworkSettings']['Ports'].values():
            assert not bindings or all(b['HostIp'] == '127.0.0.1' and b['HostPort'] not in ('5432','5433','9090') for b in bindings), 'unsafe port'
    nets = [set(c['NetworkSettings']['Networks']) for c in copies]
    assert nets == [{'ev01-b13-net'}]*2, 'B13 network mismatch'
    assert all('ev01-b13-net' not in c['NetworkSettings']['Networks'] for c in protected), 'protected network overlap'
    return 'ev01-b13-net'


def query(cfg, sql):
    # COPY container only. No SQL sourced from config. Fail closed on authentication errors.
    text = docker('exec', cfg['container'], 'psql', '-h', '127.0.0.1', '-U', cfg['user'],
                  '-d', cfg['url'].split('/')[-1], '-XAt', '-v', 'ON_ERROR_STOP=1', '-c',
                  'BEGIN READ ONLY; '+sql+'; COMMIT;')
    return json.loads(next(line for line in text.splitlines() if line.startswith(('{','['))))


def host_proof(tcp, backend_rows):
    assert tcp['pid'] == 42831 and tcp['port'] == 19090 and tcp['httpPort'] == 18081
    assert tcp['applicationName'] == 'EV01B13_VAN' and tcp['address'] == '172.19.0.1'
    pid = str(tcp['pid'])
    assert pathlib.Path(command(['ps','-p',pid,'-o','comm=']).strip()).name == 'java', 'VAN PID is not Java'
    start = command(['ps','-p',pid,'-o','lstart=']).strip()
    assert start, 'missing VAN PID'
    # Read in memory only. Never persist command/env containing passwords.
    process = command(['ps','eww','-p',pid,'-o','command='])
    urls = re.findall(r'jdbc:postgresql://[^\s\"\']+', process)
    assert urls and all(re.fullmatch(r'jdbc:postgresql://(?:127\.0\.0\.1|localhost):15433/van_sim\?ApplicationName=EV01B13_VAN', u) for u in urls), 'VAN PID JDBC config mismatch'
    listeners = command(['lsof','-nP','-a','-p',pid,'-iTCP','-sTCP:LISTEN','-Fn'])
    assert all(re.search(r'^n.*:'+str(port)+r'$',listeners,re.M) for port in (19090,18081)), 'VAN listener owner mismatch'
    # Check owners globally, not only the supplied PID (SO_REUSEPORT/another listener).
    for port in (19090,18081):
        owners = command(['lsof','-nP','-iTCP:'+str(port),'-sTCP:LISTEN','-t']).split()
        assert set(owners) == {pid}, 'multiple/foreign listener owners'
    sockets = command(['lsof','-nP','-a','-p',pid,'-iTCP','-sTCP:ESTABLISHED','-Fn'])
    local_ports = set(re.findall(r'^n(?:127\.0\.0\.1|\[::1\]):(\d+)->(?:127\.0\.0\.1|\[::1\]):15433$', sockets, re.M))
    assert local_ports and len(local_ports) == len(backend_rows), 'VAN socket/backend cardinality mismatch'
    assert all(r['datname']=='van_sim' and r['client_addr']=='172.19.0.1' and r['application_name']=='EV01B13_VAN' and r['usename']==tcp['dbUser'] for r in backend_rows), 'VAN backend mismatch'
    assert command(['ps','-p',pid,'-o','lstart=']).strip() == start, 'VAN PID replaced during check'
    return dict(pid=int(pid),start=start,listenerPorts=[19090,18081],localPorts=sorted(local_ports),backends=backend_rows,
                method='PID-start+JDBC-config+listener-owner+socket-count+ApplicationName+NAT-client-address')


def boundary(evidence):
    # Running C state/statistics and VAN pool membership may legitimately vary.
    return dict(daemon=evidence['daemon'], copies=evidence['copies'], protected=evidence['protected'],
                host={k:evidence['host'][k] for k in ('pid','start','listenerPorts')})


def attest(cfg):
    specs = cfg['protectedContainers']
    assert len(specs)==4 and all(s['container'] and not s['container'].startswith('REPLACE') for s in specs), 'fill real B/C inventory first'
    copies = json.loads(docker('inspect', cfg['payment']['container'],cfg['van']['container']))
    protected = json.loads(docker('inspect',*[s['container'] for s in specs]))
    net = validate(copies, protected, [s['role'] for s in specs])
    network = json.loads(docker('network','inspect',net))[0]
    assert set(network['Containers']) == {c['Id'] for c in copies}, 'foreign network member'
    for kind, c, ip, port, database in zip(('payment','van'),copies,('172.19.0.2','172.19.0.3'),('15432','15433'),('payment_sim','van_sim')):
        item=cfg[kind]
        assert item['container']=='ev01-b13-'+kind and item['url']=='jdbc:postgresql://127.0.0.1:'+port+'/'+database
        assert c['NetworkSettings']['Networks'][net]['IPAddress']==ip, 'Docker IP mismatch'
        assert c['NetworkSettings']['Ports']['5432/tcp']==[dict(HostIp='127.0.0.1',HostPort=port)]
        observed=query(item,"SELECT json_build_object('database',current_database(),'system',system_identifier::text,'directory',current_setting('data_directory'),'address',inet_server_addr()::text,'port',inet_server_port(),'user',current_user) FROM pg_control_system()")
        assert observed['database']==database and observed['port']==5432
        # psql uses container loopback; JDBC uses the published network interface. Java checks that interface independently.
        observed['address']=ip
        item['identity']=observed
    rows=query(cfg['van'],"SELECT coalesce(json_agg(x),'[]'::json) FROM (SELECT pid,datname,client_addr::text,client_port,application_name,usename FROM pg_stat_activity WHERE application_name='EV01B13_VAN' ORDER BY pid) x")
    host=host_proof(cfg['tcp'],rows)
    selected=lambda c:{k:c[k] for k in ('Id','Mounts')} | {'networks':c['NetworkSettings']['Networks'],'ports':c['NetworkSettings']['Ports']}
    return dict(daemon=docker('info','--format','{{.ID}}').strip(),copies=[selected(c) for c in copies],
                protected=[dict(role=s['role'],**selected(c)) for c,s in zip(protected,specs)],host=host)


def check(path):
    path=pathlib.Path(path); cfg=json.loads(path.read_text()); expected=json.loads(json.dumps(cfg))
    live=attest(cfg)
    assert cfg==expected, 'live identity changed'
    assert boundary(live)==boundary(json.loads((path.parent/'docker-before.json').read_text())), 'Docker/PID boundary changed'
    return live


def seal(evidence):
    # Only stopped B content is hashed. Live C receives inspect calls only.
    hashes={}
    for original in evidence['protected']:
        if original['role']!='B': continue
        for mount in original['Mounts']:
            assert mount['Type'] in ('volume','bind'), 'unsupported protected mount'
            source=mount.get('Name') if mount['Type']=='volume' else mount['Source']
            hashes[mount['Source']]=docker('run','--rm','--network','none','--mount',
                'type='+mount['Type']+',source='+source+',target=/e,readonly',
                'postgres:17','sh','-ec',
                'find /e -type l -print -quit > /tmp/links; test ! -s /tmp/links; find /e -type f -exec sha256sum {} + > /tmp/hashes; LC_ALL=C sort /tmp/hashes > /tmp/sorted; sha256sum /tmp/sorted').strip()
    assert hashes, 'missing protected B mounts'
    return hashes


if __name__=='__main__':
    if sys.argv[1]=='--check':
        check(sys.argv[2])
    elif sys.argv[1]=='--finish':
        path=pathlib.Path(sys.argv[2]); live=check(path)
        (path.parent/'docker-after.json').write_text(json.dumps(live,indent=2)+'\n')
        hashes=seal(live)
        (path.parent/'source-B-hashes-after.json').write_text(json.dumps(hashes,indent=2)+'\n')
        assert hashes==json.loads((path.parent/'source-B-hashes-before.json').read_text()), 'original B content changed'
    else:
        source=pathlib.Path(sys.argv[1]); out=pathlib.Path(sys.argv[2]); out.mkdir(mode=0o700, exist_ok=False)
        cfg=json.loads(source.read_text()); evidence=attest(cfg)
        (out/'docker-before.json').write_text(json.dumps(evidence,indent=2)+'\n')
        (out/'source-B-hashes-before.json').write_text(json.dumps(seal(evidence),indent=2)+'\n')
        (out/'attested.json').write_text(json.dumps(cfg,indent=2)+'\n')
        print(out/'attested.json')
