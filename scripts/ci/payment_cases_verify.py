"""Verify on a secret-free exported checkout. Never repackage an operator's running JAR."""
import argparse
import concurrent.futures
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
from source_bundle import ROOT,export

def verify(root,module,full):
    output=ROOT/'target/payment-cases-verify'/('full' if full else 'smoke');output.mkdir(parents=True,exist_ok=True)
    options=[] if module=='bank' else ['-f','shop-app/pom.xml']
    prefix=('SPRING_','TEST_','BANK_','SHOP_','TOTP_','REDIS_','POSTGRES_','STRIPE_','VAULT_','MANAGEMENT_','SERVER_')
    env={k:v for k,v in os.environ.items() if not k.startswith(prefix) and k not in ('JAVA_TOOL_OPTIONS','JDK_JAVA_OPTIONS','_JAVA_OPTIONS','MAVEN_ARGS','MAVEN_OPTS')}
    selections={'bank':'PaymentCaseAdministrationIT,HistoricalPaymentMigrationIT,AuditPaymentConcurrencyIT',
                'shop':'PurchaseIdentityIT,PurchaseCancellationIT,PaymentAttemptConcurrencyIT'}
    phases=[('unit',[],'test'),('integration',['-Pintegration-tests']+([] if full else ['-Dit.test='+selections[module]]),'verify')]
    for phase,flags,goal in phases:
        log=output/f'{module}-{phase}.log'
        with log.open('w') as stream:
            result=subprocess.run(['bash','./mvnw','-B','-ntp',*options,*flags,goal],cwd=root,env=env,stdout=stream,stderr=subprocess.STDOUT)
        reports=root/('target' if module=='bank' else 'shop-app/target')/('surefire-reports' if phase=='unit' else 'failsafe-reports')
        if reports.is_dir():shutil.copytree(reports,output/f'{module}-{phase}-reports',dirs_exist_ok=True)
        matrix=root/('target/bank-security-matrix.json' if module=='bank' else 'shop-app/target/shop-security-matrix.json')
        if matrix.is_file():shutil.copy2(matrix,output/matrix.name)
        print(f'{module} {phase}: exit={result.returncode}; {log}',flush=True)
        if result.returncode:return result.returncode
    return 0

if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--full',action='store_true');args=parser.parse_args()
    with tempfile.TemporaryDirectory(prefix='novabank-payment-cases-') as directory:
        root=Path(directory)/'source';manifest=export(root)
        output=ROOT/'target/payment-cases-verify'/('full' if args.full else 'smoke');output.mkdir(parents=True,exist_ok=True)
        shutil.copy2(root/'source-manifest.json',output/'source-manifest.json')
        print(f'Exported {len(manifest)} public files. No runtime env, DEV changes, staging or commits.',flush=True)
        with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
            results=list(pool.map(lambda module:verify(root,module,args.full),('bank','shop')))
    raise SystemExit(max(results))
