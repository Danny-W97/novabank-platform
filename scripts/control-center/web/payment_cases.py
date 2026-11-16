"""Bounded, token-protected DEV read model. All decisions use the fixed Java maintenance jobs."""
import json
import re
from dev_accounts import read_query, account_id

def filters(query,detail=False):
    allowed={'profile'} if detail else {'profile','q','view','before','bucket'}
    if not isinstance(query,dict) or set(query)-allowed or query.get('profile')!=['dev'] or any(not isinstance(v,list) or len(v)!=1 or not isinstance(v[0],str) for v in query.values()):raise ValueError('Sprawy płatności są wyłącznie dla DEV.')
    term=query.get('q',[''])[0]
    if len(term)>128 or any(ord(c)<32 or ord(c)==127 for c in term):raise ValueError('Nieprawidłowe wyszukiwanie.')
    view=query.get('view',['UNRESOLVED'])[0]
    if view not in ('ALL','UNRESOLVED','ESCALATED','CLOSED_UNRESOLVED','RESOLVED','REFUNDS','CONFLICT'):raise ValueError('Nieprawidłowy filtr spraw.')
    bucket=query.get('bucket',['ALL'])[0]
    if bucket not in ('ALL','CURRENT','REVIEW','HISTORY'):raise ValueError('Nieprawidłowa zakładka zakupów.')
    return {'q':term,'view':view,'bucket':bucket,'before':account_id(query['before'][0]) if 'before' in query else None}

UNRESOLVED="""(EXISTS(SELECT 1 FROM shop_payment_attempts a WHERE a.order_id=o.id AND a.state IN ('CREATED','PROCESSING','AWAITING_AUTHORIZATION','UNKNOWN'))
 OR (o.merchant_id IS NULL AND o.payment_idempotency_key IS NOT NULL AND o.status<>'PAID'))"""
FIELDS="""o.id::text AS id,o.order_number AS "orderNumber",o.customer_id AS "customerId",o.status AS "localStatus",
 o.total::text AS amount,o.currency,COALESCE(o.payment_provider,'UNKNOWN') AS provider,
 COALESCE(c.workflow_status,'OPEN') AS workflow,
 CASE WHEN c.financial_outcome IN ('PAID','REFUNDED','CONFLICT','REFUND_REVIEW') THEN c.financial_outcome
 WHEN o.status='CANCELLED' THEN 'CANCELLED'
 WHEN (c.financial_outcome IS NULL OR c.financial_outcome='UNKNOWN') AND o.purchase_id IS NOT NULL AND o.merchant_id IS NOT NULL AND o.payment_idempotency_key IS NULL AND o.bank_authorization_id IS NULL AND o.external_payment_id IS NULL AND NOT EXISTS(SELECT 1 FROM shop_payment_attempts x WHERE x.order_id=o.id) THEN 'NOT_STARTED'
 ELSE COALESCE(c.financial_outcome,'UNKNOWN') END AS outcome,
 COALESCE(c.version,0)::text AS version,c.checked_at AS "checkedAt",c.allow_new_purchase_at AS "allowNewPurchaseAt",
 o.historical_imported_at AS "importedAt",o.purchase_id::text AS "purchaseId",o.created_at AS "createdAt",
 o.cancel_requested_at AS "cancelRequestedAt",o.cancelled_at AS "cancelledAt"
 ,(o.purchase_id IS NOT NULL AND o.merchant_id IS NOT NULL AND o.historical_imported_at IS NULL AND o.status<>'PAID'
 AND COALESCE(c.financial_outcome,'UNKNOWN') NOT IN ('PAID','REFUNDED','CONFLICT','REFUND_REVIEW')
 AND NOT EXISTS(SELECT 1 FROM shop_refunds r WHERE r.order_id=o.id)
 AND NOT EXISTS(SELECT 1 FROM shop_payment_case_plans p WHERE p.order_id=o.id AND p.status='APPLYING')) AS "cancellationAvailable"
"""
ARCHIVED="(COALESCE(c.workflow_status,'OPEN') IN ('CLOSED_UNRESOLVED','RESOLVED') AND COALESCE(c.financial_outcome,'UNKNOWN') NOT IN ('CONFLICT','REFUND_REVIEW'))"
REVIEW="""(c.financial_outcome IN ('CONFLICT','REFUND_REVIEW') OR (c.financial_outcome IN ('PAID','REFUNDED') AND o.status<>'PAID') OR
 EXISTS(SELECT 1 FROM shop_payment_attempts a WHERE a.order_id=o.id AND (a.state='UNKNOWN' OR a.reconciliation_suspended_at IS NOT NULL)) OR
 (o.merchant_id IS NULL AND o.payment_idempotency_key IS NOT NULL AND o.status<>'PAID') OR
 (o.cancel_requested_at IS NOT NULL AND o.status NOT IN ('PAID','CANCELLED')))"""
HISTORY="(o.status IN ('PAID','CANCELLED','FAILED') AND NOT "+UNRESOLVED+" AND COALESCE(o.cancelled_at,(SELECT max(t.updated_at) FROM shop_payment_attempts t WHERE t.order_id=o.id AND t.state IN ('SUCCEEDED','DECLINED','CANCELLED')),o.created_at)<CURRENT_TIMESTAMP-INTERVAL '7 days')"

class PaymentCases:
    def __init__(self,database):self.database=database
    def read(self,sql):
        try:
            raw=self.database._run(self.database._base_command(),sql=read_query(sql)).strip()
            return json.loads(raw) if raw else None
        except (OSError,RuntimeError,ValueError):raise RuntimeError('Nie można odczytać spraw. Wymagany aktualny sklep ze schematem V17, Docker i lokalne bazy DEV.') from None
    def listing(self,values):
        values=filters({'profile':['dev'],**{key:[value] for key,value in values.items() if value is not None}})
        term=values['q'].encode('utf8').hex();view=values['view'];conditions=['true']
        if values['bucket']=='REVIEW':conditions.extend(['NOT ('+ARCHIVED+')','COALESCE('+REVIEW+',FALSE)'])
        elif values['bucket']=='HISTORY':conditions.append('('+ARCHIVED+') OR (NOT COALESCE('+REVIEW+',FALSE) AND '+HISTORY+')')
        elif values['bucket']=='CURRENT':conditions.extend(['NOT ('+ARCHIVED+')','NOT COALESCE('+REVIEW+',FALSE)','NOT '+HISTORY])
        if view=='UNRESOLVED':conditions.append(UNRESOLVED)
        elif view=='REFUNDS':conditions.append("EXISTS(SELECT 1 FROM shop_refunds r WHERE r.order_id=o.id) OR c.financial_outcome IN ('REFUNDED','REFUND_REVIEW')")
        elif view=='CONFLICT':conditions.append("c.financial_outcome='CONFLICT'")
        elif view!='ALL':conditions.append("c.workflow_status='"+view+"'")
        if values['before']:conditions.append('o.id<'+values['before'])
        rows=self.read(f"""WITH search AS (SELECT lower(convert_from(decode('{term}','hex'),'UTF8')) AS term)
          SELECT COALESCE(json_agg(row_to_json(page)),'[]'::json) FROM (
          SELECT {FIELDS},{UNRESOLVED} AS unresolved FROM shop_orders o LEFT JOIN shop_payment_cases c ON c.order_id=o.id CROSS JOIN search s
          WHERE {' AND '.join('('+part+')' for part in conditions)} AND (s.term='' OR o.id::text=s.term
          OR strpos(lower(o.order_number),s.term)>0 OR strpos(lower(o.customer_id),s.term)>0)
          ORDER BY o.id DESC LIMIT 26) page;""")
        if not isinstance(rows,list) or len(rows)>26 or any(not isinstance(row,dict) or not isinstance(row.get('id'),str) for row in rows):raise RuntimeError('Nieprawidłowy wynik odczytu spraw.')
        return {'cases':rows[:25],'next':rows[24]['id'] if len(rows)>25 else None,'profile':'dev'}
    def detail(self,identifier):
        identifier=account_id(identifier)
        row=self.read(f"""SELECT row_to_json(detail) FROM (SELECT {FIELDS},{UNRESOLVED} AS unresolved,
          COALESCE((SELECT json_agg(row_to_json(a)) FROM (SELECT provider,state,charged_amount::text AS amount,charged_currency AS currency,
          reconciliation_failures AS failures,reconciliation_suspended_at AS "suspendedAt",updated_at AS "updatedAt"
          FROM shop_payment_attempts WHERE order_id=o.id ORDER BY created_at DESC LIMIT 30) a),'[]'::json) AS attempts,
          COALESCE((SELECT json_agg(row_to_json(r)) FROM (SELECT id::text,amount::text,currency,status,bank_reference AS reference
          FROM shop_refunds WHERE order_id=o.id ORDER BY id DESC LIMIT 30) r),'[]'::json) AS refunds,
          COALESCE((SELECT json_agg(row_to_json(e)) FROM (SELECT * FROM (
          SELECT request_id AS "requestId",action,operator_id AS operator,reason,resulting_version::text AS version,created_at AS at FROM shop_payment_case_events WHERE order_id=o.id
          UNION ALL SELECT r.request_id AS "requestId",'PURCHASE_CANCEL_'||e.state AS action,r.actor AS operator,r.reason,NULL::text AS version,e.created_at AS at
          FROM shop_purchase_cancellation_events e JOIN shop_purchase_cancellation_requests r ON r.request_id=e.request_id WHERE r.order_id=o.id
          ) history ORDER BY at DESC LIMIT 50) e),'[]'::json) AS events,
          (SELECT row_to_json(p) FROM (SELECT digest,resolution,status,apply_request_id AS "applyRequestId",apply_reason AS "applyReason",
          expires_at AS "expiresAt",manifest->'facts' AS facts FROM shop_payment_case_plans WHERE order_id=o.id
          ORDER BY created_at DESC LIMIT 1) p) AS plan
          FROM shop_orders o LEFT JOIN shop_payment_cases c ON c.order_id=o.id WHERE o.id={identifier}) detail;""")
        if row is None:raise LookupError('Nie znaleziono zamówienia.')
        if not isinstance(row,dict):raise RuntimeError('Nieprawidłowy wynik odczytu sprawy.')
        return row
