"""Independent K4 policy and evidence gate. Reads public policy and synthetic test artifacts only."""
import json
from pathlib import Path
import xml.etree.ElementTree as ET

POLICY = Path(__file__).with_name('security-policy.json')
ACCESS = {'public','client','admin','deny','key','callback','webhook','docs','metrics','login','logout'}
REQUIRED = {
    'bank': {
        'AuditPaymentConcurrencyIT#everyRegisteredCookieMutationHasCsrfAndAdminRoutesRejectOrdinaryRoles': 1,
        'AuditPaymentConcurrencyIT#foreignHistoryNotificationsAndSchedulesStayOwnedEvenForAdminSessions': 1,
        'AuditPaymentConcurrencyIT#k4TransferExportsAndProfileUseAuthenticatedOwner': 1,
        'AuditPaymentConcurrencyIT#anotherMerchantCannotReadOrRedeemAuthorization': 1,
        'AuditPaymentConcurrencyIT#k4ForeignMerchantCannotReadCardStatusOrRefundOriginalPayment': 1,
        'AuditPaymentConcurrencyIT#merchantCancellationIsOwnedIdempotentAndOldApprovalCannotCharge': 1,
        'SharedSessionHttpIT#loginAndLogoutAreSharedAndEndpointMatrixIsEnforced': 1,
        'SharedSessionHttpIT#accountBlockedAfterPasswordCannotCompleteTwoFactorInAnotherProcess': 1,
        'SharedSessionHttpIT#challengeFailuresAcrossProcessesShareOneLimitAndValidCodeCompletesOnce': 1,
        'SharedSessionHttpIT#k4AdminBlockingRevokesExistingClientSessionAcrossBothProcesses': 1,
        'ProfileSecurityWebTest#bankUserBasicAuthenticationCannotOpenProfileInsteadOfFormMfa': 1,
        'PaymentApiKeyFilterTest#rotatedKeysKeepTheSameMerchantIdentity': 1,
        'EmailTokenServiceTest#passwordChangesOnlyAfterOneRowWasClaimed': 1,
    },
    'shop': {
        'PurchaseCancellationIT#allRegisteredShopWritesRequireCsrfAndOrderOwnershipForEverySessionRole': 1,
        'PurchaseCancellationIT#k4AllOrderAndCardEntryPointsKeepForeignOrdersUnchanged': 1,
        'PurchaseCancellationIT#k4ProfileAddressCartAndPurchaseIdentityAreBoundToPrincipal': 1,
        'PurchaseCancellationIT#k4BrowserReturnAndWrongCallbackStateCannotInventPayment': 1,
        'PaymentAttemptConcurrencyIT#signedStripeWebhookReplayChangesFinancialStateOnlyOnce': 1,
        'PaymentAttemptConcurrencyIT#invalidSignedStripeWebhookCannotOverwriteDurableAttempt': 6,
        'StripeWebhookVerifierTest#': 1,
    }
}


def policy(path=POLICY):
    value=json.loads(Path(path).read_text(encoding='utf8'))
    if value.get('version')!=1:raise ValueError('Unsupported K4 policy version')
    result={}
    for app in ('bank','shop'):
        entries={}
        for group in value[app]:
            if group.get('access') not in ACCESS or not group.get('owner') or group.get('source') not in ('mvc','framework','filter'):
                raise ValueError('K4 policy requires explicit access, ownership and endpoint source')
            if group['access']=='key' and not group.get('scope'):raise ValueError('API policy needs a scope')
            for endpoint in group['endpoints']:
                verb,path=endpoint.split(' ',1)
                if verb not in ('GET','POST','PUT','PATCH','DELETE') or not path.startswith('/') or endpoint in entries:
                    raise ValueError('Duplicate or invalid K4 endpoint: '+endpoint)
                entries[endpoint]={key:value for key,value in group.items() if key!='endpoints'}
        if not entries:raise ValueError('Empty K4 policy')
        result[app]=entries
    return result


def summarize(root, destination):
    root=Path(root);destination=Path(destination);destination.mkdir(parents=True,exist_ok=True)
    expected=policy(root/'scripts/ci/security-policy.json');applications={}
    for app,relative in (('bank',Path('.')),('shop',Path('shop-app'))):
        target=root/relative/'target'
        boundary=json.loads((target/'security'/f'k4-{app}-boundary.json').read_text(encoding='utf8'))
        if boundary.get('verdict')!='PASS':raise ValueError(app+': boundary did not pass')
        seen={check['endpoint'] for check in boundary['checks']}
        if seen!=set(expected[app]):raise ValueError(app+': incomplete endpoint boundary evidence')
        for endpoint,entry in expected[app].items():
            checks={(item['subject'],item['scenario']) for item in boundary['checks'] if item['endpoint']==endpoint}
            access=entry['access']
            if entry['source']=='filter':required={('ANONYMOUS','filter-missing-csrf'),('ANONYMOUS','filter-invalid-csrf'),('ADMIN','filter-logout' if access=='logout' else 'filter-invalid-credentials')}
            elif access=='key':required={('ANONYMOUS','missing-api-key'),('BAD_KEY','wrong-api-key'),('SCOPED_KEY','correct-api-scope-without-csrf'),('WRONG_SCOPE','wrong-api-scope'),('EXPIRED_KEY','expired-api-key')}
            elif access=='metrics':required={('ANONYMOUS','metrics-no-credentials'),('METRICS_BASIC','metrics-credentials'),('METRICS_BASIC','metrics-wrong-password')}
            else:
                required={(role,'role') for role in ('ANONYMOUS','USER','CUSTOMER','ADMIN','METRICS','UNKNOWN')}
                if access in ('client','admin'):required|={('PRE_MFA','unfinished-login'),('DISABLED','disabled-principal'),('LOCKED','locked-principal')}
                if not endpoint.startswith('GET ') and access!='webhook':
                    role='ADMIN' if access=='admin' else 'USER' if app=='bank' else 'CUSTOMER'
                    required|={(role,'missing-csrf'),(role,'invalid-csrf')}
                if access=='docs':required|={(role,'prod-docs') for role in ('ANONYMOUS','USER','CUSTOMER','ADMIN','METRICS')}
            if required-checks:raise ValueError(app+': incomplete role/CSRF evidence: '+endpoint)
        tests={}
        for folder in ('surefire-reports','failsafe-reports'):
            for report in (target/folder).glob('TEST-*.xml'):
                for case in ET.parse(report).getroot().iter('testcase'):
                    name=case.get('classname','').split('.')[-1]+'#'+case.get('name','')
                    tests[name]=not any(case.find(tag) is not None for tag in ('failure','error','skipped'))
        if not tests or not all(tests.values()):raise ValueError(app+': failed, skipped or missing tests')
        proofs={}
        for prefix,minimum in REQUIRED[app].items():
            count=sum(name.startswith(prefix) for name in tests)
            if count<minimum:raise ValueError(app+': missing business/session evidence: '+prefix)
            proofs[prefix]=count
        applications[app]={'policyOperations':len(expected[app]),'boundaryChecks':len(boundary['checks']),
            'controllerOperations':boundary['registeredControllerOperations'],'testsPassed':len(tests),'requiredProofs':proofs}
        (destination/f'k4-{app}-boundary.json').write_text(json.dumps(boundary,ensure_ascii=False,indent=2)+'\n',encoding='utf8')
    report={'verdict':'PASS','scope':'Bank/shop application policy; K5/K6 excluded',
        'applications':applications,'unclassifiedOperations':0,'skippedRequiredTests':0}
    (destination/'k4-report.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n',encoding='utf8')
    return report


if __name__=='__main__':
    rows=policy()
    print(json.dumps({'policyValid':True,'operations':{app:len(entries) for app,entries in rows.items()}}))
