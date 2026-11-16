import tempfile
import unittest
from pathlib import Path
from notifications import Notifications
from notification_retention import NotificationRetention


class RetentionTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        root = Path(self.temp.name)
        self.notifications = Notifications(root, root / 'runtime', lambda text: text.replace('private', '***'))
        self.retention = NotificationRetention(self.notifications)

    def old(self, state='deleted', **kwargs):
        ident = self.notifications.add('Bank', 'WARN', 'private fixture', fingerprint=str(kwargs)+state, **kwargs)
        self.notifications.change('archive' if state == 'archived' else 'delete', ident, True)
        with self.notifications.connect() as db:
            db.execute("UPDATE notifications SET state_changed_at='2000-01-01T00:00:00+00:00' WHERE id=?", (ident,))
        return ident

    def test_export_required_and_active_unresolved_prod_and_repairs_preserved(self):
        candidate = self.old()
        prod = self.old(profile='prod')
        unresolved = self.old(issue_code='SCHEDULE_CONFIGURATION_INVALID', profile='dev')
        active = self.notifications.add('Bank', 'ERROR', 'active')
        repair = self.old(state='archived')
        self.notifications.reserve_repair('fixture', repair, 'revision', 'job')
        plan = self.retention.plan('dev')
        self.assertEqual(1, plan['count'])
        with self.assertRaises(ValueError): self.retention.purge(plan['planId'], 'dev', True, 'USUN')
        export = self.retention.export(plan['planId'], 'dev')
        self.assertNotIn('private', export['content'])
        self.assertEqual(1, self.retention.purge(plan['planId'], 'dev', True, 'USUN')['removed'])
        with self.assertRaises(LookupError): self.notifications.detail(candidate)
        for ident in (prod, unresolved, active, repair): self.notifications.detail(ident)

    def test_restore_after_export_invalidates_entire_plan_and_fingerprint_does_not_resurrect(self):
        ident = self.old()
        plan = self.retention.plan('dev')
        self.retention.export(plan['planId'], 'dev')
        self.notifications.change('restore', ident)
        with self.assertRaisesRegex(ValueError, 'zmieniły'): self.retention.purge(plan['planId'], 'dev', True, 'USUN')
        self.assertEqual('active', self.notifications.detail(ident)['state'])
        self.notifications.change('delete', ident, True)
        with self.notifications.connect() as db:
            db.execute("UPDATE notifications SET state_changed_at='2000-01-01' WHERE id=?", (ident,))
        plan = self.retention.plan('dev'); self.retention.export(plan['planId'], 'dev')
        self.retention.purge(plan['planId'], 'dev', True, 'USUN')
        self.assertIsNone(self.notifications.add('Bank', 'WARN', 'private fixture', fingerprint='{}deleted'))
        newer = self.notifications.add('Bank', 'WARN', 'new event')
        self.assertGreater(newer, ident)

    def test_strict_profile_parameters_and_confirmation(self):
        self.old(profile='test')
        self.assertEqual(0, self.retention.plan('dev')['count'])
        self.assertEqual(1, self.retention.plan('test')['count'])
        for profile in ('prod', '', None):
            with self.assertRaises(ValueError): self.retention.plan(profile)
        for value in (True, 0, -1, 3651, '30'):
            with self.assertRaises(ValueError): self.retention.plan('dev', value)
        with self.assertRaises(ValueError): self.retention.action({'operation': 'plan', 'profile': 'dev', 'sql': 'x'})
        plan = self.retention.plan('dev')
        with self.assertRaises(ValueError): self.retention.export(plan['planId'], 'test')
        with self.assertRaises(ValueError): self.retention.purge(plan['planId'], 'dev', 'true', 'USUN')
