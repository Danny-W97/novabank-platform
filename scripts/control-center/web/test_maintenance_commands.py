import os
from contextlib import contextmanager
from pathlib import Path
import tempfile
import unittest
from unittest.mock import Mock, patch
from maintenance_commands import validate, command, environment


class MaintenanceTests(unittest.TestCase):
    def test_purchase_commands_require_dev_exact_scope_and_explicit_confirmation(self):
        valid={'orderId':'9007199254740993','requestId':'12345678-1234-4234-8234-123456789abc','reason':'Synthetic reviewed cancellation','confirmation':'CANCEL'}
        self.assertEqual(valid,validate('purchase-cancel','dev',valid))
        self.assertEqual('REFRESH',validate('purchase-refresh','dev',valid|{'confirmation':'REFRESH'})['confirmation'])
        for profile,params in [('prod',valid),('test',valid),('dev',valid|{'confirmation':'CHECK'}),('dev',valid|{'orderId':'1;DROP'}),('dev',valid|{'customerId':'other'}),('dev',valid|{'reason':'short'}),('dev',valid|{'requestId':'bad'})]:
            with self.assertRaises((ValueError,TypeError)):validate('purchase-cancel',profile,params)

    @unittest.skipIf(os.name=='nt','WSL/Linux launcher')
    def test_purchase_job_launches_shop_guard_not_bank_history_writer(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);(root/'shop-app/target').mkdir(parents=True);(root/'shop-app/target/shop-app-0.0.1-SNAPSHOT.jar').write_bytes(b'synthetic')
            args=command(root,'/synthetic/java','purchase-cancel','dev',{'orderId':'1','requestId':'12345678-1234-4234-8234-123456789abc','reason':'Synthetic cancellation','confirmation':'CANCEL'})
            self.assertIn('-Dloader.main=org.demo.shopapp.maintenance.PurchaseCancellationCommand',args)
            self.assertIn('--operation=purchase-cancel',args)

    def test_migration_environment_checks_checkout_ports_and_both_backup_hashes(self):
        panel=Mock();panel.root=Path('/synthetic-checkout')
        panel.dev_configuration.lock=__import__('threading').RLock()
        panel.dev_configuration.snapshot.return_value={
            'project':b'POSTGRES_HOST=localhost\nPOSTGRES_PORT=5300\nSHOP_POSTGRES_HOST=localhost\nSHOP_POSTGRES_PORT=5400\n',
            'dev':b'POSTGRES_PASSWORD=synthetic\nSHOP_POSTGRES_PASSWORD=synthetic\nSHOP_PAYMENT_API_KEY=synthetic-test-key\nTOTP_ENCRYPTION_KEY=not-for-migration\n'}
        panel.totp_recovery.backup.return_value=Path('/synthetic-backups/bank_pair.dump')
        panel.operation_settings.state.return_value={'values':{'backupDirectory':'/synthetic-backups'}}
        seen=[]
        @contextmanager
        def snapshot(path):
            seen.append(path.name)
            yield Path('/synthetic-copy'),('a' if path.name.startswith('bank_') else 'b')*64
        panel.totp_recovery.snapshot.side_effect=snapshot
        with patch('dev_cash.DevCash') as bank,patch('dev_shop_accounts.DevShopDatabase') as shop:
            bank.return_value._run.return_value='{"5432/tcp":[{"HostIp":"127.0.0.1","HostPort":"5300"}]}'
            shop.return_value._run.return_value='{"5432/tcp":[{"HostIp":"127.0.0.1","HostPort":"5400"}]}'
            result=environment(panel,'dev','payment-history-migration-plan')
            self.assertEqual('a'*64,result['MIGRATION_BANK_BACKUP_SHA256'])
            self.assertEqual('b'*64,result['MIGRATION_SHOP_BACKUP_SHA256'])
            self.assertEqual(['bank_pair.dump','shop_pair.dump'],seen)
            self.assertNotIn('TOTP_ENCRYPTION_KEY',result)
            bank.return_value._base_command.assert_called_once()
            shop.return_value._base_command.assert_called_once()
            for raw in ('{"5432/tcp":[{"HostIp":"0.0.0.0","HostPort":"5400"}]}',
                        '{"5432/tcp":[{"HostIp":"127.0.0.1","HostPort":"9999"}]}'):
                shop.return_value._run.return_value=raw
                with self.assertRaises(ValueError):environment(panel,'dev','payment-history-migration-apply')
            shop.return_value._base_command.side_effect=RuntimeError('wrong checkout')
            with self.assertRaises(RuntimeError):environment(panel,'dev','payment-history-migration-apply')

    def test_history_import_requires_exact_plan_and_operator_attestations(self):
        self.assertEqual({},validate('payment-history-migration-plan','dev',None))
        valid={'planDigest':'a'*64,'confirmation':'MIGRATE','backupsVerified':True,'writesPaused':True}
        self.assertEqual(valid,validate('payment-history-migration-apply','dev',valid))
        for profile,params in [('test',valid),('prod',valid),('dev',valid|{'confirmation':'ROTATE'}),
                ('dev',valid|{'writesPaused':False}),('dev',valid|{'backupsVerified':False}),
                ('dev',valid|{'planDigest':'a;whoami'}),('dev',valid|{'sql':'UPDATE shop_orders'})]:
            with self.subTest(profile=profile,fields=list(params)):
                with self.assertRaises(ValueError):validate('payment-history-migration-apply',profile,params)
        with self.assertRaises(ValueError):validate('payment-history-migration-plan','dev',{'merchantId':'guess'})

    def test_purchase_environment_is_local_checked_and_does_not_claim_backup_attestation(self):
        panel=Mock();panel.root=Path('/synthetic-checkout');panel.dev_configuration.lock=__import__('threading').RLock()
        panel.dev_configuration.snapshot.return_value={'project':b'POSTGRES_HOST=localhost\nPOSTGRES_PORT=5300\nSHOP_POSTGRES_HOST=localhost\nSHOP_POSTGRES_PORT=5400\n',
            'dev':b'POSTGRES_PASSWORD=synthetic\nSHOP_POSTGRES_PASSWORD=synthetic\nBANK_API_KEY=synthetic-bank-key\nBANK_API_URL=http://localhost:8080\nSHOP_PAYMENT_PROVIDER=bank-demo\nTOTP_ENCRYPTION_KEY=not-for-cancellation\n'}
        with patch('dev_cash.DevCash') as bank,patch('dev_shop_accounts.DevShopDatabase') as shop:
            bank.return_value._run.return_value='{"5432/tcp":[{"HostIp":"127.0.0.1","HostPort":"5300"}]}'
            shop.return_value._run.return_value='{"5432/tcp":[{"HostIp":"127.0.0.1","HostPort":"5400"}]}'
            result=environment(panel,'dev','purchase-cancel');self.assertEqual('synthetic-bank-key',result['BANK_API_KEY']);self.assertNotIn('TOTP_ENCRYPTION_KEY',result);self.assertNotIn('MIGRATION_BANK_BACKUP_SHA256',result)
            panel.totp_recovery.backup.assert_not_called()
            panel.dev_configuration.snapshot.return_value['dev']=b'BANK_API_URL=https://remote.example\n'
            with self.assertRaises(ValueError):environment(panel,'dev','purchase-cancel')

    def test_payment_inventory_has_no_write_or_arbitrary_query_parameters(self):
        self.assertEqual({}, validate('payment-history-review', 'dev', None))
        for profile, parameters in (('prod', {}), ('dev', {'sql': 'UPDATE users'}), ('dev', {'merchantId': 'guess'})):
            with self.assertRaises(ValueError): validate('payment-history-review', profile, parameters)

    def test_closed_parameter_set_profile_range_and_confirmation(self):
        valid = {'afterId': '0', 'limit': 25}
        self.assertEqual(valid, validate('totp-rotation-plan', 'dev', valid))
        for changed in ({'limit': True}, {'limit': 101}, {'afterId': '1;whoami'}, {'afterId': True},
                        {'afterId': '9223372036854775808'}, {'command': 'bash'}):
            with self.assertRaises(ValueError): validate('totp-rotation-plan', 'dev', valid | changed)
        with self.assertRaises(ValueError): validate('totp-rotation-plan', 'prod', valid)
        with self.assertRaises(ValueError): validate('totp-rotation-apply', 'dev', valid)
        confirmed = valid | {'planDigest': 'a'*64, 'confirmation': 'ROTATE'}
        self.assertEqual(confirmed, validate('totp-rotation-apply', 'test', confirmed))

    @unittest.skipIf(os.name == 'nt', 'Maintenance launcher is WSL/Linux only')
    def test_fixed_java_launcher_without_env_or_arbitrary_options_in_argv(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory); (root/'target').mkdir()
            (root/'target/Bank-demo-0.0.1-SNAPSHOT.jar').write_bytes(b'fixture')
            args = command(root, '/fixture/java', 'totp-rotation-plan', 'test', {'afterId': '0', 'limit': 10})
            self.assertIn('org.springframework.boot.loader.launch.PropertiesLauncher', args)
            self.assertIn('--operation=totp-plan', args)
            self.assertFalse(any('password' in value.lower() for value in args))
            with self.assertRaises(ValueError): command(root, 'java', 'shell', 'dev', {})

    def test_environment_has_only_requested_profile_database_and_totp_settings(self):
        panel = Mock()
        panel.dev_configuration.lock = __import__('threading').RLock()
        panel.dev_configuration.snapshot.return_value = {
            'project': b'POSTGRES_HOST=localhost\nPOSTGRES_PORT=5300\nPOSTGRES_DB=fixture\nPOSTGRES_USER=fixture\n',
            'dev': b'POSTGRES_PASSWORD=private-fixture\nTOTP_ENCRYPTION_KEY=private-totp\nBANK_API_KEY=not-needed\n'}
        values = environment(panel, 'dev')
        self.assertEqual('private-fixture', values['POSTGRES_PASSWORD'])
        self.assertNotIn('BANK_API_KEY', values)
        panel.configuration_files.test_environment.return_value = {'TEST_POSTGRES_URL': 'fixture', 'TEST_BANK_API_KEY': 'not-needed'}
        self.assertEqual({'TEST_POSTGRES_URL': 'fixture'}, environment(panel, 'test'))
