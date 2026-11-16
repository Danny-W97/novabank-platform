"""Opt-in real SQL against a newly owned, unexposed container; never Compose/DEV."""
import json
import os
from pathlib import Path
import re
import subprocess
import unittest
import uuid
from payment_cases import PaymentCases,filters
from postgres_test_support import wait_for_postgres

DOCKER=['docker','--host','unix:///var/run/docker.sock']

@unittest.skipUnless(os.environ.get('NOVABANK_PAYMENT_CASES_SQL_TEST')=='1','Explicit disposable Docker SQL fixture required')
class PaymentCaseSqlTests(unittest.TestCase):
    @classmethod
    def sql(cls,text):
        result=subprocess.run(DOCKER+['exec','-i',cls.container,'psql','-h','127.0.0.1','-X','-qAt','-U','postgres','-v','ON_ERROR_STOP=1'],input=text,text=True,capture_output=True,timeout=30)
        if result.returncode:raise AssertionError('Owned payment case SQL fixture failed: '+result.stderr)
        return result.stdout.strip()
    @classmethod
    def setUpClass(cls):
        cls.container='payment-cases-reader-it-'+uuid.uuid4().hex
        cls.addClassCleanup(cls.cleanup)
        subprocess.run(DOCKER+['run','-d','--name',cls.container,'--network','none','--label','novabank.control.test=payment-cases-reader',
            '-e','POSTGRES_HOST_AUTH_METHOD=trust','postgres:18'],check=True,capture_output=True,timeout=30)
        wait_for_postgres(DOCKER, cls.container)
        root=Path(__file__).resolve().parents[3]
        for migration in sorted((root/'shop-app/src/main/resources/db/migration').glob('V*__*.sql'),key=lambda p:int(re.match(r'V(\d+)',p.name)[1])):
            cls.sql(migration.read_text(encoding='utf8'))
        cls.sql("""INSERT INTO shop_orders(id,order_number,customer_id,merchant_account_number,total,currency,status,payment_idempotency_key)
            SELECT 9007199254740993+i,'synthetic-order-'||i,'synthetic-customer','22222222222222222222222222',10,'PLN','FAILED','synthetic-key-'||i FROM generate_series(1,27) i;
            INSERT INTO shop_payment_cases(order_id,workflow_status,allow_new_purchase_at) VALUES (9007199254740994,'CLOSED_UNRESOLVED',now());
            INSERT INTO shop_payment_case_plans(digest,order_id,resolution,manifest) VALUES (repeat('a',64),9007199254740994,'UNKNOWN','{"facts":[{"source":"synthetic"}]}');
            INSERT INTO shop_payment_case_events(request_id,order_id,action,operator_id,reason,expected_version,resulting_version)
            VALUES ('12345678-1234-4234-8234-123456789abc',9007199254740994,'CLOSE_UNRESOLVED','local-cc-dev','Synthetic reason',0,1);""")
    @classmethod
    def cleanup(cls):
        result=subprocess.run(DOCKER+['inspect',cls.container,'--format','{{index .Config.Labels "novabank.control.test"}}'],capture_output=True,text=True,timeout=10)
        if result.returncode==0 and result.stdout.strip()=='payment-cases-reader' and cls.container.startswith('payment-cases-reader-it-'):
            subprocess.run(DOCKER+['rm','-f','-v',cls.container],check=True,capture_output=True,timeout=20)
    def reader(self):
        owner=self
        class Database:
            def _base_command(self):return []
            def _run(self,args,*,sql):return owner.sql(sql)
        return PaymentCases(Database())
    def test_pages_search_and_workflow_filters_keep_identity_and_do_not_resolve_money(self):
        reader=self.reader();page=reader.listing(filters({'profile':['dev']}));self.assertEqual(25,len(page['cases']))
        following=reader.listing(filters({'profile':['dev'],'before':[page['next']]}));self.assertEqual(2,len(following['cases']))
        self.assertEqual(27,len({r['id'] for r in page['cases']+following['cases']}))
        closed=reader.listing(filters({'profile':['dev'],'view':['CLOSED_UNRESOLVED']}));self.assertEqual(1,len(closed['cases']))
        self.assertEqual('UNKNOWN',closed['cases'][0]['outcome']);self.assertTrue(closed['cases'][0]['unresolved'])
        injected=reader.listing(filters({'profile':['dev'],'q':["' OR TRUE; --"]}));self.assertEqual([],injected['cases'])
    def test_detail_returns_only_public_plan_facts_and_bounded_audit(self):
        data=self.reader().detail('9007199254740994')
        self.assertEqual('CLOSED_UNRESOLVED',data['workflow']);self.assertEqual('UNKNOWN',data['outcome'])
        self.assertEqual([{'source':'synthetic'}],data['plan']['facts']);self.assertEqual(1,len(data['events']))
        self.assertNotIn('manifest',data['plan']);self.assertEqual([],data['attempts'])

    def test_closed_unknown_is_archived_immediately_without_changing_its_outcome(self):
        reader=self.reader();query={'profile':['dev'],'q':['9007199254740994'],'view':['ALL']}
        for bucket in ('CURRENT','REVIEW'):
            self.assertEqual([],reader.listing(filters(query|{'bucket':[bucket]}))['cases'])
        archived=reader.listing(filters(query|{'bucket':['HISTORY']}))['cases']
        self.assertEqual(1,len(archived));self.assertEqual('UNKNOWN',archived[0]['outcome'])
        self.assertEqual('CLOSED_UNRESOLVED',archived[0]['workflow'])

    def test_z_buckets_do_not_hide_old_unknowns_and_include_cancellation_audit(self):
        self.sql("""INSERT INTO shop_orders(id,order_number,customer_id,merchant_account_number,total,currency,status,merchant_id,purchase_id,created_at,cancelled_at)
          VALUES (700001,'synthetic-current','synthetic-buckets','22222222222222222222222222',10,'PLN','PENDING','synthetic',gen_random_uuid(),now(),NULL),
          (700002,'synthetic-closed','synthetic-buckets','22222222222222222222222222',10,'PLN','CANCELLED','synthetic',gen_random_uuid(),now()-interval '9 days',now()-interval '8 days'),
          (700003,'synthetic-unknown','synthetic-buckets','22222222222222222222222222',10,'PLN','UNKNOWN','synthetic',gen_random_uuid(),now()-interval '10 days',NULL),
          (700004,'synthetic-conflict','synthetic-buckets','22222222222222222222222222',10,'PLN','PENDING','synthetic',gen_random_uuid(),now(),NULL);
          INSERT INTO shop_payment_cases(order_id,financial_outcome) VALUES (700004,'CONFLICT');
          INSERT INTO shop_payment_cases(order_id,financial_outcome,workflow_status) VALUES (700002,'CANCELLED','RESOLVED');
          INSERT INTO shop_payment_attempts(id,order_id,merchant_id,provider,idempotency_key,state,correlation_state,charged_amount,charged_currency)
          VALUES ('synthetic-unknown-attempt',700003,'synthetic','CARD','synthetic-unknown-key','UNKNOWN','synthetic',10,'PLN');
          INSERT INTO shop_purchase_cancellation_requests(request_id,order_id,actor,reason,state) VALUES ('22345678-1234-4234-8234-123456789abc',700002,'LOCAL_CC_DEV','Synthetic reviewed cancellation','CANCELLED');
          INSERT INTO shop_purchase_cancellation_events(request_id,state) VALUES ('22345678-1234-4234-8234-123456789abc','CANCELLED');""")
        try:
            reader=self.reader()
            for bucket,expected in [('CURRENT',['700001']),('HISTORY',['700002']),('REVIEW',['700004','700003'])]:
                page=reader.listing(filters({'profile':['dev'],'bucket':[bucket],'view':['ALL'],'q':['synthetic-buckets']}))
                self.assertEqual(expected,[r['id'] for r in page['cases']])
            data=reader.detail('700002');self.assertEqual('PURCHASE_CANCEL_CANCELLED',data['events'][0]['action'])
            self.assertEqual('CANCELLED',data['outcome']);self.assertEqual('NOT_STARTED',reader.detail('700001')['outcome'])
            self.assertEqual('CONFLICT',reader.detail('700004')['outcome'])
            self.assertTrue(reader.detail('700001')['cancellationAvailable']);self.assertFalse(reader.detail('700004')['cancellationAvailable'])
        finally:
            self.sql("DELETE FROM shop_purchase_cancellation_events WHERE request_id='22345678-1234-4234-8234-123456789abc'; DELETE FROM shop_purchase_cancellation_requests WHERE order_id IN (700001,700002,700003); DELETE FROM shop_payment_attempts WHERE order_id=700003; DELETE FROM shop_payment_cases WHERE order_id IN (700002,700004); DELETE FROM shop_orders WHERE id IN (700001,700002,700003,700004);")

if __name__=='__main__':unittest.main()
