"""Explicit TEST-only contract; touches only resources created by this invocation."""
import base64
import json
import re
import uuid
import urllib.error
import urllib.parse
import urllib.request


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self,*args,**kwargs):return None


def contract(key,call=None):
    if not re.fullmatch(r'(?:sk|rk)_test_[A-Za-z0-9_]+',key or ''):raise ValueError('Wymagany testowy klucz Stripe; LIVE jest zabroniony.')
    run=uuid.uuid4().hex
    if call is None:
        opener=urllib.request.build_opener(urllib.request.ProxyHandler({}),NoRedirect())
        def call(method,path,data=None,operation='read'):
            body=None if data is None else urllib.parse.urlencode(data).encode()
            request=urllib.request.Request('https://api.stripe.com/v1/'+path,data=body,method=method,
                headers={'Authorization':'Basic '+base64.b64encode((key+':').encode()).decode(),
                         'Content-Type':'application/x-www-form-urlencoded','Idempotency-Key':'cc-contract-'+run+'-'+operation})
            with opener.open(request,timeout=15) as response:return json.load(response)
    def identifier(row,prefix):
        if row.get('livemode') is not False or not re.fullmatch(prefix+r'[A-Za-z0-9_]+',row.get('id','')):raise ValueError('Niepotwierdzony zasób TEST.')
        return row['id']
    product=price=None;sessions=[];cleanup=[]
    try:
        product=identifier(call('POST','products',{'name':'NovaBank isolated cancellation contract','metadata[ccContractRun]':run},'product'),'prod_')
        price=identifier(call('POST','prices',{'product':product,'unit_amount':'500','currency':'pln','metadata[ccContractRun]':run},'price'),'price_')
        for number in range(2):
            row=call('POST','checkout/sessions',{'mode':'payment','success_url':'https://example.invalid/cc-contract-success',
                'cancel_url':'https://example.invalid/cc-contract-cancel','line_items[0][price]':price,'line_items[0][quantity]':'1',
                'payment_method_types[0]':'card','client_reference_id':'cc-contract-'+run+'-'+str(number),'metadata[ccContractRun]':run},'session-'+str(number))
            session=identifier(row,'cs_test_');sessions.append(session)
            if row.get('status')!='open' or row.get('amount_total')!=500 or row.get('currency')!='pln':raise ValueError('Niezgodna sesja testowa.')
            call('POST','checkout/sessions/'+session+'/expire',{},'expire-'+str(number))
            state=call('GET','checkout/sessions/'+session+'?expand[]=payment_intent')
            if (state.get('id')!=session or state.get('livemode') is not False or state.get('metadata',{}).get('ccContractRun')!=run
                or state.get('status')!='expired' or state.get('payment_status')!='unpaid' or 'payment_intent' not in state or state['payment_intent'] is not None):
                raise ValueError('Nie potwierdzono końca bez obciążenia.')
        return {'verdict':'OPEN_SESSION_EXPIRY_AND_NEW_SESSION_PASS','sessionsTested':2,'chargesCreated':False,
                'paidVsCancelRace':'NOT_TESTED_REQUIRES_CHECKOUT_PAYMENT','shopDatabaseChanged':False}
    finally:
        # Never enumerate or change pre-existing resources. Unknown outcomes are not called PASS.
        for index,session in enumerate(sessions):
            try:
                state=call('GET','checkout/sessions/'+session)
                if state.get('status')=='open':call('POST','checkout/sessions/'+session+'/expire',{},'cleanup-'+str(index))
                cleanup.append(state.get('status') in ('open','expired'))
            except Exception:cleanup.append(False)
        for prefix,resource in (('prices',price),('products',product)):
            if resource:
                try:cleanup.append(call('POST',prefix+'/'+resource,{'active':'false'},'archive-'+prefix).get('active') is False)
                except Exception:cleanup.append(False)
        if not all(cleanup):raise RuntimeError('Sprzątanie własnych zasobów TEST niepotwierdzone. Sprawdź Stripe; nie powtarzaj testu bez kontroli.')
