import json
import unittest
from unittest.mock import Mock
from payment_cases import PaymentCases,filters
from maintenance_commands import validate

class PaymentCaseTests(unittest.TestCase):
    def test_profiles_filters_and_cursors_are_closed(self):
        self.assertEqual('CURRENT',filters({'profile':['dev'],'bucket':['CURRENT']})['bucket'])
        with self.assertRaises(ValueError):filters({'profile':['dev'],'bucket':['CURRENT;DROP']})
        for query in ({},{'profile':['test']},{'profile':['prod']},{'profile':['dev'],'view':['PAID;DROP']},
                {'profile':['dev'],'before':['9223372036854775808']},{'profile':['dev'],'q':['x'*129]},
                {'profile':['dev'],'q':['\n']},{'profile':['dev'],'q':['a','b']}):
            with self.assertRaises(ValueError):filters(query)
        with self.assertRaises(ValueError):filters({'profile':['dev'],'q':['x']},True)
    def test_fixed_read_only_queries_encode_search_and_never_return_card_or_auth_secrets(self):
        database=Mock();database._run.return_value='[]';reader=PaymentCases(database)
        needle="' OR TRUE; -- <script>"
        reader.listing(filters({'profile':['dev'],'q':[needle],'view':['REFUNDS']}))
        sql=database._run.call_args.kwargs['sql']
        self.assertNotIn(needle,sql);self.assertIn(needle.encode().hex(),sql)
        for term in ('BEGIN READ ONLY','statement_timeout','LIMIT 26','COMMIT'):self.assertIn(term,sql)
        for term in ('INSERT ','UPDATE ','DELETE ','password_hash','online_number','demo_cvv','totp_secret'):self.assertNotIn(term,sql)
        database._run.return_value=json.dumps({'id':'1'})
        reader.detail('1');sql=database._run.call_args.kwargs['sql']
        self.assertIn('LIMIT 50',sql);self.assertIn('manifest->\'facts\'',sql)
        self.assertNotIn('manifest::text',sql)
    def test_malformed_results_fail_closed_and_paging_keeps_int64_as_text(self):
        db=Mock();reader=PaymentCases(db)
        for value in ('null','{}','not-json'):
            db._run.return_value=value
            with self.assertRaises(RuntimeError):reader.listing(filters({'profile':['dev']}))
        rows=[{'id':str(9223372036854775807-n)} for n in range(26)];db._run.return_value=json.dumps(rows)
        page=reader.listing(filters({'profile':['dev']}));self.assertEqual(25,len(page['cases']));self.assertEqual(rows[24]['id'],page['next'])
        db._run.return_value='null'
        with self.assertRaises(LookupError):reader.detail('1')
        db._base_command.side_effect=RuntimeError('synthetic-private-key')
        with self.assertRaises(RuntimeError) as error:reader.detail('1')
        self.assertNotIn('synthetic-private-key',str(error.exception))
    def test_decisions_are_versioned_idempotent_and_not_financial_overrides(self):
        base={'orderId':'9007199254740993','requestId':'12345678-1234-4234-8234-123456789abc','reason':'Controlled audit decision'}
        decision=base|{'decision':'ALLOW_NEW_PURCHASE','expectedVersion':'0','confirmation':'DECIDE'}
        self.assertEqual(decision,validate('payment-case-decision','dev',decision))
        for profile,change in [('prod',{}),('test',{}),('dev',{'decision':'MARK_FAILED'}),('dev',{'expectedVersion':True}),
                ('dev',{'reason':'short'}),('dev',{'reason':'x\nyyyyyyyyy'}),('dev',{'sql':'UPDATE orders'}),('dev',{'requestId':'bad'})]:
            with self.assertRaises(ValueError):validate('payment-case-decision',profile,decision|change)
        apply=base|{'planDigest':'a'*64,'backupsVerified':True,'writesPaused':True,'confirmation':'RECONCILE'}
        self.assertEqual(apply,validate('payment-case-apply','dev',apply))
        for field in ('writesPaused','backupsVerified'):
            with self.assertRaises(ValueError):validate('payment-case-apply','dev',apply|{field:False})

if __name__=='__main__':unittest.main()
