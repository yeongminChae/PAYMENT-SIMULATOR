import copy,unittest
from unittest.mock import patch
from isolation import validate,profile,attest
from test_isolation import container
import test_host_proof as hp
from isolation import host_proof

class RecheckProfileTest(unittest.TestCase):
    def setUp(self):
        self.c=[container(x,net='ev01-b13r2-20261111') for x in ('p','v')]
        self.o=[container(x,False,'original') for x in ('bp','bv','cp','cv','fp','fv')]
        self.roles=['B','B','C','C','FAILED','FAILED']
    def test_host_boundary_r2_and_wrong_nat(self):
        h=hp.HostProofTest();h.setUp()
        h.cfg.update(port=29090,httpPort=28081,applicationName='EV01B13R2_VAN',address='172.29.0.1')
        h.rows[0].update(client_addr='172.29.0.1',application_name='EV01B13R2_VAN')
        h.process=h.process.replace('15433','16433').replace('EV01B13_VAN','EV01B13R2_VAN')
        h.listener=h.listener.replace('19090','29090').replace('18081','28081')
        h.socket=h.socket.replace('15433','16433')
        with patch('isolation.command',side_effect=h.command):host_proof(h.cfg,h.rows,'recheck-20261111')
        h.starts=['same','same'];h.rows[0]['client_addr']='172.19.0.1'
        with patch('isolation.command',side_effect=h.command):
            with self.assertRaises(AssertionError):host_proof(h.cfg,h.rows,'recheck-20261111')
    def test_exact_profile(self):
        self.assertEqual('ev01-b13r2-20261111',validate(self.c,self.o,self.roles,'recheck-20261111'))
    def test_unknown_profile(self):
        with self.assertRaises(AssertionError):profile('other')
    def test_failed_volume_shared_rejected(self):
        self.c[0]['Mounts']=self.o[4]['Mounts']
        with self.assertRaises(AssertionError):validate(self.c,self.o,self.roles,'recheck-20261111')
    def test_failed_container_running_rejected(self):
        self.o[4]['State']['Running']=True
        with self.assertRaises(AssertionError):validate(self.c,self.o,self.roles,'recheck-20261111')
    def test_legacy_network_rejected(self):
        self.c[0]['NetworkSettings']['Networks']={'ev01-b13-net':{}}
        with self.assertRaises(AssertionError):validate(self.c,self.o,self.roles,'recheck-20261111')
    def test_incomplete_protected_inventory_rejected(self):
        with self.assertRaises(AssertionError):attest({'profile':'recheck-20261111','protectedContainers':[]})

if __name__=='__main__':unittest.main()
