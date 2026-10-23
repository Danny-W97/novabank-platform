"""Closed opt-in load/transport checks on a secret-free export, never running DEV JARs."""
import argparse
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import json
import uuid
from source_bundle import ROOT,export
from k4_policy import summarize


def require_local_docker(environment):
    endpoint=environment.get('DOCKER_HOST','')
    if endpoint and not endpoint.startswith('unix://'):raise ValueError('Testy wymagają lokalnego Docker przez unix socket.')
    selected=subprocess.check_output(['docker','context','inspect','--format','{{.Endpoints.docker.Host}}'],env=environment,stderr=subprocess.PIPE,timeout=10).decode().strip()
    if not selected.startswith('unix://'):raise ValueError('Zdalny kontekst Docker nie jest dozwolony w tych testach.')


if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('check',choices=('load','transport','security','payment-bank','payment-shop','totp','k4'));args=parser.parse_args()
    output=ROOT/'target/audit-extended'/args.check;output.mkdir(parents=True,exist_ok=True)
    if args.check=='k4':output=output/('run-'+uuid.uuid4().hex);output.mkdir()
    prefixes=('SPRING_','BANK_','SHOP_','TEST_','TOTP_','POSTGRES_','REDIS_','STRIPE_','VAULT_','MANAGEMENT_','SERVER_')
    env={k:v for k,v in os.environ.items() if not k.startswith(prefixes) and k not in ('JAVA_TOOL_OPTIONS','JDK_JAVA_OPTIONS','_JAVA_OPTIONS','MAVEN_ARGS','MAVEN_OPTS','BASH_ENV','ENV')}
    require_local_docker(env)
    with tempfile.TemporaryDirectory(prefix='novabank-audit-extended-') as directory:
        root=Path(directory)/'source';export(root)
        shutil.copy2(root/'source-manifest.json',output/'source-manifest.json')
        commands=[['-DskipTests','package']]
        if args.check=='load':commands.append(['-Pintegration-tests','-Dit.test=ConcurrentTransferLoadIT','-Dnovabank.load.enabled=true','verify'])
        elif args.check=='transport':commands.append(['-f','shop-app/pom.xml','-Pintegration-tests','-Dit.test=RealBankTransportFaultIT','-Dnovabank.real-payment-faults=true','verify'])
        elif args.check=='security':
            commands.extend([['-Pintegration-tests','-Dit.test=SharedSessionHttpIT,AuditPaymentConcurrencyIT','verify'],['-f','shop-app/pom.xml','-Pintegration-tests','-Dit.test=PurchaseCancellationIT','verify']])
        elif args.check=='k4':
            commands.extend([
                ['-Punit-tests','-Dtest=ProfileSecurityWebTest,MetricsBasicAuthenticationFilterTest,PaymentApiKeyFilterTest,TwoFactorLoginServiceTest,EmailTokenServiceTest','test'],
                ['-Pintegration-tests','-Dit.test=K4SecurityPolicyIT,AuditPaymentConcurrencyIT#everyRegisteredCookieMutationHasCsrfAndAdminRoutesRejectOrdinaryRoles+foreignHistoryNotificationsAndSchedulesStayOwnedEvenForAdminSessions+k4TransferExportsAndProfileUseAuthenticatedOwner+anotherMerchantCannotReadOrRedeemAuthorization+k4ForeignMerchantCannotReadCardStatusOrRefundOriginalPayment+merchantCancellationIsOwnedIdempotentAndOldApprovalCannotCharge,SharedSessionHttpIT#loginAndLogoutAreSharedAndEndpointMatrixIsEnforced+accountBlockedAfterPasswordCannotCompleteTwoFactorInAnotherProcess+challengeFailuresAcrossProcessesShareOneLimitAndValidCodeCompletesOnce+k4AdminBlockingRevokesExistingClientSessionAcrossBothProcesses','verify'],
                ['-f','shop-app/pom.xml','-Punit-tests','-Dtest=MetricsBasicAuthenticationFilterTest,StripeWebhookVerifierTest','test'],
                ['-f','shop-app/pom.xml','-Pintegration-tests','-Dit.test=K4SecurityPolicyIT,PurchaseCancellationIT#allRegisteredShopWritesRequireCsrfAndOrderOwnershipForEverySessionRole+k4AllOrderAndCardEntryPointsKeepForeignOrdersUnchanged+k4ProfileAddressCartAndPurchaseIdentityAreBoundToPrincipal+k4BrowserReturnAndWrongCallbackStateCannotInventPayment,PaymentAttemptConcurrencyIT','verify']])
        elif args.check=='payment-bank':commands.append(['-Pintegration-tests','-Dit.test=AuditPaymentConcurrencyIT,HistoricalPaymentMigrationIT','verify'])
        elif args.check=='payment-shop':commands.append(['-f','shop-app/pom.xml','-Pintegration-tests','-Dit.test=PaymentAttemptConcurrencyIT,PurchaseCancellationIT','verify'])
        else:commands.append(['-Pintegration-tests','-Dit.test=SharedSessionHttpIT#restoredBackupRotatesKeysAndAuthenticatesWithOriginalTotpSeed','verify'])
        for index,options in enumerate(commands):
            with (output/f'phase-{index}.log').open('w') as log:
                result=subprocess.run(['bash','./mvnw','-B','-ntp',*options],cwd=root,env=env,stdout=log,stderr=subprocess.STDOUT,timeout=1100)
            for relative in ('target/cc-transfer-load-report.json','target/bank-security-matrix.json','shop-app/target/shop-security-matrix.json','target/failsafe-reports','shop-app/target/failsafe-reports'):
                source=root/relative
                if source.is_dir():shutil.copytree(source,output/(('shop-' if relative.startswith('shop') else 'bank-')+source.name),dirs_exist_ok=True)
                elif source.is_file():shutil.copy2(source,output/source.name)
            print(f'{args.check}: phase {index}, exit {result.returncode}; {output}',flush=True)
            if result.returncode:
                if args.check=='k4':(output/'k4-report.json').write_text(json.dumps({'verdict':'FAIL','phase':index,'reason':'A required test phase failed. See phase log.'})+'\n',encoding='utf8')
                raise SystemExit(result.returncode)
        if args.check=='k4':
            try:
                report=summarize(root,output)
                print(json.dumps(report,ensure_ascii=False,indent=2),flush=True)
                print('K4: wszystkie operacje sklasyfikowane; wymagane testy dostępu, właściciela, CSRF i integracji zaliczone. Raport: '+str(output/'k4-report.json'),flush=True)
            except (OSError,ValueError) as error:
                (output/'k4-report.json').write_text(json.dumps({'verdict':'FAIL','reason':str(error)})+'\n',encoding='utf8')
                raise SystemExit('K4: niekompletne dowody; '+str(error))
