import json
from pathlib import Path
import tempfile
import unittest
from k4_policy import policy, summarize


class K4PolicyTests(unittest.TestCase):
    def test_all_policy_operations_are_explicit_and_scoped(self):
        entries=policy()
        self.assertGreater(len(entries['bank']),70)
        self.assertGreater(len(entries['shop']),35)
        self.assertEqual('client',entries['shop']['GET /api/auth/me']['access'])
        self.assertEqual('order-owner',entries['shop']['POST /api/orders/{orderId}/card-pay']['owner'])
        self.assertEqual('payments:authorize',entries['bank']['POST /api/payment-authorizations/by-key/{key}/cancel']['scope'])

    def test_duplicate_and_unclassified_policy_fails_closed(self):
        original=json.loads(Path(__file__).with_name('security-policy.json').read_text())
        with tempfile.TemporaryDirectory() as directory:
            path=Path(directory)/'policy.json'
            broken=json.loads(json.dumps(original));broken['bank'].append(broken['bank'][0]);path.write_text(json.dumps(broken))
            with self.assertRaisesRegex(ValueError,'Duplicate'):policy(path)
            broken=json.loads(json.dumps(original));broken['bank'][0]['access']='authenticated-maybe';path.write_text(json.dumps(broken))
            with self.assertRaises(ValueError):policy(path)

    def test_missing_report_or_partial_role_evidence_cannot_be_a_pass(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);(root/'scripts/ci').mkdir(parents=True)
            (root/'scripts/ci/security-policy.json').write_bytes(Path(__file__).with_name('security-policy.json').read_bytes())
            with self.assertRaises(OSError):summarize(root,root/'result')
            target=root/'target/security';target.mkdir(parents=True)
            fake={'verdict':'PASS','checks':[{'endpoint':name,'subject':'ANONYMOUS','scenario':'role','result':'DENY'} for name in policy()['bank']]}
            (target/'k4-bank-boundary.json').write_text(json.dumps(fake))
            with self.assertRaisesRegex(ValueError,'incomplete role/CSRF'):summarize(root,root/'result')


if __name__=='__main__':unittest.main()
