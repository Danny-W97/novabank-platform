import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location('mapping_review', Path(__file__).resolve().parents[1]/'maintenance/payment_mapping_review.py')
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class MappingReviewTest(unittest.TestCase):
    def manifest(self, resolution='COMPLETED'):
        return {'format': 1, 'bankSnapshotSha256': 'a'*64, 'shopSnapshotSha256': 'b'*64, 'writesPaused': True,
                'mappings': [{'merchantId': 'synthetic', 'orderId': 'order', 'attemptKey': 'key', 'provider': 'BANK',
                              'resolution': resolution, 'bankReference': 'reference', 'proofSha256': 'c'*64}]}

    def test_review_is_not_apply_and_historical_failed_is_not_proof(self):
        result = module.review(self.manifest())
        self.assertEqual('READY_FOR_OPERATOR_REVIEW', result['status'])
        self.assertFalse(result['applied'])
        for state in ('UNKNOWN', 'FAILED', 'PENDING'):
            self.assertEqual('STOP', module.review(self.manifest(state))['status'])

    def test_collision_missing_evidence_and_in_flight_writes_stop(self):
        data = self.manifest(); data['mappings'] *= 2
        self.assertEqual('STOP', module.review(data)['status'])
        data = self.manifest(); data['mappings'][0]['proofSha256'] = ''
        self.assertEqual('STOP', module.review(data)['status'])
        data = self.manifest(); data['writesPaused'] = False
        with self.assertRaises(ValueError): module.review(data)
