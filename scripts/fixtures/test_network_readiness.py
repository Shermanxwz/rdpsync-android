#!/usr/bin/env python3
"""Route/address-only pretest regressions. No network or device is used."""
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from emulator_boot_health import Gate, network_route_state
from test_emulator_boot_health import FakeAdb, HOME

ADDRESS = '3: wlan0    inet 10.0.2.16/24 brd 10.0.2.255 scope global wlan0\\       valid_lft forever preferred_lft forever\n'
ROUTE = '10.0.2.2 dev wlan0 table wlan0 src 10.0.2.16 uid 2000\n    cache\n'
UNREACHABLE = (2, '', 'RTNETLINK answers: Network is unreachable\n')

class NetworkFake(FakeAdb):
    def __init__(self, responses=None, **kwargs):
        super().__init__(**kwargs); self.responses = responses or [(0, ROUTE, '')]
        self.route_attempts = 0; self.address = ADDRESS; self.address_error = ''
        self.final_unhealthy = False; self.late = False; self.extra_device = False
        self.timeouts = []
    def run(self, args, **kwargs):
        self.timeouts.append((self.clock, kwargs['timeout']))
        if 'ip' in args:
            self.calls.append(args); self.clock += .05
            suffix = args[args.index('ip')+1:]
            if suffix == ['-o', '-4', 'addr', 'show']:
                result = (0, self.address, self.address_error)
            elif suffix == ['-4', 'route', 'show', 'table', 'all']: result = (0, '', '')
            elif suffix == ['-4', 'route', 'get', '10.0.2.2']:
                result = self.responses[min(self.route_attempts, len(self.responses)-1)]
                self.route_attempts += 1
                if result[0] == 0 and self.final_unhealthy: self.error = 'systemui'
                if result[0] == 0 and self.late: self.clock = 119.95
            else: raise AssertionError(args)
            return subprocess.CompletedProcess(args, result[0], result[1].encode(), result[2].encode())
        if args == ['adb','devices'] and self.route_attempts and self.extra_device:
            return subprocess.CompletedProcess(args, 0, b'List of devices attached\nemulator-5554\tdevice\nphysical\tdevice\n', b'')
        return super().run(args, **kwargs)

class NetworkTests(unittest.TestCase):
    def exercise(self, fake, error=False, fixture_network='gnome'):
        with tempfile.TemporaryDirectory() as d:
            out=Path(d)/'health'; gate=Gate(out,fake.run,lambda:fake.clock,fake.sleep,
                {'GITHUB_ACTIONS':'true','RUNNER_ENVIRONMENT':'github-hosted'},fixture_network=fixture_network)
            if error:
                with self.assertRaises(Exception): gate.execute()
            else: gate.execute()
            report=json.loads((out/'boot-health.json').read_text())
            self.assertEqual(not error, report['success'])
            self.assertFalse(any(any(x in c for x in ['nc','svc','reboot','force-stop','start','instrument']) for c in fake.calls))
            self.assertTrue(all(timeout <= 120-start+1e-8 for start,timeout in fake.timeouts))
            return report
    def test_valid_target_with_empty_all_tables_is_ready(self):
        r=self.exercise(NetworkFake());self.assertTrue(r['fixture_route']['ready'])
        self.assertEqual(1,len(r['fixture_route']['observations']))
    def test_host_simulated_no_route_old_home_only_passes_new_route_gate_fails(self):
        # Counterfactual host fake only, not a replay/proof of the actual guest outage.
        old=NetworkFake([UNREACHABLE]);old_report=self.exercise(old,fixture_network=None)
        self.assertTrue(old_report['success']);self.assertEqual(0,old.route_attempts)
        self.assertNotIn('fixture_route',old_report)
        f=NetworkFake([UNREACHABLE]);r=self.exercise(f,True)
        self.assertFalse(r['fixture_route']['ready']);self.assertEqual(30,f.route_attempts)
    def test_explicit_unreachable_then_actual_matched_route(self):
        f=NetworkFake([UNREACHABLE,(0,ROUTE,'')]);r=self.exercise(f)
        self.assertEqual(2,f.route_attempts);self.assertEqual('target_route_unreachable',r['fixture_route']['observations'][0]['reason'])
    def test_permission_error_is_not_pending(self):
        for result in [(2,'','RTNETLINK answers: Permission denied\n'),(0,ROUTE,'Permission denied\n'),(1,'','')]:
            f=NetworkFake([result]);self.exercise(f,True);self.assertEqual(1,f.route_attempts)
    def test_permission_in_address_stderr_is_not_ready(self):
        f=NetworkFake();f.address_error='Permission denied';self.exercise(f,True);self.assertEqual(0,f.route_attempts)
    def test_unknown_flags_and_malformed_fail_immediately(self):
        for route in ['', 'garbage\n',ROUTE+ROUTE,ROUTE.replace('dev wlan0',''),ROUTE.replace('src','banana'),ROUTE.rstrip(), 'blackhole 10.0.2.2\n','unreachable 10.0.2.2\n']:
            f=NetworkFake([(0,route,'')]);self.exercise(f,True);self.assertEqual(1,f.route_attempts)
    def test_source_missing_unbound_or_loopback_never_passes(self):
        for route in [ROUTE.replace(' src 10.0.2.16',''),ROUTE.replace('10.0.2.16','10.0.2.17'),ROUTE.replace('dev wlan0','dev lo'),ROUTE.replace('10.0.2.16','127.0.0.1')]:
            self.assertFalse(network_route_state(ADDRESS,route,0,'')['ready'])
    def test_no_fixed_interface_or_ip(self):
        self.assertTrue(network_route_state(ADDRESS.replace('wlan0','eth0').replace('10.0.2.16','192.168.5.4'),ROUTE.replace('wlan0','eth0').replace('10.0.2.16','192.168.5.4'),0,'')['ready'])
    def test_empty_addresses_is_pending_not_success(self):
        self.assertFalse(network_route_state('',ROUTE,0,'')['ready'])
    def test_malformed_addresses_fail_closed(self):
        for addr in ['Permission denied\n',ADDRESS.rstrip(),ADDRESS.replace('/24','/99'),ADDRESS.replace('scope global','scope alien'),ADDRESS+'junk\n']:
            with self.assertRaises(ValueError):network_route_state(addr,ROUTE,0,'')
    def test_final_home_snapshot_rechecks_after_route(self):
        f=NetworkFake();f.final_unhealthy=True;self.exercise(f,True)
    def test_final_snapshot_cannot_overrun_original_deadline(self):
        f=NetworkFake();f.late=True;self.exercise(f,True)
    def test_new_device_during_wait_fails_closed(self):
        f=NetworkFake([UNREACHABLE]);f.extra_device=True;self.exercise(f,True);self.assertEqual(1,f.route_attempts)
    def test_all_table_diagnostic_permission_stderr_is_fatal(self):
        f=NetworkFake();orig=f.run
        def run(args,**kw):
            if args[-4:]==['route','show','table','all']:
                return subprocess.CompletedProcess(args,0,b'',b'Permission denied\n')
            return orig(args,**kw)
        f.run=run;self.exercise(f,True);self.assertEqual(0,f.route_attempts)
    def test_missing_and_unbound_source_remain_red_in_full_gate(self):
        for route in [ROUTE.replace(' src 10.0.2.16',''),ROUTE.replace('10.0.2.16','10.0.2.17')]:
            f=NetworkFake([(0,route,'')]);r=self.exercise(f,True)
            self.assertEqual(30,f.route_attempts);self.assertFalse(r['fixture_route']['ready'])
    def test_unreviewed_api_or_preset_fails(self):
        self.exercise(NetworkFake(sdk='29'),True)
        with self.assertRaises(ValueError):Gate('/tmp/unused',fixture_network='arbitrary-host')
    def test_timeout_fails_without_retry(self):
        f=NetworkFake();orig=f.run
        def run(args,**kw):
            if 'ip' in args:raise subprocess.TimeoutExpired(args,kw['timeout'])
            return orig(args,**kw)
        f.run=run;self.exercise(f,True);self.assertEqual(0,f.route_attempts)

if __name__=='__main__':unittest.main()
