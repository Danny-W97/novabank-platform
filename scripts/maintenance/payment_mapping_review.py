"""Offline review of an explicit, redacted migration manifest. Never connects to or changes a database.

This is a review gate, not an importer or proof that an external payment did not happen.
Uncertain/historical FAILED records require reconciliation; no guessed merchant or automatic refund.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re


def review(manifest):
    required = {'format', 'bankSnapshotSha256', 'shopSnapshotSha256', 'writesPaused', 'mappings'}
    if not isinstance(manifest, dict) or set(manifest) != required or manifest['format'] != 1:
        raise ValueError('Invalid migration review format.')
    for key in ('bankSnapshotSha256', 'shopSnapshotSha256'):
        if not isinstance(manifest[key], str) or not re.fullmatch('[a-f0-9]{64}', manifest[key]):
            raise ValueError('Both immutable snapshot digests are required.')
    if manifest['writesPaused'] is not True:
        raise ValueError('Review requires an explicit coordinated maintenance window.')
    rows = manifest['mappings']
    if not isinstance(rows, list) or len(rows) > 1000:
        raise ValueError('Review at most 1000 explicit mappings per manifest.')
    fields = {'merchantId', 'orderId', 'attemptKey', 'provider', 'resolution', 'bankReference', 'proofSha256'}
    conflicts, seen = [], set()
    for index, row in enumerate(rows):
        if not isinstance(row, dict) or set(row) != fields:
            raise ValueError('Invalid mapping field set.')
        for key in ('merchantId', 'orderId', 'attemptKey'):
            if not isinstance(row[key], str) or not re.fullmatch('[A-Za-z0-9_-]{1,100}', row[key]):
                raise ValueError('Mapping requires explicit immutable identities, not credentials or account numbers.')
        identity = row['merchantId'], row['orderId']
        if identity in seen:
            conflicts.append({'row': index, 'reason': 'DUPLICATE_PURCHASE_MAPPING'})
        seen.add(identity)
        if row['provider'] not in ('BANK', 'CARD', 'STRIPE'):
            raise ValueError('Explicit provider required.')
        if row['resolution'] not in ('COMPLETED', 'PROVEN_NOT_CHARGED'):
            conflicts.append({'row': index, 'reason': 'UNCONFIRMED_OUTCOME'})
        if not isinstance(row['proofSha256'], str) or not re.fullmatch('[a-f0-9]{64}', row['proofSha256']):
            conflicts.append({'row': index, 'reason': 'MISSING_RECONCILIATION_EVIDENCE'})
        if row['resolution'] == 'COMPLETED' and (not isinstance(row['bankReference'], str)
                or not re.fullmatch('[A-Za-z0-9_-]{1,100}', row['bankReference'])):
            conflicts.append({'row': index, 'reason': 'MISSING_FINANCIAL_REFERENCE'})
        if row['resolution'] == 'PROVEN_NOT_CHARGED' and row['bankReference'] is not None:
            conflicts.append({'row': index, 'reason': 'REFERENCE_CONTRADICTS_NO_CHARGE'})
    canonical = json.dumps(manifest, sort_keys=True, separators=(',', ':')).encode()
    return {'status': 'STOP' if conflicts else 'READY_FOR_OPERATOR_REVIEW', 'rows': len(rows),
            'manifestSha256': hashlib.sha256(canonical).hexdigest(), 'conflicts': conflicts,
            'applied': False,
            'nextAction': 'Operator must verify the supplied evidence against both snapshots and provider, '
                          'restore the backup in isolation and approve a schema-specific backfill. '
                          'This gate neither verifies evidence contents nor assigns data.'}


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('manifest', type=Path)
    args = parser.parse_args()
    try:
        if args.manifest.stat().st_size > 2_000_000:
            raise ValueError('Manifest too large.')
        result = review(json.loads(args.manifest.read_text(encoding='utf8')))
        print(json.dumps(result, indent=2))
        raise SystemExit(2 if result['status'] == 'STOP' else 0)
    except (OSError, ValueError):
        # Do not print malformed input, file contents, personal data or credentials.
        print('MIGRATION_REVIEW_INVALID: no database connection or write was performed.')
        raise SystemExit(1)
