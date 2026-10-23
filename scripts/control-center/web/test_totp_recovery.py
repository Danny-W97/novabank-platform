"""Isolated backup/transport fixtures. Never reads an actual env, key, backup or database."""
import base64
import hashlib
import json
from pathlib import Path
import subprocess
import tempfile
import time
import unittest
from unittest.mock import patch
import zipfile

from totp_recovery import TotpRecovery, RecoveryFailure, INSPECT, VERIFY, key_id, validate_keys
from operator_settings import ensure_backup_owner

KEY = base64.b64encode(bytes([31])*32).decode()
ENCRYPTED = 'synthetic-iv.synthetic-ciphertext'
JOB = 'a'*32


class RecoveryTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(); self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.directory = ensure_backup_owner(self.root, '.novabank/backups/dev', create=True)
        self.run_dir = self.directory/'20261019T100000Z'; self.run_dir.mkdir()
        for name in ('bank', 'shop'):
            path = self.run_dir/f'{name}_{self.run_dir.name}.dump'; path.write_bytes(b'PGDMP synthetic fixture')
            Path(str(path)+'.sha256').write_text(hashlib.sha256(path.read_bytes()).hexdigest()+'  '+path.name+'\n')
        self.jar = self.root/'target/Bank-demo-0.0.1-SNAPSHOT.jar'; self.jar.parent.mkdir()
        with zipfile.ZipFile(self.jar, 'w') as archive:
            archive.writestr('BOOT-INF/classes/org/demo/bankdemo/maintenance/TotpRecoveryVerificationCommand.class', b'synthetic fixture')
        self.commands = []; self.inputs = []; self.rows = [{'id': 1, 'encrypted': ENCRYPTED}]
        self.failed = None; self.cleanup_failure = False; self.output = []; self.java_failure = False
        self.settings = {'backupDirectory': '.novabank/backups/dev'}
        self.service = TotpRecovery(self.root, None, runner=self.runner)

    def runner(self, args, **kwargs):
        self.commands.append(args); self.inputs.append(kwargs.get('input'))
        if args[0] == 'synthetic-java':
            payload = json.loads(kwargs['input'])
            return subprocess.CompletedProcess(args, 1 if self.java_failure else 0,
                json.dumps({'verified': len(payload['ciphertexts'])}).encode(), b'never print child details')
        if 'pg_restore' in args and self.failed == 'restore':
            return subprocess.CompletedProcess(args, 1, b'private backup detail must stay hidden')
        if 'rm' in args and self.cleanup_failure:
            return subprocess.CompletedProcess(args, 1, b'raw cleanup details')
        if '--format' in args:
            if 'ps' in args:
                return subprocess.CompletedProcess(args, 0, ('novabank-totp-recovery-'+JOB).encode())
            return subprocess.CompletedProcess(args, 0, JOB.encode())
        if 'psql' in args:
            import re
            after = int(re.search(r'WHERE id>([0-9]+)', args[-1])[1])
            return subprocess.CompletedProcess(args, 0, json.dumps([row for row in self.rows if row['id'] > after][:100]).encode())
        return subprocess.CompletedProcess(args, 0, b'{}')

    def execute(self, action=INSPECT, parameters=None, cancelled=lambda: False):
        with patch('totp_recovery.os.name', 'posix'), patch('totp_recovery.shutil.which', return_value='docker'):
            return self.service.run(action, parameters or {}, self.settings, JOB, 'synthetic-java',
                {'PATH': '/bin', 'DOCKER_HOST': 'tcp://remote', 'JAVA_TOOL_OPTIONS': 'untrusted',
                 'TOTP_ENCRYPTION_KEY': 'unwanted-dev-secret'}, self.output.append, cancelled)

    def test_discovery_and_verification_keep_only_safe_metadata(self):
        self.rows.append({'id': 2, 'encrypted': 'v1.next.iv.encrypted'})
        result = self.execute(); plan = result['recoveryPlan']
        self.assertEqual(['legacy', 'next'], plan['keyIds']); self.assertEqual(2, plan['records'])
        self.assertNotIn(ENCRYPTED, json.dumps(result))
        parameters = {'planId': plan['planId'], 'keys': {'legacy': KEY, 'next': KEY}}
        parameters = self.service.parameters(VERIFY, 'dev', parameters)
        final = self.execute(VERIFY, parameters)
        self.assertEqual(2, final['verified']); self.assertFalse(final['loginTested']); self.assertTrue(final['cleanup'])
        self.assertNotIn(KEY, ''.join(self.output)); self.assertNotIn(ENCRYPTED, ''.join(self.output))
        self.assertNotIn(KEY, json.dumps(self.commands)); self.assertTrue(any(value and KEY.encode() in value for value in self.inputs))
        run = next(args for args in self.commands if 'run' in args)
        for item in ('none', '--cap-drop', 'ALL', '--tmpfs', '--log-driver', '--pids-limit'):
            self.assertIn(item, run)
        for item in ('-p', '--publish', '-v', '--volume'):
            self.assertNotIn(item, run)
        self.assertEqual(2, sum('rm' in args for args in self.commands))
        self.assertTrue((self.run_dir/f'bank_{self.run_dir.name}.dump').is_file())

    def test_wrong_key_failure_is_generic_and_still_cleans_container(self):
        plan = self.execute()['recoveryPlan']; self.java_failure = True
        with self.assertRaises(RecoveryFailure) as caught:
            self.execute(VERIFY, {'planId': plan['planId'], 'keys': {'legacy': KEY}})
        self.assertNotIn(KEY, str(caught.exception)); self.assertIn('rm', self.commands[-1])

    def test_restore_failure_cleanup_and_no_false_success(self):
        self.failed = 'restore'
        with self.assertRaises(RecoveryFailure): self.execute()
        self.assertIn('rm', self.commands[-1]); self.assertNotIn('private backup', ''.join(self.output))
        self.assertFalse(self.service.plans)

    def test_empty_backup_and_bad_ciphertext_are_not_passing_tests(self):
        for rows in ([], [{'id': 1, 'encrypted': 'unknown'}]):
            self.rows = rows
            with self.assertRaises((RecoveryFailure, ValueError)): self.execute()
            self.assertIn('rm', self.commands[-1])

    def test_changed_backup_or_checksum_stops_before_docker(self):
        plan = self.execute()['recoveryPlan']; count = len(self.commands)
        path = self.run_dir/f'bank_{self.run_dir.name}.dump'; path.write_bytes(b'changed')
        Path(str(path)+'.sha256').write_text(hashlib.sha256(b'changed').hexdigest()+'  '+path.name)
        with self.assertRaises(ValueError): self.execute(VERIFY, {'planId': plan['planId'], 'keys': {'legacy': KEY}})
        self.assertEqual(count, len(self.commands))

    def test_checksum_accepts_absolute_paths_with_spaces_without_changing_source(self):
        path = self.root/'Dysk D'/'backup bank.dump'; path.parent.mkdir()
        original = b'PGDMP synthetic backup with spaces'
        path.write_bytes(original)
        expected = hashlib.sha256(original).hexdigest()
        sidecar = Path(str(path)+'.sha256')
        for marker in (' ', '*'):
            with self.subTest(marker=marker):
                # sha256sum/shasum format: digest + space + mode + complete filename.
                metadata = expected.upper()+' '+marker+str(path)+'\n'
                sidecar.write_text(metadata, encoding='utf8')
                with self.service.snapshot(path) as (stream, digest):
                    self.assertEqual(expected, digest)
                    self.assertEqual(original, stream.read())
                self.assertEqual(original, path.read_bytes())
                self.assertEqual(metadata, sidecar.read_text(encoding='utf8'))
        self.assertFalse(self.commands)

    def test_checksum_rejects_malformed_records_before_docker(self):
        path = self.run_dir/f'bank_{self.run_dir.name}.dump'
        digest = hashlib.sha256(path.read_bytes()).hexdigest()
        sidecar = Path(str(path)+'.sha256')
        for metadata in ('', digest, digest+'  \n', 'z'*64+'  bank.dump\n',
                         digest+'  bank.dump\n'+digest+'  second.dump\n',
                         digest+' bank.dump\n'):
            with self.subTest(metadata=metadata):
                sidecar.write_text(metadata, encoding='utf8')
                with self.assertRaisesRegex(ValueError, 'Brak poprawnej sumy'):
                    with self.service.snapshot(path):
                        self.fail('Malformed checksum must not yield a snapshot')
        self.assertFalse(self.commands)

    def test_checksum_mismatch_with_space_path_is_still_rejected(self):
        path = self.run_dir/f'bank_{self.run_dir.name}.dump'
        sidecar = Path(str(path)+'.sha256')
        sidecar.write_text('0'*64+'  /mnt/c/Dysk D/backup bank.dump\n', encoding='utf8')
        with self.assertRaisesRegex(ValueError, 'suma jest niezgodna'):
            self.execute()
        self.assertFalse(self.commands)

    def test_expired_plan_restart_and_key_mismatch_are_refused(self):
        plan = self.execute()['recoveryPlan']; parameters = {'planId': plan['planId'], 'keys': {'other': KEY}}
        with self.assertRaises(ValueError): self.service.parameters(VERIFY, 'dev', parameters)
        self.service.plans[plan['planId']]['expires'] = time.monotonic()-1
        with self.assertRaises(ValueError): self.service.parameters(VERIFY, 'dev', parameters)
        with self.assertRaises(ValueError): TotpRecovery(self.root, None).plan(plan['planId'])

    def test_production_arbitrary_parameters_and_bad_keys_are_rejected(self):
        for action, profile, parameters in ((INSPECT, 'prod', None), (INSPECT, 'test', None),
                (INSPECT, 'dev', {'path': '/private'}), (VERIFY, 'dev', {'planId': 'x', 'keys': {}})):
            with self.assertRaises(ValueError): self.service.parameters(action, profile, parameters)
        for key in ('not base64', '', 'QUJD', KEY+'\n'):
            with self.assertRaises(ValueError): validate_keys({'planId': JOB, 'keys': {'legacy': key}})
        for value in ('v2.id.iv.enc', 'v1.<unsafe>.iv.enc', 'x'*4097):
            with self.assertRaises(ValueError): key_id(value)

    def test_cancellation_and_cleanup_failure_never_report_success(self):
        calls = [False, False, True]
        with self.assertRaises(RecoveryFailure): self.execute(cancelled=lambda: calls.pop(0) if calls else True)
        self.assertIn('rm', self.commands[-1])
        self.cleanup_failure = True
        with self.assertRaises(RecoveryFailure): self.execute()
        self.assertIn('ERROR:', ''.join(self.output))

    def test_stale_jar_and_symlink_are_rejected(self):
        self.jar.write_bytes(b'old artifact')
        with self.assertRaises(ValueError): self.execute()
        self.assertFalse(self.commands)

    def test_panel_never_persists_or_reads_configuration_keys_for_this_test(self):
        from server import Panel, ACTION_MAP
        panel = Panel(self.root)
        with patch('server.threading.Thread.start'):
            task = panel.submit(INSPECT, 'dev', True)
        with patch.object(panel, 'known_secrets', side_effect=AssertionError('must not read DEV secrets')), \
                patch.object(panel.totp_recovery, 'run', return_value={'recoveryPlan': {'planId': JOB, 'keyIds': ['legacy']}}):
            panel.execute(task['id'], ACTION_MAP[INSPECT], 'dev')
        panel.totp_recovery.plans[JOB] = {'keyIds': ['legacy'], 'expires': time.monotonic()+600}
        with patch('server.threading.Thread.start'):
            task = panel.submit(VERIFY, 'dev', True, {'planId': JOB, 'keys': {'legacy': KEY}})
        self.assertNotIn('parameters', task)
        with patch.object(panel, 'known_secrets', side_effect=AssertionError('must not read DEV secrets')), \
                patch.object(panel.totp_recovery, 'run', return_value={'verified': 1, 'loginTested': False}):
            panel.execute(task['id'], ACTION_MAP[VERIFY], 'dev')
        self.assertNotIn(KEY, json.dumps(panel.public_jobs()))
        self.assertNotIn(KEY, (panel.runtime/f"job-{task['id']}.json").read_text())
        self.assertFalse(panel.private_parameters)


if __name__ == '__main__': unittest.main()
