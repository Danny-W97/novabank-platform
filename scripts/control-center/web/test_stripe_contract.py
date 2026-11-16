import unittest
from stripe_contract import contract


class StripeContractTests(unittest.TestCase):
    def fake(self,missing_intent=False):
        calls=[];states={}
        def call(method,path,data=None,operation='read'):
            calls.append((method,path,data))
            if path=='products':return {'id':'prod_fixture','livemode':False}
            if path=='prices':return {'id':'price_fixture','livemode':False}
            if path=='checkout/sessions':
                key='cs_test_'+str(len(states));states[key]={'id':key,'livemode':False,'metadata':{'ccContractRun':data['metadata[ccContractRun]']},'status':'open','payment_status':'unpaid','payment_intent':None,'amount_total':500,'currency':'pln'};return dict(states[key])
            if path.endswith('/expire'):states[path.split('/')[2]]['status']='expired';return {}
            if path.startswith('checkout/sessions/'):
                row=dict(states[path.split('/')[2].split('?')[0]])
                if missing_intent:row.pop('payment_intent')
                return row
            return {'active':False}
        return call,calls
    def test_only_own_test_resources_and_explicit_null_confirm_success(self):
        call,calls=self.fake();result=contract('sk_test_fixture',call)
        self.assertEqual(2,result['sessionsTested']);self.assertFalse(result['chargesCreated'])
        self.assertEqual('NOT_TESTED_REQUIRES_CHECKOUT_PAYMENT',result['paidVsCancelRace'])
        self.assertEqual(2,len([row for row in calls if row[1].endswith('/expire')]))
        self.assertFalse(any('payment_intents' in row[1] or 'charges' in row[1] for row in calls))
    def test_live_keys_rejected_before_network(self):
        call,calls=self.fake()
        with self.assertRaises(ValueError):contract('sk_live_fixture',call)
        self.assertEqual([],calls)
    def test_missing_intent_is_not_null(self):
        call,_=self.fake(True)
        with self.assertRaises(ValueError):contract('sk_test_fixture',call)


if __name__=='__main__':unittest.main()
