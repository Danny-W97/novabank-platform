"""Fixed local maintenance entry points; no shell, arbitrary properties, URLs or production profile."""
import os
from pathlib import Path
import re
import uuid
from dev_configuration import assignments
from env_ownership import normalize_pair

ACTIONS = {'totp-rotation-plan': 'totp-plan', 'totp-rotation-apply': 'totp-apply', 'payment-history-review': 'payment-review',
           'payment-history-migration-plan': 'payment-migration-plan', 'payment-history-migration-apply': 'payment-migration-apply',
           'payment-case-plan':'payment-case-plan','payment-case-decision':'payment-case-decision','payment-case-apply':'payment-case-apply',
           'purchase-cancel':'purchase-cancel','purchase-refresh':'purchase-refresh'}

CASE_DECISIONS=('ESCALATE','CLOSE_UNRESOLVED','ALLOW_NEW_PURCHASE','REVOKE_NEW_PURCHASE')


def validate(action, profile, parameters):
    if action in ('purchase-cancel','purchase-refresh'):
        if profile!='dev' or not isinstance(parameters,dict) or set(parameters)!={'orderId','requestId','reason','confirmation'}:raise ValueError('Operacja zakupu wymaga lokalnego DEV i zamkniętego zestawu parametrów.')
        # Reuse exact ID/reason/UUID validation without accepting an arbitrary decision or financial outcome.
        base={key:parameters[key] for key in ('orderId','requestId','reason')}
        validate('payment-case-plan',profile,base)
        if parameters['confirmation']!=('CANCEL' if action=='purchase-cancel' else 'REFRESH'):raise ValueError('Wpisz wymagane potwierdzenie operacji zakupu.')
        return dict(parameters)
    if action.startswith('payment-case-'):
        if profile!='dev' or action not in ACTIONS or not isinstance(parameters,dict):raise ValueError('Sprawy płatności są tylko dla DEV.')
        expected={'orderId','requestId','reason'}
        if action=='payment-case-decision':expected|={'decision','expectedVersion','confirmation'}
        if action=='payment-case-apply':expected|={'planDigest','backupsVerified','writesPaused','confirmation'}
        if set(parameters)!=expected:raise ValueError('Nieprawidłowe parametry sprawy.')
        result=dict(parameters)
        for key in ('orderId','expectedVersion'):
            if key not in result:continue
            value=result[key]
            if not isinstance(value,str) or not re.fullmatch('[1-9][0-9]{0,18}' if key=='orderId' else '0|[1-9][0-9]{0,18}',value) or int(value)>9223372036854775807:raise ValueError('Nieprawidłowy numer/wersja sprawy.')
        if not isinstance(result['requestId'],str) or str(uuid.UUID(result['requestId']))!=result['requestId']:raise ValueError('Wymagany UUID decyzji.')
        reason=result['reason']
        if not isinstance(reason,str) or not 8<=len(reason.strip())<=1000 or any(ord(c)<32 or ord(c)==127 for c in reason):raise ValueError('Uzasadnienie: 8–1000 znaków bez znaków sterujących.')
        if action=='payment-case-decision' and (result['decision'] not in CASE_DECISIONS or result['confirmation']!='DECIDE'):raise ValueError('Wybierz jawną decyzję i wpisz DECIDE.')
        if action=='payment-case-apply' and (result['confirmation']!='RECONCILE' or result['backupsVerified'] is not True or result['writesPaused'] is not True or not isinstance(result['planDigest'],str) or not re.fullmatch('[a-f0-9]{64}',result['planDigest'])):raise ValueError('Wymagane dowody, kopie, zatrzymanie zapisów i RECONCILE.')
        return result
    if action.startswith('payment-history-migration-'):
        if profile != 'dev': raise ValueError('Migracja historii jest wyłącznie dla lokalnego DEV.')
        if action == 'payment-history-migration-plan':
            if parameters not in (None, {}): raise ValueError('Plan migracji nie przyjmuje SQL, ścieżek ani mapowań z przeglądarki.')
            return {}
        if (action != 'payment-history-migration-apply' or not isinstance(parameters, dict)
                or set(parameters) != {'planDigest','confirmation','backupsVerified','writesPaused'}
                or parameters['confirmation'] != 'MIGRATE' or parameters['backupsVerified'] is not True or parameters['writesPaused'] is not True
                or not isinstance(parameters['planDigest'], str) or not re.fullmatch('[a-f0-9]{64}',parameters['planDigest'])):
            raise ValueError('Wymagany hash planu, MIGRATE oraz potwierdzenie odtworzenia kopii i zatrzymania zapisów.')
        return dict(parameters)
    if action == 'payment-history-review' and profile in ('dev', 'test'):
        if parameters not in (None, {}):
            raise ValueError('Przegląd płatności nie przyjmuje mapowań, SQL ani opcji zapisu.')
        return {}
    if action not in ACTIONS or profile not in ('dev', 'test') or not isinstance(parameters, dict):
        raise ValueError('Dozwolona jest wyłącznie jawna operacja administracyjna DEV/TEST.')
    expected = {'afterId', 'limit'} | ({'planDigest', 'confirmation'} if action.endswith('apply') else set())
    if set(parameters) != expected:
        raise ValueError('Nieprawidłowy zakres parametrów rotacji.')
    after = parameters['afterId']
    if type(after) is int:
        after = str(after)
    if not isinstance(after, str) or not re.fullmatch(r'0|[1-9][0-9]{0,18}', after) or int(after) > 9223372036854775807:
        raise ValueError('Nieprawidłowy checkpoint rotacji.')
    if type(parameters['limit']) is not int or not 1 <= parameters['limit'] <= 100:
        raise ValueError('Partia musi zawierać 1–100 rekordów.')
    result = dict(parameters, afterId=after)
    if action.endswith('apply') and (parameters['confirmation'] != 'ROTATE'
            or not isinstance(parameters['planDigest'], str) or not re.fullmatch(r'[a-f0-9]{64}', parameters['planDigest'])):
        raise ValueError('Wymagany hash przejrzanego planu i wpisanie ROTATE.')
    return result


def command(root, java, action, profile, parameters):
    parameters = validate(action, profile, parameters)
    if os.name == 'nt':
        raise ValueError('Operację administracyjną uruchom z Control Center działającego w WSL.')
    purchase=action in ('purchase-cancel','purchase-refresh')
    jar = Path(root) / ('shop-app/target/shop-app-0.0.1-SNAPSHOT.jar' if purchase else 'target/Bank-demo-0.0.1-SNAPSHOT.jar')
    if not jar.is_file() or jar.is_symlink() or not jar.resolve().is_relative_to(Path(root).resolve()):
        raise ValueError('Najpierw zbuduj aktualny JAR '+('sklepu' if purchase else 'banku')+'. Plik nie może być dowiązaniem.')
    if not java:
        raise ValueError('Brak JDK do uruchomienia narzędzia administracyjnego.')
    main='org.demo.shopapp.maintenance.PurchaseCancellationCommand' if purchase else 'org.demo.bankdemo.maintenance.MaintenanceCommand'
    return [java, '-Dloader.main='+main, '-cp', str(jar),
            'org.springframework.boot.loader.launch.PropertiesLauncher', '--profile='+profile, '--operation='+ACTIONS[action],
            *['--'+key+'='+str(value) for key, value in parameters.items()]]


def environment(panel, profile, action=None):
    if profile == 'test':
        values = panel.configuration_files.test_environment()
        allowed = {'TEST_POSTGRES_URL', 'TEST_POSTGRES_USER', 'TEST_POSTGRES_PASSWORD', 'TEST_TOTP_ENCRYPTION_KEY',
                   'TEST_TOTP_ENCRYPTION_KEYS', 'TEST_TOTP_ENCRYPTION_ACTIVE_ID'}
    elif profile == 'dev':
        with panel.dev_configuration.lock:
            snapshot, _ = normalize_pair(panel.dev_configuration.snapshot())
            values = {**assignments(snapshot['project'] or b''), **assignments(snapshot['dev'] or b'')}
        allowed = {'POSTGRES_HOST', 'POSTGRES_PORT', 'POSTGRES_DB', 'POSTGRES_USER', 'POSTGRES_PASSWORD',
                   'TOTP_ENCRYPTION_KEY', 'TOTP_ENCRYPTION_KEYS', 'TOTP_ENCRYPTION_ACTIVE_ID'}
    else:
        raise ValueError('Narzędzie jest dostępne tylko w DEV/TEST.')
    if action and (action.startswith(('payment-history-migration-','payment-case-')) or action in ('purchase-cancel','purchase-refresh')):
        if profile != 'dev': raise ValueError('Import historii jest tylko dla DEV.')
        from dev_cash import DevCash
        from dev_shop_accounts import DevShopDatabase
        import json
        # Match the exact Compose checkout AND the host port used by the JDBC command.
        # Merely reaching localhost is insufficient for a financial-history write tool.
        local_env={key:value for key,value in os.environ.items() if key in {'PATH','HOME','LANG','LC_ALL'}}
        for adapter,container,key in ((DevCash,'bank-postgres','POSTGRES_PORT'),(DevShopDatabase,'shop-postgres','SHOP_POSTGRES_PORT')):
            database=adapter(panel.root,values,local_env)
            database._base_command()  # Identity/labels/working directory and local host checks only.
            mappings=json.loads(database._run(['docker','--host','unix:///var/run/docker.sock','inspect','--format','{{json .NetworkSettings.Ports}}',container]))
            bindings=mappings.get('5432/tcp',[]) if isinstance(mappings,dict) else []
            if not any(item.get('HostIp') in ('127.0.0.1','::1') and item.get('HostPort')==values.get(key) for item in bindings):
                raise ValueError('Port JDBC nie odpowiada zweryfikowanej bazie Compose tego projektu; nic nie zapisano.')
        allowed = {'POSTGRES_HOST','POSTGRES_PORT','POSTGRES_DB','POSTGRES_USER','POSTGRES_PASSWORD',
                   'SHOP_POSTGRES_HOST','SHOP_POSTGRES_PORT','SHOP_POSTGRES_DB','SHOP_POSTGRES_USER','SHOP_POSTGRES_PASSWORD',
                   'SHOP_MERCHANT_ID','SHOP_MERCHANT_ACCOUNT_NUMBER','SHOP_PAYMENT_API_KEY',
                   'BANK_API_KEY_ID','BANK_API_KEY_MERCHANTS','BANK_PAYMENT_MERCHANTS'}
        # Both hashes are computed on immutable copies by the same checksum gate as recovery.
        result={key:value for key,value in values.items() if key in allowed}
        if action in ('purchase-cancel','purchase-refresh'):
            from urllib.parse import urlsplit
            address=values.get('BANK_API_URL','http://127.0.0.1:8080'); parsed=urlsplit(address)
            if parsed.scheme!='http' or parsed.hostname not in ('127.0.0.1','localhost') or parsed.username or parsed.password or parsed.query or parsed.fragment or parsed.path not in ('','/') or (parsed.port or 80)!=int(values.get('BANK_APP_PORT','8080')):raise ValueError('Wymagany lokalny adres banku zgodny z portem DEV.')
            result.update({key:values[key] for key in ('BANK_API_KEY','BANK_API_KEY_ID','SHOP_PAYMENT_PROVIDER') if key in values})
            result['BANK_API_URL']=address
            return result
        if action=='payment-case-decision':
            # Workflow/risk decision does not write financial rows. Constructor still validates the merchant.
            result.update(MIGRATION_BANK_BACKUP_SHA256='0'*64,MIGRATION_SHOP_BACKUP_SHA256='0'*64)
        else:
            recovery=panel.totp_recovery
            settings=panel.operation_settings.state('dev')['values']
            bank=recovery.backup(settings)
            shop=bank.with_name('shop_'+bank.name.removeprefix('bank_'))
            for name,path in (('BANK',bank),('SHOP',shop)):
                with recovery.snapshot(path) as (_,digest): result['MIGRATION_'+name+'_BACKUP_SHA256']=digest
        return result
    return {key: value for key, value in values.items() if key in allowed}
