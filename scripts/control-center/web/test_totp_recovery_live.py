"""Opt-in real Docker/JAR test with a freshly generated synthetic pg_dump, never user env/backups."""
import base64
import json
import os
from pathlib import Path
import secrets
import shutil
import subprocess
import tempfile
import time
import unittest

from operator_settings import ensure_backup_owner
from totp_recovery import TotpRecovery, RecoveryFailure, INSPECT, VERIFY, DOCKER


@unittest.skipUnless(os.environ.get('NOVABANK_TOTP_RECOVERY_TEST') == '1', 'Requires explicit isolated Docker recovery test')
class RecoveryLiveTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.directory = tempfile.TemporaryDirectory(prefix='novabank synthetic recovery-')
        cls.root = Path(cls.directory.name); cls.project = Path(__file__).resolve().parents[3]
        cls.java = shutil.which('java')
        fixture = subprocess.run([cls.java, str(Path(__file__).parent/'fixtures/RecoveryCipherFixture.java')],
                                 capture_output=True, timeout=30, check=True)
        cls.synthetic = json.loads(fixture.stdout)
        backup = ensure_backup_owner(cls.root, '.novabank/backups/dev', create=True)/'20261019T100000Z'; backup.mkdir()
        cls.dump = backup/f'bank_{backup.name}.dump'
        identifier = secrets.token_hex(16); container = 'novabank-synthetic-source-'+identifier
        def docker(*args, **kwargs):
            result = subprocess.run(DOCKER+list(args), capture_output=True, timeout=120, **kwargs)
            if result.returncode: raise RuntimeError('Synthetic fixture Docker step failed; details omitted.')
            return result.stdout
        try:
            docker('run','-d','--rm','--name',container,'--label','novabank.synthetic-source='+identifier,
                   '--network','none','--log-driver','none','--user','postgres','--cap-drop','ALL','--security-opt','no-new-privileges',
                   '--tmpfs','/var/lib/postgresql:rw,nosuid,nodev,noexec,size=268435456,uid=999,gid=999',
                   '-e','PGDATA=/var/lib/postgresql/data','-e','POSTGRES_USER=recovery','-e','POSTGRES_DB=recovery',
                   '-e','POSTGRES_HOST_AUTH_METHOD=trust','postgres:18',
                   'timeout','600','/usr/local/bin/docker-entrypoint.sh','postgres')
            for _ in range(60):
                try: docker('exec',container,'pg_isready','-h','127.0.0.1','-U','recovery','-d','recovery'); break
                except RuntimeError: time.sleep(.5)
            sql = 'CREATE TABLE public.users(id BIGINT PRIMARY KEY,totp_secret_encrypted TEXT);'
            for index, value in enumerate(cls.synthetic['ciphertexts'],1):
                if not all(char.isalnum() or char in '._+/=-' for char in value): raise ValueError()
                sql += f"INSERT INTO public.users VALUES ({index},'{value}');"
            docker('exec','-i',container,'psql','-X','-v','ON_ERROR_STOP=1','-U','recovery','-d','recovery',input=sql.encode())
            cls.dump.write_bytes(docker('exec',container,'pg_dump','-U','recovery','-d','recovery','-Fc','--no-owner','--no-privileges'))
        finally:
            label = subprocess.run(DOCKER+['inspect','--format','{{index .Config.Labels "novabank.synthetic-source"}}',container],capture_output=True,timeout=15)
            if label.returncode == 0 and label.stdout.decode().strip() == identifier:
                subprocess.run(DOCKER+['rm','-f','-v',container],capture_output=True,timeout=30,check=True)
        cls.original = cls.dump.read_bytes()
        for path in (cls.dump, backup/f'shop_{backup.name}.dump'):
            path.write_bytes(cls.original)
            # Match backup.sh: sha256sum receives the full path, including spaces.
            Path(str(path)+'.sha256').write_bytes(subprocess.run(
                ['sha256sum', str(path)], capture_output=True, timeout=15, check=True).stdout)
        target = cls.root/'target'; target.mkdir()
        shutil.copy2(cls.project/'target/Bank-demo-0.0.1-SNAPSHOT.jar',target/'Bank-demo-0.0.1-SNAPSHOT.jar')

    @classmethod
    def tearDownClass(cls): cls.directory.cleanup()

    def setUp(self):
        self.dump.write_bytes(self.original)
        Path(str(self.dump)+'.sha256').write_bytes(subprocess.run(
            ['sha256sum', str(self.dump)], capture_output=True, timeout=15, check=True).stdout)
        self.service = TotpRecovery(self.root,None); self.lines = []; self.ids = []

    def execute(self, action=INSPECT, parameters=None):
        identifier = secrets.token_hex(16); self.ids.append(identifier)
        return self.service.run(action,parameters or {},{'backupDirectory':'.novabank/backups/dev'},identifier,
                                self.java,{'PATH':os.environ['PATH']},self.lines.append)

    def tearDown(self):
        for identifier in self.ids:
            result = subprocess.run(DOCKER+['ps','-a','--filter','label=novabank.totp-recovery='+identifier,'--format','{{.Names}}'],capture_output=True,timeout=15,check=True)
            self.assertEqual(b'',result.stdout, 'A recovery container was not cleaned up')
        for value in self.synthetic['keys'].values(): self.assertNotIn(value,''.join(self.lines))

    def test_real_restore_legacy_keyring_and_packaged_java_verifier(self):
        plan = self.execute()['recoveryPlan']; self.assertEqual(['legacy','next'],plan['keyIds'])
        result = self.execute(VERIFY,{'planId':plan['planId'],'keys':dict(self.synthetic['keys'])})
        self.assertEqual(2,result['verified']); self.assertTrue(result['cleanup']); self.assertFalse(result['loginTested'])
        self.assertEqual(self.original,self.dump.read_bytes())

    def test_real_wrong_key_does_not_pass_and_still_cleans_up(self):
        plan = self.execute()['recoveryPlan']; keys = dict(self.synthetic['keys']); keys['legacy']=base64.b64encode(secrets.token_bytes(32)).decode()
        with self.assertRaises(RecoveryFailure): self.execute(VERIFY,{'planId':plan['planId'],'keys':keys})
        self.assertNotIn(keys['legacy'],''.join(self.lines))

    def test_corrupt_archive_with_matching_checksum_does_not_pass(self):
        self.dump.write_bytes(b'PGDMP corrupt synthetic archive')
        Path(str(self.dump)+'.sha256').write_bytes(subprocess.run(
            ['sha256sum', str(self.dump)], capture_output=True, timeout=15, check=True).stdout)
        with self.assertRaises(RecoveryFailure): self.execute()


if __name__ == '__main__': unittest.main()
