"""Mock-only Mac process evidence tests. Does not inspect real processes/DBs."""
import copy, unittest
from unittest.mock import patch
from isolation import host_proof

class HostProofTest(unittest.TestCase):
    def setUp(self):
        self.cfg=dict(pid=42831,port=19090,httpPort=18081,applicationName='EV01B13_VAN',address='172.19.0.1',dbUser='postgres')
        self.rows=[dict(pid=17,datname='van_sim',client_addr='172.19.0.1',client_port=9999,application_name='EV01B13_VAN',usename='postgres')]
        self.listener='p42831\nn*:19090\nn*:18081\n'
        self.socket='p42831\nn127.0.0.1:55555->127.0.0.1:15433\n'
        self.owner='42831\n'
        self.process='java -jar van.jar VAN_DB_URL=jdbc:postgresql://127.0.0.1:15433/van_sim?ApplicationName=EV01B13_VAN VAN_DB_PASSWORD=not-persisted'
        self.starts=['Wed Nov 4 09:00:00 2026']*2
    def command(self,args):
        if args[0]=='ps':
            if 'comm=' in args:return '/usr/bin/java'
            if 'lstart=' in args:return self.starts.pop(0)
            return self.process
        if '-t' in args:return self.owner
        return self.socket if '-sTCP:ESTABLISHED' in args else self.listener
    def proof(self):
        with patch('isolation.command',side_effect=self.command):return host_proof(self.cfg,self.rows)
    def test_nat_proof_not_raw_port_equality(self):
        result=self.proof()
        self.assertEqual(['55555'],result['localPorts'])
        self.assertNotIn('not-persisted',str(result))
    def test_unexpected_raw_inet_text_still_fails_closed(self):
        # SQL host(client_addr) must strip PostgreSQL's /32; do not weaken equality here.
        self.rows[0]['client_addr']='172.19.0.1/32'
        with self.assertRaises(AssertionError):self.proof()
    def test_wrong_database_still_rejected(self):
        self.rows[0]['datname']='van_sim_c'
        with self.assertRaises(AssertionError):self.proof()
    def test_other_db_url_rejected(self):
        self.process=self.process.replace(':15433/van_sim',':5433/van_sim')
        with self.assertRaises(AssertionError):self.proof()
    def test_other_listener_owner_rejected(self):
        self.owner='42831\n999\n'
        with self.assertRaises(AssertionError):self.proof()
    def test_wrong_nat_backend_rejected(self):
        self.rows[0]['client_addr']='172.19.0.9'
        with self.assertRaises(AssertionError):self.proof()
    def test_pool_cardinality_mismatch_rejected(self):
        self.rows.append(dict(self.rows[0]))
        with self.assertRaises(AssertionError):self.proof()
    def test_pid_reuse_rejected(self):
        self.starts[1]='Wed Nov 4 10:00:00 2026'
        with self.assertRaises(AssertionError):self.proof()
    def test_missing_backend_rejected(self):
        self.rows=[]
        with self.assertRaises(AssertionError):self.proof()
if __name__=='__main__':unittest.main()
