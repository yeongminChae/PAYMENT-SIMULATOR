import unittest, copy, json
from unittest.mock import patch
from isolation import validate, attest, seal

def container(n,running=True,net='ev01-b13-net',source=None):
    return dict(Id=n,State=dict(Running=running),Mounts=[dict(Source=source or '/vol/'+n,Type='volume',Destination='/var/lib/postgresql/data',RW=True)],
        HostConfig={},NetworkSettings=dict(Networks={net:{}},Ports={'5432/tcp':[dict(HostIp='127.0.0.1',HostPort='15432')]}))

class IsolationTest(unittest.TestCase):
    def setUp(self):
        self.c=[container(n) for n in ('p','v')]
        self.o=[container(n,False,'original') for n in ('bp','bv','cp','cv')]
    def test_valid_physical_copy_can_share_system_identifier(self):
        self.assertEqual('ev01-b13-net',validate(self.c,self.o,['B','B','C','C']))
    def reject(self,change):
        change()
        with self.assertRaises(AssertionError):validate(self.c,self.o,['B','B','C','C'])
    def test_same_container(self):self.reject(lambda:self.c.__setitem__(0,self.o[0]))
    def test_shared_source_volume(self):self.reject(lambda:self.c[0]['Mounts'][0].update(Source='/vol/bp'))
    def test_nested_source_bind(self):self.reject(lambda:self.c[0]['Mounts'][0].update(Source='/vol/bp/subdir'))
    def test_readonly_pgdata(self):self.reject(lambda:self.c[0]['Mounts'][0].update(RW=False))
    def test_running_c_allowed(self):
        self.o[2]['State']['Running']=True
        self.o[3]['State']['Running']=True
        validate(self.c,self.o,['B','B','C','C'])
    def test_running_original(self):self.reject(lambda:self.o[0]['State'].update(Running=True))
    def test_default_network(self):self.reject(lambda:self.c[0]['NetworkSettings'].update(Networks={'bridge':{}}))
    def test_original_port(self):self.reject(lambda:self.c[0]['NetworkSettings']['Ports']['5432/tcp'][0].update(HostPort='5432'))
    def test_public_bind(self):self.reject(lambda:self.c[0]['NetworkSettings']['Ports']['5432/tcp'][0].update(HostIp='0.0.0.0'))
    def test_copy_volume_overlap(self):self.reject(lambda:self.c[1].update(Mounts=self.c[0]['Mounts']))
    def test_protected_network(self):self.reject(lambda:self.o[0]['NetworkSettings'].update(Networks={'ev01-b13-net':{}}))
    def test_privileged(self):self.reject(lambda:self.c[1]['HostConfig'].update(Privileged=True))
    def test_seal_never_reads_running_c_mounts(self):
        evidence={'protected':[{'role':'B','Mounts':[dict(Type='volume',Name='original-b',Source='/vol/b')]},
                               {'role':'C','Mounts':[dict(Type='volume',Name='original-c',Source='/vol/c')]}]}
        with patch('isolation.docker',return_value='hash') as mocked:
            self.assertEqual({'/vol/b':'hash'},seal(evidence))
            self.assertEqual(1,mocked.call_count)
            self.assertIn('type=volume,source=original-b,target=/e,readonly',mocked.call_args.args)
            self.assertIn('none',mocked.call_args.args)
    def test_attest_only_executes_on_b13_copies(self):
        copies=[container('p'),container('v')]
        for c,ip,port in zip(copies,['172.19.0.2','172.19.0.3'],['15432','15433']):
            c['NetworkSettings']['Networks']['ev01-b13-net']={'IPAddress':ip}
            c['NetworkSettings']['Ports']['5432/tcp'][0]['HostPort']=port
        for c in self.o[2:]:c['State']['Running']=True
        cfg={'protectedContainers':[{'role':r,'container':n} for r,n in zip(['B','B','C','C'],['bp','bv','cp','cv'])],
             'payment':dict(container='ev01-b13-payment',url='jdbc:postgresql://127.0.0.1:15432/payment_sim',user='postgres'),
             'van':dict(container='ev01-b13-van',url='jdbc:postgresql://127.0.0.1:15433/van_sim',user='postgres'),'tcp':{}}
        calls=[]
        def fake(*args):
            calls.append(args)
            if args[0]=='inspect':return json.dumps(copies if args[1]=='ev01-b13-payment' else self.o)
            if args[0]=='network':return json.dumps([{'Containers':{'p':{},'v':{}}}])
            if args[0]=='info':return 'test-daemon'
            assert args[0]=='exec' and args[1] in ('ev01-b13-payment','ev01-b13-van')
            assert 'BEGIN READ ONLY' in args[-1]
            if 'pg_control_system' in args[-1]:
                self.assertIn('host(inet_server_addr())',args[-1])
                self.assertNotIn('inet_server_addr()::text',args[-1])
                return json.dumps(dict(database='payment_sim' if args[1].endswith('payment') else 'van_sim',port=5432,system='123',directory='/data',address='127.0.0.1',user='postgres'))
            self.assertIn('host(client_addr) AS client_addr',args[-1])
            self.assertNotIn('client_addr::text',args[-1])
            return '[]'
        with patch('isolation.docker',side_effect=fake),patch('isolation.host_proof',return_value={'pid':42831}):
            evidence=attest(cfg)
        self.assertEqual('test-daemon',evidence['daemon'])
        self.assertFalse(any(c[0]=='exec' and c[1] in ('bp','bv','cp','cv') for c in calls))

if __name__=='__main__': unittest.main()
