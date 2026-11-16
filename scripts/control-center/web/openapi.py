"""Public, secret-free description of the local panel contract (no commands run)."""
from dev_configuration import FIELDS
from production_operations import PARAMETERS as PRODUCTION_PARAMETERS
from problems import CATALOG
from problem_support import REPAIR_ACTION


def ref(name):
    return {"$ref": f"#/components/schemas/{name}"}


def obj(properties, required=None, **options):
    return {"type": "object", "properties": properties,
            "required": list(properties) if required is None else required, **options}


def array(schema):
    return {"type": "array", "items": schema}


def document(actions):
    """Actions come from the server allowlist, never from runtime logs or secrets."""
    text = {"type": "string"}
    boolean = {"type": "boolean"}
    count = {"type": "integer", "minimum": 0}
    identifier = {"type": "integer", "minimum": 1, "maximum": 9223372036854775807}
    job_id = {"type": "string", "pattern": "^[0-9a-f]{32}$"}
    timestamp = {"type": "string", "format": "date-time"}
    nullable_time = {"type": ["string", "null"], "format": "date-time"}
    nullable_id = {"type": ["integer", "null"], "minimum": 1, "maximum": 9223372036854775807}
    account_identifier = {"type": "string", "pattern": "^[1-9][0-9]{0,18}$", "description": "Dodatni int64 do 9223372036854775807 jako tekst — bez utraty precyzji JS."}
    view = {"type": "string", "enum": ["active", "archived", "deleted"]}
    profile = {"type": "string", "enum": ["dev", "test", "prod"]}
    cash = obj({"accountId": identifier,
                "amount": {"type": "string", "pattern": r"^(?!0(?:\.0{1,2})?$)(?:0|[1-9]\d{0,8})(?:\.\d{1,2})?$",
                           "description": "Dodatnia kwota 0.01–999999999.99 PLN; kropka, nie przecinek. Serwer używa Decimal. Zero jest odrzucane."},
                "requestId": {"type": "string", "format": "uuid",
                              "pattern": "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$",
                              "description": "Kanoniczny UUID, małe litery. Ponowienie tych samych danych z tym UUID nie księguje drugi raz."}},
               additionalProperties=False)
    job_requests = []
    for action in actions:
        properties = {"action": {"type": "string", "const": action["id"]},
                      "profile": {"type": "string", "enum": list(action["profiles"])},
                      "confirmed": {"type": "boolean", **({"const": True} if action["changes"] else {})}}
        extra_required = []
        if "prod" in action["profiles"]:
            properties["productionConfirmation"] = {"type": ["string", "null"], "description": "W widoku PROD obowiązkowe dokładnie PROD; w DEV/TEST tylko brak/null. Nie jest sekretem."}
        if action['id'] in ('purchase-cancel','purchase-refresh'):
            fields={'orderId':account_identifier,'requestId':{'type':'string','format':'uuid'},'reason':{'type':'string','minLength':8,'maxLength':1000},'confirmation':{'type':'string','const':'CANCEL' if action['id']=='purchase-cancel' else 'REFRESH'}}
            properties['parameters']=obj(fields,additionalProperties=False);extra_required.append('parameters')
        elif action['id'].startswith('payment-case-'):
            fields={'orderId':account_identifier,'requestId':{'type':'string','format':'uuid'},'reason':{'type':'string','minLength':8,'maxLength':1000}}
            if action['id']=='payment-case-decision':fields.update(expectedVersion={'type':'string','pattern':'^(0|[1-9][0-9]{0,18})$'},decision={'type':'string','enum':['ESCALATE','CLOSE_UNRESOLVED','ALLOW_NEW_PURCHASE','REVOKE_NEW_PURCHASE']},confirmation={'const':'DECIDE','type':'string'})
            if action['id']=='payment-case-apply':fields.update(planDigest={'type':'string','pattern':'^[a-f0-9]{64}$'},confirmation={'type':'string','const':'RECONCILE'},backupsVerified={'type':'boolean','const':True},writesPaused={'type':'boolean','const':True})
            properties['parameters']=obj(fields,additionalProperties=False);extra_required.append('parameters')
        elif action["id"] == 'backup-totp-verify':
            properties['parameters'] = obj({'planId': {'type': 'string', 'pattern': '^[a-f0-9]{32}$'},
                'keys': {'type': 'object', 'minProperties': 1, 'maxProperties': 32,
                         'propertyNames': {'pattern': '^[A-Za-z0-9_-]{1,32}$'},
                         'additionalProperties': {'type': 'string', 'writeOnly': True, 'maxLength': 128,
                                                  'description': 'AES Base64; tylko pamięć zadania i stdin weryfikatora. Bez zapisu w Job/env/logach.'}}}, additionalProperties=False)
            extra_required.append('parameters')
        elif action["id"] == 'payment-history-migration-apply':
            properties['parameters'] = obj({'planDigest': {'type':'string','pattern':'^[a-f0-9]{64}$'},
                'confirmation': {'type':'string','const':'MIGRATE'},'backupsVerified': {'type':'boolean','const':True},
                'writesPaused': {'type':'boolean','const':True}}, additionalProperties=False)
            extra_required.append('parameters')
        elif action["id"] == REPAIR_ACTION:
            properties["parameters"] = ref("RepairParameters")
            extra_required.append("parameters")
        elif action["id"] in ("migrate-cli-backup", "migrate-cli-dr"):
            properties["parameters"] = obj({"revision": {"type": "string", "pattern": "^[a-f0-9]{64}$"}}, additionalProperties=False)
            extra_required.append("parameters")
        elif action["id"] in PRODUCTION_PARAMETERS:
            fields = PRODUCTION_PARAMETERS[action["id"]]
            parameters = {key: ({"type": "boolean", "const": True} if key == "restarted" else
                                {"type": "string", "writeOnly": True, "maxLength": 1024,
                                 "minLength": 16 if "Password" in key and required else 1 if required else 0,
                                 "pattern": r"^[A-Za-z0-9._~+/=@!%:-]*$", "description": "Tylko pamięć zadania; nie pojawia się w Job/logach/URL."})
                          for key, required in fields.items()}
            properties["parameters"] = obj(parameters, required=[key for key, required in fields.items() if required], additionalProperties=False)
            for key in parameters:
                if "Password" in key and not fields[key]:
                    parameters[key]["anyOf"] = [{"maxLength": 0}, {"minLength": 6 if key == "truststorePassword" else 16}]
            if not any(fields.values()):
                properties["parameters"] = {"anyOf": [properties["parameters"], {"type": "null"}]}
            if any(fields.values()):
                extra_required.append("parameters")
        elif action["id"] == "artifacts-delete":
            properties["parameters"] = obj({"deleteConfirmation": {"type": "string", "const": "USUN"}}, additionalProperties=False)
            extra_required.append("parameters")
        elif action["id"] in ("cash-credit", "cash-debit"):
            properties["parameters"] = ref("CashParameters")
            extra_required.append("parameters")
        else:
            properties["parameters"] = {"type": "null", "description": "Opcjonalne null; pozostałe parametry są odrzucane."}
        required = ["action", "profile", "confirmed"] + extra_required
        branch = obj(properties, required, title=action["title"], description=action["description"], additionalProperties=False)
        if "prod" in action["profiles"]:
            branch["allOf"] = [{"if": {"properties": {"profile": {"const": "prod"}}, "required": ["profile"]},
                                "then": {"properties": {"confirmed": {"const": True}, "productionConfirmation": {"const": "PROD"}}, "required": ["productionConfirmation"]},
                                "else": {"properties": {"productionConfirmation": {"type": "null"}}}}]
        job_requests.append(branch)
    notification_requests = []
    for operation in ("archive", "delete", "restore", "delete-active"):
        properties = {"operation": {"type": "string", "const": operation},
                      "confirmed": {"type": "boolean", **({"const": True} if operation.startswith("delete") else {})}}
        if operation != "delete-active":
            properties["id"] = identifier
        notification_requests.append(obj(properties, additionalProperties=False))
    schemas = {
        "PaymentCasePage":obj({'cases':array({'type':'object'}),'next':{'type':['string','null']},'profile':{'const':'dev','type':'string'}}),
        "PaymentCaseDetail":obj({'id':account_identifier,'orderNumber':text,'customerId':text,'localStatus':text,'outcome':text,'workflow':text,'version':text,'cancellationAvailable':{'type':'boolean'},'attempts':array({'type':'object'}),'refunds':array({'type':'object'}),'events':array({'type':'object'}),'plan':{'type':['object','null']}},additionalProperties=True),
        "Error": obj({"error": text, "issueCode": {"type": "string", "enum": list(CATALOG)}, "notificationId": nullable_id}, required=["error"]),
        "RepairParameters": obj({"notificationId": identifier, "revision": {"type": "string", "pattern": "^[0-9a-f]{64}$"},
                                 "planToken": {"type": "string", "pattern": "^[0-9a-f]{64}$", "description": "Plan z tej instancji; nie jest hasłem użytkownika."},
                                 "requestId": {"type": "string", "format": "uuid", "description": "Ponowienie tej samej próby zwraca ten sam job; nie wykonuje zapisu drugi raz."}}, additionalProperties=False),
        "SupportCheck": obj({"id": identifier}, additionalProperties=False),
        "ProblemGuide": obj({"code": {"type": "string", "enum": list(CATALOG)}, "title": text, "impact": text,
                             "section": text, "anchor": text, "document": text, "check": {"type": ["string", "null"]}, "steps": array(text)}),
        "RepairPlan": obj({"revision": text, "planToken": text, "title": text, "source": text, "writes": array(text), "effects": text, "restartRequired": boolean}),
        "SupportDetails": obj({"notificationId": identifier, "problem": ref("ProblemGuide"), "profile": {"type": ["string", "null"], "enum": ["dev", "test", "prod", None]},
                               "jobId": {"anyOf": [job_id, {"type": "null"}]}, "context": obj({"component": {"type": "string", "enum": ["bank", "shop"]}, "port": count}, required=[], additionalProperties=False),
                               "outcome": {"type": "string", "enum": ["open", "configuration_verified", "condition_cleared", "manual"]}, "outcomeNote": {"type": ["string", "null"]},
                               "actions": array(obj({"kind": {"type": "string", "enum": ["view", "job"]}, "id": text, "title": text})),
                               "plan": {"anyOf": [ref("RepairPlan"), {"type": "null"}]}, "diagnosis": text, "readOnly": boolean}),
        "Ok": obj({"ok": boolean}),
        "CashParameters": cash,
        "JobRequest": {"oneOf": job_requests},
        "NotificationRequest": {"oneOf": notification_requests},
        "CancelRequest": obj({"id": job_id}, additionalProperties=False),
        "Action": obj({"id": text, "title": text, "group": text, "description": text,
                       "profiles": array(profile), "changes": boolean, "timeout": count}),
        "Job": obj({"id": job_id, "action": {"type": "string", "enum": [a["id"] for a in actions]},
                    "title": text, "profile": profile,
                    "status": {"type": "string", "enum": ["running", "success", "failed", "cancelled", "interrupted"]},
                    "started": timestamp, "finished": nullable_time, "exitCode": {"type": ["integer", "null"]},
                    "parameters": ref("CashParameters")},
                   required=["id", "action", "title", "profile", "status", "started", "finished", "exitCode"]),
        "JobDetail": {"allOf": [ref("Job"), obj({"log": text})]},
        "Jobs": obj({"jobs": array(ref("Job"))}),
        "DocumentationLink": obj({"swagger": {"type": ["string", "null"]},
                                  "openapi": {"type": ["string", "null"]}, "note": text}),
        "DocumentationLinks": obj({"bank": ref("DocumentationLink"), "shop": ref("DocumentationLink")}),
        "Bootstrap": obj({"token": {"type": "string",
                                    "description": "Losowy token bieżącej instancji do nagłówka X-Control-Center-Token. Nie zapisuj ani nie udostępniaj."},
                          "actions": array(ref("Action")), "project": text, "platform": text,
                          "jobs": array(ref("Job")), "documentation": ref("DocumentationLinks")}),
        "Service": obj({"name": text, "port": {"type": ["integer", "null"], "minimum": 1, "maximum": 65535},
                        "up": boolean, "group": text, "detail": text}),
        "Status": obj({"services": array(ref("Service")), "checked": timestamp, "note": text}),
        "Account": obj({"id": identifier, "owner": text,
                        "number": {"type": "string", "description": "Maskowany numer: wyłącznie końcówka rachunku."},
                        "balance": text, "currency": {"type": "string", "const": "PLN"},
                        "status": {"type": "string", "const": "ACTIVE"}}),
        "Accounts": obj({"accounts": {"type": "array", "items": ref("Account"), "maxItems": 500}, "truncated": boolean}),
        "BankAccountRow": obj({"id": account_identifier, "ownerId": account_identifier, "owner": text, "email": text,
                               "number": {"type": "string", "description": "Maskowany numer — tylko ostatnie 4 cyfry."}, "balance": text,
                               "currency": {"type": "string", "pattern": "^[A-Z]{3}$"}, "status": {"type": "string", "enum": ["ACTIVE", "BLOCKED", "CLOSED"]},
                               "userStatus": {"type": "string", "enum": ["ACTIVE", "BLOCKED"]}}),
        "BankAccountPage": obj({"accounts": {"type": "array", "items": ref("BankAccountRow"), "maxItems": 50}, "next": {"anyOf": [account_identifier, {"type": "null"}]}, "pageSize": {"const": 50, "type": "integer"}, "readOnly": {"const": True, "type": "boolean"}}),
        "BankAccount": obj({"id": account_identifier, "ownerId": account_identifier, "owner": text, "email": text,
                            "balance": text, "currency": text, "status": {"type": "string", "enum": ["ACTIVE", "BLOCKED", "CLOSED"]},
                            "userStatus": {"type": "string", "enum": ["ACTIVE", "BLOCKED"]}, "role": {"type": "string", "enum": ["USER", "ADMIN"]},
                            "accountNumber": {"type": "string", "pattern": "^[0-9]{26}$"}, "emailVerified": boolean, "twoFactorEnabled": boolean,
                            "createdAt": timestamp, "updatedAt": timestamp, "userCreatedAt": timestamp, "lastLoginAt": nullable_time}),
        "BankAccountDetail": obj({"account": ref("BankAccount"), "readOnly": {"const": True, "type": "boolean"}}),
        "ShopAccountRow": obj({"id": account_identifier, "customerId": text, "email": text,
                               "firstName": {"type": ["string", "null"]}, "lastName": {"type": ["string", "null"]},
                               "active": boolean, "role": {"type": "string", "enum": ["CUSTOMER", "ADMIN"]}, "createdAt": timestamp}),
        "ShopAccountPage": obj({"accounts": {"type": "array", "items": ref("ShopAccountRow"), "maxItems": 50}, "next": {"anyOf": [account_identifier, {"type": "null"}]}, "pageSize": {"const": 50, "type": "integer"}, "readOnly": {"const": True, "type": "boolean"}}),
        "ShopAccount": obj({"id": account_identifier, "customerId": text, "email": text,
                            "firstName": {"type": ["string", "null"]}, "lastName": {"type": ["string", "null"]},
                            "active": boolean, "role": {"type": "string", "enum": ["CUSTOMER", "ADMIN"]},
                            "createdAt": timestamp, "updatedAt": timestamp, "hasPhone": boolean, "hasAddress": boolean}),
        "ShopAccountDetail": obj({"account": ref("ShopAccount"), "readOnly": {"const": True, "type": "boolean"}}),
        "OperatorDevValues": obj({"backupDirectory": {"type": "string", "minLength": 1, "maxLength": 400, "description": "Podkatalog .novabank/backups albo dedykowany pusty lokalny katalog novabank-dev-backups[-nazwa]. Bez dowiązań i przejęcia niepustego obcego katalogu."},
                                  "backupRetentionDays": {"type": "integer", "minimum": 1, "maximum": 3650},
                                  "backupAt": {"type": "string", "pattern": "^([01][0-9]|2[0-3]):[0-5][0-9]$"},
                                  "drAt": {"type": "string", "pattern": "^([01][0-9]|2[0-3]):[0-5][0-9]$"},
                                  "drWeekday": {"type": "string", "enum": ["MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN"]},
                                  "artifactRetentionDays": {"type": "integer", "minimum": 1, "maximum": 3650},
                                  "tempRetentionDays": {"type": "integer", "minimum": 1, "maximum": 365}}, additionalProperties=False),
        "OperatorTestValues": obj({"artifactRetentionDays": {"type": "integer", "minimum": 1, "maximum": 3650},
                                   "tempRetentionDays": {"type": "integer", "minimum": 1, "maximum": 365}}, additionalProperties=False),
        "OperatorState": {"oneOf": [obj({"profile": {"const": p, "type": "string"}, "revision": {"type": "string", "pattern": "^[a-f0-9]{64}$"}, "values": ref(name), "exists": boolean}, additionalProperties=False) for p, name in (("dev", "OperatorDevValues"), ("test", "OperatorTestValues"))]},
        "OperatorSave": {"oneOf": [obj({"profile": {"const": p, "type": "string"}, "revision": {"type": "string", "pattern": "^[a-f0-9]{64}$"}, "values": ref(name), "confirmed": {"type": "boolean", "const": True}}, additionalProperties=False) for p, name in (("dev", "OperatorDevValues"), ("test", "OperatorTestValues"))]},
        "LegacyTimerPlan": obj({"kind": {"type": "string", "enum": ["backup", "dr"]}, "revision": {"type": "string", "pattern": "^[a-f0-9]{64}$"},
                                "legacyTimer": text, "newTimer": text, "legacyExists": boolean, "legacyEnabled": boolean, "legacyActive": boolean,
                                "serviceRunning": boolean, "newExists": boolean, "legacyBackupDirectory": {"type": ["string", "null"]},
                                "canMigrate": boolean, "reason": text, "settings": ref("OperatorDevValues")}),
        "ManagedSchedule": obj({"kind": {"type": "string", "enum": ["backup", "dr"]}, "timer": text, "service": text,
                                "installed": boolean, "enabled": boolean, "waiting": boolean,
                                "configuration": {"type": "string", "enum": ["UNKNOWN", "ABSENT", "VALID", "INVALID"]}, "configurationNote": text,
                                "execution": {"type": "string", "enum": ["UNKNOWN", "NEVER", "RUNNING", "SUCCESS", "FAILED"]},
                                "lastStarted": {"type": ["string", "null"]}, "lastFinished": {"type": ["string", "null"]},
                                "exitCode": {"type": ["integer", "null"]}, "nextRun": {"type": ["string", "null"]}}),
        "LegacySchedules": obj({"available": boolean, "timers": {"type": "array", "maxItems": 2, "items": ref("LegacyTimerPlan")}, "managed": {"type": "array", "maxItems": 2, "items": ref("ManagedSchedule")}, "note": text}),
        "CliHistory": obj({"items": {"type": "array", "maxItems": 500, "items": obj({"id": {"type": "string", "pattern": "^[a-f0-9]{64}$"}, "created": timestamp, "result": {"type": "string", "enum": ["OK", "BLAD"]}, "title": {"type": "string", "maxLength": 240}}, additionalProperties=False)}, "sourceExists": boolean, "limit": {"type": "integer", "const": 500}, "note": text}),
        "LegacyState": obj({"schedules": ref("LegacySchedules"), "history": ref("CliHistory")}),
        "Counts": obj({"active": count, "archived": count, "deleted": count}),
        "Notification": obj({"id": identifier, "source": text, "level": {"type": "string", "enum": ["WARN", "ERROR"]},
                             "message": text, "created": timestamp, "state": view,
                             "previous_state": {"type": ["string", "null"], "enum": ["active", "archived", None]},
                             "state_changed_at": nullable_time}),
        "NotificationDetail": obj({"notification": ref("Notification")}),
        "Notifications": obj({"total": count, "counts": ref("Counts"), "view": view, "revision": count,
                              "items": {"type": "array", "items": ref("Notification"), "maxItems": 50},
                              "next": nullable_id, "sourcesAvailable": boolean, "note": text}),
        "NotificationChange": obj({"changed": count, "notification": {"anyOf": [ref("Notification"), {"type": "null"}]},
                                   "counts": ref("Counts")}),
        "OpenApiDocument": {"type": "object", "description": "Dokument OpenAPI 3.1 opisujący API panelu."},
    }
    schemas["Notification"]["properties"].update({
        "issue_code": {"type": ["string", "null"], "enum": [*CATALOG, None]},
        "profile": {"type": ["string", "null"], "enum": ["dev", "test", "prod", None]},
        "job_id": {"anyOf": [job_id, {"type": "null"}]},
        "outcome": {"type": "string", "enum": ["open", "configuration_verified", "condition_cleared", "manual"]},
        "outcome_at": nullable_time, "outcome_note": {"type": ["string", "null"]},
        "issue_context": obj({"component": text, "port": count}, required=[]),
    })
    config_values = {}
    for key, label, _, kind, _, _, _ in FIELDS:
        if kind == "managed":
            continue
        field = {"type": "string", "maxLength": 256, "description": label}
        if kind == "host":
            field["enum"] = ["localhost", "127.0.0.1"]
        elif kind == "port":
            field.update(pattern="^(?:10(?:2[4-9]|[3-9][0-9])|1[1-9][0-9]{2}|[2-9][0-9]{3}|[1-5][0-9]{4}|6[0-4][0-9]{3}|65[0-4][0-9]{2}|655[0-2][0-9]|6553[0-5])$", description=label + "; 1024–65535, różny od innych portów.")
        elif kind == "account":
            field.update(pattern="^(?:[0-9]{26})?$", writeOnly=True, description=label + "; puste zachowuje zapisany numer.")
        elif kind == "secret":
            field.update(pattern="^(?:[A-Za-z0-9_+=./!:@%\\-]{16,256})?$", writeOnly=True,
                         description=label + "; puste zachowuje sekret. Istniejące prawidłowe hasło chronione przed zmianą.")
        else:
            field["pattern"] = "^[A-Za-z][A-Za-z0-9_]{0,62}$" if key.endswith(("_DB", "_USER")) else "^[A-Za-z][A-Za-z0-9_-]{0,62}$"
        config_values[key] = field
    schemas["DevConfigurationRequest"] = obj({"profile": {"type": "string", "const": "dev"},
                                              "revision": {"type": "string", "pattern": "^[0-9a-f]{64}$"},
                                              "values": obj(config_values, required=[], additionalProperties=False),
                                              "confirmed": {"type": "boolean", "const": True}}, additionalProperties=False)
    schemas["ConfigurationField"] = obj({"key": {"type": "string", "enum": [field[0] for field in FIELDS]},
                                          "label": text, "group": text, "kind": {"type": "string", "enum": ["host", "port", "name", "secret", "account", "managed"]},
                                          "value": {"type": ["string", "null"], "description": "Sekrety, rachunek i klucze zawsze null."},
                                          "configured": boolean, "locked": boolean, "file": text},
                                         allOf=[{"if": {"properties": {"kind": {"enum": ["secret", "account", "managed"]}}, "required": ["kind"]},
                                                 "then": {"properties": {"value": {"type": "null"}}}}])
    schemas["DevConfiguration"] = obj({"profile": {"type": "string", "const": "dev"}, "revision": {"type": "string", "pattern": "^[0-9a-f]{64}$"},
                                       "files": array(obj({"path": text, "exists": boolean})),
                                       "fields": array(ref("ConfigurationField")), "ready": boolean,
                                       "paymentsReady": boolean, "warnings": array(text), "note": text, "message": text},
                                      required=["profile", "revision", "files", "fields", "ready", "paymentsReady", "warnings", "note"])

    def parameter(name, location, schema, description, required=False):
        return {"name": name, "in": location, "required": required, "schema": schema, "description": description}

    def response(schema, description):
        return {"description": description, "content": {"application/json": {"schema": ref(schema)}}}

    errors = {"400": "Nieprawidłowe dane, profil, ID albo brak wymaganego potwierdzenia.",
              "403": "Niezaufany Host/Origin/Sec-Fetch-Site albo brak lub niepoprawny token.",
              "404": "Brak wskazanego zasobu lub niedozwolona postać operacji.",
              "409": "Inne zadanie już trwa albo panel jest zamykany.",
              "413": "Nieprawidłowy Content-Length: 1–4096 bajtów; jobs do 8192, edytor save do 32768.",
              "415": "Wymagany Content-Type application/json.",
              "500": "Błąd systemowy uruchomienia lub zatrzymania zadania.",
              "503": "Niedostępny odczyt danych DEV lub lokalna baza powiadomień."}

    def operation(op_id, title, description, schema, tag, *, token=False, code="200", failures=(), params=None, body=None):
        responses = {code: response(schema, "Wynik; 202 oznacza przyjęcie zadania, nie zakończenie operacji." if code == "202" else "Wynik operacji.")}
        for failure in ("403", *failures):
            responses[failure] = response("Error", errors[failure])
        result = {"operationId": op_id, "summary": title, "description": description, "tags": [tag],
                  "security": [{"ControlCenterToken": []}] if token else [], "responses": responses}
        if params:
            result["parameters"] = params
        if body:
            result["requestBody"] = {"required": True, "content": {"application/json": {"schema": ref(body)}}}
        return result

    paths = {
        "/api/payment-cases":{"get":operation("paymentCaseList","Sprawy płatności DEV","Odczyt lokalnych spraw, z rozdzieleniem wyniku finansowego i decyzji administracyjnej. Maksymalnie 25 pozycji, bez sekretów i danych kart. Wymaga V16.","PaymentCasePage","Uzgadnianie płatności",token=True,failures=("400","503"),params=[parameter('bucket','query',{'type':'string','enum':['ALL','CURRENT','REVIEW','HISTORY']},'Obszar zakupów; sam wiek nie rozstrzyga płatności.'),parameter('profile','query',{'type':'string','const':'dev'},'Tylko DEV.',True),parameter('q','query',{'type':'string','maxLength':128},'ID/numer zamówienia lub ID klienta.'),parameter('view','query',{'type':'string','enum':['ALL','UNRESOLVED','ESCALATED','CLOSED_UNRESOLVED','RESOLVED','REFUNDS','CONFLICT']},'Filtr spraw.'),parameter('before','query',account_identifier,'Kursor strony.')])},
        "/api/payment-cases/{id}":{"get":operation("paymentCaseDetail","Dowody i historia decyzji DEV","Ograniczone próby, zwroty i 50 ostatnich zdarzeń audytu. Brak hasła/PAN/CVV. Decyzje tylko przez jawne zadania, bez zapisu przez GET.","PaymentCaseDetail","Uzgadnianie płatności",token=True,failures=("400","404","503"),params=[parameter('id','path',account_identifier,'ID zamówienia.',True),parameter('profile','query',{'type':'string','const':'dev'},'Tylko DEV.',True)])},
        "/api/operations/settings": {
            "get": operation("operatorSettings", "Ustawienia operacji DEV/TEST", "Odczyt bez tworzenia plików. Katalog kopii, retencje i terminy DEV; TEST tylko retencje artefaktów. Nie dotyczy PROD.", "OperatorState", "Ustawienia operacji", token=True, failures=("400", "503"), params=[parameter("profile", "query", {"type": "string", "enum": ["dev", "test"]}, "Pojedynczy profil, bez dodatkowych parametrów.", True)]),
            "post": operation("saveOperatorSettings", "Zapisz ustawienia operacji", "Zapis JSON z rewizją i confirmed=true. Blokada podczas zadania. Nie przenosi kopii, nie przejmuje katalogów ani nie instaluje timerów. Istniejące timery wymagają osobnej aktualizacji. Nazwy/dni/godziny/ścieżki są walidowane; nie przyjmuje poleceń shell.", "OperatorState", "Ustawienia operacji", token=True, failures=("400", "409", "503"), body="OperatorSave")},
        "/api/operations/legacy": {"get": operation("legacyOperations", "Plan timerów i osobna historia CLI", "Tylko odczyt znanych jednostek systemd użytkownika i zaimportowanej historii. DEV: plany dwóch timerów; TEST: tylko historia. Bez mutacji/importu przy GET. Plan jest powiązany z rewizją plików, stanu systemd i ustawień. Migracja wymaga osobnego zadania migrate-cli-backup/dr z revision i confirmed=true; import to import-cli-history. Wpisy logu nie są wykonywalnymi zadaniami. Brak systemd/nieznana własność/drop-in blokuje plany, nie wymusza przejęcia.", "LegacyState", "Ustawienia operacji", token=True, failures=("400", "409", "503"), params=[parameter("profile", "query", {"type": "string", "enum": ["dev", "test"]}, "Pojedynczy profil DEV/TEST.", True)])},
        "/api/bank-accounts": {"get": operation("bankAccountList", "Przegląd rachunków bankowych DEV", "Tylko odczyt zweryfikowanego lokalnego kontenera DEV tego checkoutu. Maksymalnie 50 rachunków, najnowsze ID pierwsze; next to before kolejnej strony. Fraza jest tekstem, nie SQL ani wzorcem LIKE. Lista zawiera dane osobowe i maskowany numer; bez eksportu/utrwalania w panelu i bez hashy lub sekretów uwierzytelnienia. Wymaga WSL/Docker i migracji. Nie wymaga działającej aplikacji banku. Wszystkie parametry pojedyncze; nieznane są odrzucane.", "BankAccountPage", "Konta bankowe DEV", token=True, failures=("400", "503"), params=[parameter("profile", "query", {"const": "dev", "type": "string"}, "Wyłącznie DEV.", True), parameter("q", "query", {"type": "string", "maxLength": 128}, "Imię/nazwisko, e-mail, ID lub numer rachunku; bez znaków sterujących."), parameter("status", "query", {"type": "string", "enum": ["ALL", "ACTIVE", "BLOCKED", "CLOSED"]}, "Domyślnie ALL."), parameter("currency", "query", {"type": "string", "pattern": "^(ALL|[A-Z]{3})$"}, "Kod waluty lub ALL."), parameter("before", "query", account_identifier, "ID mniejsze niż ten kursor; bez offsetu.")])},
        "/api/bank-accounts/{id}": {"get": operation("bankAccountDetail", "Szczegóły rachunku bankowego DEV", "Tylko odczyt: pełny numer, saldo, właściciel, e-mail, statusy, rola, flagi 2FA/weryfikacji i daty. Bez hasła/PIN-u, hashy, sekretu TOTP i kodów odzyskiwania. Dane osobowe — nie udostępniaj odpowiedzi. Żadnych operacji finansowych. Tylko pojedynczy profile=dev.", "BankAccountDetail", "Konta bankowe DEV", token=True, failures=("400", "404", "503"), params=[parameter("id", "path", account_identifier, "Identyfikator rachunku.", True), parameter("profile", "query", {"type": "string", "const": "dev"}, "Wyłącznie DEV.", True)])},
        "/api/support/{id}": {"get": operation("problemSupport", "Diagnoza, instrukcja i plan dla powiadomienia", "Rozpoznane kody pochodzą z backendu, nie z tekstu logu. Odczyt nie zmienia plików. Plan DEV jest związany z rewizją i instancją; PROD/nieznane logi mają instrukcję bez automatycznej naprawy.", "SupportDetails", "Pomoc i naprawy", token=True, failures=("400", "404", "503"), params=[parameter("id", "path", identifier, "Identyfikator powiadomienia.", True)])},
        "/api/support/check": {"post": operation("checkProblem", "Sprawdź ponownie warunek techniczny", "Nie zmienia plików ani usług; zapisuje osobny wynik diagnostyki. Archiwum/kosz pozostają niezależne. Zgodność konfiguracji nie oznacza zdrowia usług. Dostępne tylko dla rozpoznanego DEV/TEST, poza koszem i bez aktywnego zadania.", "SupportDetails", "Pomoc i naprawy", token=True, failures=("400", "404", "409", "413", "415", "503"), body="SupportCheck")},
        "/api/config/files": {"get": operation("configurationFiles", "Lista i ustawienia plików DEV/TEST", "Stała lista: project.env/dev.env/monitoring dla DEV albo test.env dla TEST. Sekrety mają value=null. Odczyt nie tworzy plików. PROD i dowolne ścieżki są odrzucane.", "ConfigurationFiles", "Pliki i sekrety", token=True, failures=("400", "503"), params=[parameter("profile", "query", {"type": "string", "enum": ["dev", "test"]}, "Dokładnie jeden profil; bez innych parametrów.", True)])},
        "/api/config/files/save": {"post": operation("saveConfigurationFile", "Edytuj lokalny plik ustawień", "Token, confirmed=true i aktualna rewizja wszystkich czterech plików. values: tylko zmieniane pola; brak pola zachowuje wartość. remove usuwa wskazane pola. Istniejące hasła/klucze wymagają acknowledgeRisk=true. Zapis nie wykonuje SQL ani restartu; rotacja TOTP wymaga osobnej migracji. Znane pola project/dev mają jednego właściciela; błędne umieszczenie lub sprzeczne kopie blokują zapis. Limit tego endpointu: 32768 bajtów. Symlink/junction/hardlink i PROD są odrzucane. Zapis blokowany podczas zadania; kopie wycofania nie są trwałym backupem ani ochroną przed utratą zasilania.", "ConfigurationFiles", "Pliki i sekrety", token=True, failures=("400", "409", "413", "415", "503"), body="ConfigurationFileRequest")},
        "/api/config/files/reveal": {"post": operation("revealConfigurationSecret", "Jawny podgląd jednego sekretu", "Wymaga tokenu, potwierdzenia i aktualnej rewizji. Zwraca sekret celowo w value: odpowiedź jest wrażliwa, no-store. Interfejs ukrywa go po 45 sekundach/zmianie widoku; API nie może cofnąć skopiowanych danych. Nie zapisuje wartości w dzienniku. Tylko pliki wybranego DEV/TEST, nie PROD. Zablokowane podczas zadania.", "RevealedSecret", "Pliki i sekrety", token=True, failures=("400", "409", "413", "415", "503"), body="RevealSecretRequest")},
        "/api/config/dev": {
            "get": operation("devConfiguration", "Stan konfiguracji DEV", "Odczyt statusu project.env/dev.env i allowlisty pól; sekrety i rachunek nigdy nie są zwracane. Nie tworzy plików konfiguracji. Zapisane tożsamości baz i hasła są chronione.", "DevConfiguration", "Konfiguracja DEV", token=True, failures=("400", "503"),
                             params=[parameter("profile", "query", {"type": "string", "const": "dev"}, "Wymagane profile=dev; bez innych parametrów.", True)]),
            "post": operation("saveDevConfiguration", "Utwórz / uzupełnij pliki DEV", "Wyłącznie pola formularza, token i confirmed=true. Rewizja musi pasować do obecnych plików (409 przy zmianie). Puste sekrety zachowują wartość; brakujące hasła/API/TOTP są generowane. Nie rotuje istniejących prawidłowych haseł i kluczy TOTP. Zachowuje pozostałe ustawienia. Jedno zadanie naraz: zapis zablokowany podczas zadania. Zmiana portu aplikacji aktualizuje lokalny URL banku/callback sklepu. Nie wykonuje SQL, nie uruchamia Docker/JVM; potrzebne osobne kolejne kroki i restart aplikacji. Błąd drugiego zapisu wycofuje pierwszy; awaria procesu pomiędzy zapisami nie jest transakcją bazodanową, sprawdź wtedy oba pliki.", "DevConfiguration", "Konfiguracja DEV", token=True, failures=("400", "409", "413", "415", "503"), body="DevConfigurationRequest")},
        "/api/ping": {"get": operation("ping", "Dostępność panelu", "Zwraca ok i stałą nazwę aplikacji. Nie bada banku ani sklepu.", "Ok", "Panel")},
        "/api/bootstrap": {"get": operation("bootstrap", "Inicjalizacja interfejsu", "Zwraca token instancji, allowlistę operacji, historię zadań i lokalne linki dokumentacji. Nie uruchamia operacji. Dostęp localhost nie jest uwierzytelnianiem użytkownika.", "Bootstrap", "Panel")},
        "/api/openapi.json": {"get": operation("openapi", "Specyfikacja OpenAPI panelu", "Statyczny kontrakt z aktualną allowlistą operacji; bez tokenów, kont, logów i wartości sekretów.", "OpenApiDocument", "Panel")},
        "/api/status": {"get": operation("status", "Stan lokalnych usług", "DEV: sondy TCP, nie healthcheck. TEST/PROD: pusta lista i informacja o ograniczeniach, bez sond zdalnych usług.", "Status", "Diagnostyka", failures=("400",),
                                            params=[parameter("profile", "query", profile | {"default": "dev"}, "Widok panelu, nie przełączanie profilu aplikacji.")])},
        "/api/shop-accounts": {"get": operation("shopAccountList", "Konta sklepu DEV", "Tylko odczyt lokalnego shop-postgres należącego do Compose tego checkoutu. Lista klientów i administratorów, niezależna od banku, bez automatycznego łączenia po e-mailu. Do 50 kont, ID malejąco; next to before kolejnej strony. Wyszukiwanie tekstowe, nie SQL ani wzorzec LIKE. Wszystkie parametry pojedyncze, nieznane odrzucane. Dane osobowe: brak eksportu/utrwalania w panelu, haseł, hashy, tokenów i treści telefonu/adresu. Wymaga WSL, Docker i migracji sklepu; proces HTTP sklepu nie musi działać.", "ShopAccountPage", "Konta sklepu DEV", token=True, failures=("400", "503"), params=[parameter("profile", "query", {"type": "string", "const": "dev"}, "Wyłącznie DEV.", True), parameter("q", "query", {"type": "string", "maxLength": 128}, "Imię/nazwisko, e-mail, identyfikator klienta lub dokładne ID konta; bez znaków sterujących."), parameter("status", "query", {"type": "string", "enum": ["ALL", "ACTIVE", "INACTIVE"]}, "Aktywność konta; domyślnie ALL."), parameter("role", "query", {"type": "string", "enum": ["ALL", "CUSTOMER", "ADMIN"]}, "Rola konta; domyślnie ALL."), parameter("before", "query", account_identifier, "Pobierz ID mniejsze niż kursor.")])},
        "/api/shop-accounts/{id}": {"get": operation("shopAccountDetail", "Szczegóły konta sklepu DEV", "Tylko odczyt profilu, aktywności, roli, dat i flag obecności telefonu/adresu. Nie zwraca ich treści, hasła/hasha, tokenów, bank_account_number ani danych zamówień/płatności. Brak relacji tożsamości z bankiem. Nie zmienia kont ani ról. Tylko pojedynczy profile=dev.", "ShopAccountDetail", "Konta sklepu DEV", token=True, failures=("400", "404", "503"), params=[parameter("id", "path", account_identifier, "Dodatnie ID konta sklepu.", True), parameter("profile", "query", {"type": "string", "const": "dev"}, "Wyłącznie DEV.", True)])},
        "/api/accounts": {"get": operation("accounts", "Aktywne konta PLN DEV", "Lista do korekt salda, maksymalnie 500 kont; tylko kontener DEV tego checkoutu. Nie wykonuje korekty. Zawiera dane osobowe właścicieli: nie udostępniaj odpowiedzi.", "Accounts", "Salda DEV", token=True, failures=("400", "503"),
                                              params=[parameter("profile", "query", {"type": "string", "const": "dev"}, "Wymagane dokładnie jedno profile=dev; inne pola query odrzucane.", True)])},
        "/api/jobs": {
            "get": operation("jobs", "Lista zadań", "Do 60 zadań, najnowsze pierwsze; bez pola log. Token nie jest wymagany, ale kontrola Host/Origin obowiązuje.", "Jobs", "Zadania"),
            "post": operation("submitJob", "Uruchom dozwolone zadanie", "Jedno zadanie naraz. Tylko allowlista; żadnych poleceń shell/SQL/URL z requestu. Każda operacja PROD wymaga confirmed=true i productionConfirmation=PROD, także diagnostyka. Vault: lokalny HTTPS z CA projektu; token operatora (root odrzucany), udział unseal i hasła są writeOnly, tylko w pamięci zadania, nie w Job/logach. Udziały Shamir pojedynczo; brak init/seal/rekey i zapisu biznesowych KV. Finalizacja wymaga restarted=true. Zmiany DEV/TEST (także build/test) wymagają confirmed=true. Korekty PLN: DEV i parameters. Limit jobs: 8192 bajty. Wynik sprawdź przez GET /api/jobs/{id}.", "Job", "Zadania", token=True, code="202", failures=("400", "404", "409", "413", "415", "500"), body="JobRequest")},
        "/api/jobs/{id}": {"get": operation("job", "Szczegóły i log zadania", "Log jest maskowany i ograniczony. Odczyt nie wymaga tokenu. Brak rekordu: 404; ID niepasujące do wzorca także nie tworzy zasobu.", "JobDetail", "Zadania", failures=("404",),
                                               params=[parameter("id", "path", job_id, "ID zadania: 32 małe znaki hex.", True)])},
        "/api/cancel": {"post": operation("cancel", "Przerwij aktywne zadanie", "Zatrzymuje proces zadania i potomków, nie cofa zmian. Korekt cash-credit/cash-debit nie można anulować; żądanie jest odrzucane. Nieaktywne/nieznane zadanie: 400.", "Ok", "Zadania", token=True, failures=("400", "404", "413", "415", "500"), body="CancelRequest")},
        "/api/notifications": {"get": operation("notifications", "Lista powiadomień", "50 wpisów na stronę, malejąco po ID; next to kursor before następnej strony. total dotyczy wybranego stanu, counts wszystkich stanów. Odczyt może zaimportować nowe logi DEV do lokalnego SQLite, ale nie zmienia danych banku/sklepu. Profil widoku nie zmienia źródeł logów.", "Notifications", "Powiadomienia", token=True, failures=("400", "503"),
                                                   params=[parameter("view", "query", view | {"default": "active"}, "Aktywne, archiwum albo kosz; dokładnie jedna wartość."),
                                                           parameter("before", "query", identifier, "ID z next; zwracane są wpisy id < before. Inne parametry i duplikaty są odrzucane.")])},
        "/api/notifications/{id}": {"get": operation("notification", "Szczegóły powiadomienia", "Treść jednego zapisanego alertu, nie cały stack trace. Żadne parametry query nie są dozwolone.", "NotificationDetail", "Powiadomienia", token=True, failures=("400", "404", "503"),
                                                       params=[parameter("id", "path", identifier, "Dodatnie ID SQLite (maksymalnie 19 cyfr w ścieżce).", True)])},
        "/api/notifications/action": {"post": operation("changeNotification", "Archiwum, kosz i przywracanie", "archive/restore wymagają id i bool confirmed (może być false). delete wymaga id i confirmed=true. delete-active: confirmed=true, bez id; obejmuje wszystkie aktywne, także niewczytane, zachowuje archiwum. Usunięcie jest odwracalne. Restore z kosza odtwarza poprzedni stan. Ponowienie operacji na właściwym stanie zwraca changed=0. Nie usuwa plików logów i nie odwiesza płatności.", "NotificationChange", "Powiadomienia", token=True, failures=("400", "404", "413", "415", "503"), body="NotificationRequest")},
    }
    # Ping also includes the fixed application name in the actual handler response.
    paths["/api/ping"]["get"]["responses"]["200"] = response("Ping", "Serwer panelu odpowiada.")
    schemas["Ping"] = obj({"ok": boolean, "application": {"type": "string", "const": "novabank-control-center"}})
    schemas["ConfigurationEntry"] = obj({"key": text, "value": {"type": ["string", "null"]}, "secret": boolean, "configured": boolean, "editable": boolean}, allOf=[{"if": {"properties": {"secret": {"const": True}}}, "then": {"properties": {"value": {"type": "null"}}}}])
    schemas["ConfigurationFile"] = obj({"id": {"type": "string", "enum": ["project", "dev", "test", "monitoring"]}, "path": text, "exists": boolean, "size": count, "entries": array(ref("ConfigurationEntry"))})
    schemas["ConfigurationFiles"] = obj({"profile": {"type": "string", "enum": ["dev", "test"]}, "revision": {"type": "string", "pattern": "^[0-9a-f]{64}$"}, "files": array(ref("ConfigurationFile")), "note": text, "message": text}, required=["profile", "revision", "files", "note"])
    file_request = {"profile": {"type": "string", "enum": ["dev", "test"]}, "file": {"type": "string", "enum": ["project", "dev", "test", "monitoring"]}, "revision": {"type": "string", "pattern": "^[0-9a-f]{64}$"}, "confirmed": {"type": "boolean", "const": True}}
    schemas["ConfigurationFileRequest"] = obj(file_request | {"values": {"type": "object", "maxProperties": 128, "additionalProperties": {"type": "string", "maxLength": 2048, "writeOnly": True}}, "remove": {"type": "array", "maxItems": 128, "items": text}, "acknowledgeRisk": boolean}, additionalProperties=False)
    schemas["RevealSecretRequest"] = obj(file_request | {"key": text}, additionalProperties=False)
    schemas["RevealedSecret"] = obj({"key": text, "value": {"type": "string", "description": "Wrażliwa wartość: nie zapisuj w logach, URL ani udostępnianych dokumentach."}})
    return {"openapi": "3.1.0", "info": {"title": "NovaBank Control Center API", "version": "1.0.0",
            "description": "Lokalne narzędzie operatora; nie API banku ani sklepu. Nasłuch tylko 127.0.0.1. Host musi wskazywać localhost/127.0.0.1 i port panelu. Origin, jeśli podany, musi być równy http://Host; Sec-Fetch-Site: none/same-origin. Brak CORS. POST: token + JSON, do 4096 bajtów (edytor save: 32768). Zwykłe odczyty nie zwracają sekretów; osobny, potwierdzany endpoint reveal celowo zwraca jedną wrażliwą wartość. Nie wystawiaj przez LAN, proxy lub tunel. Sama dokumentacja nie zawiera tokenów ani danych runtime; nie oznacza zgody na uruchamianie operacji."},
            "servers": [{"url": "/", "description": "Bieżący lokalny serwer Control Center (dowolny port launchera)."}],
            "security": [], "tags": [{"name": name} for name in ("Panel", "Konfiguracja DEV", "Pliki i sekrety", "Diagnostyka", "Zadania", "Salda DEV", "Powiadomienia")],
            "paths": paths, "components": {"schemas": schemas, "securitySchemes": {
                "ControlCenterToken": {"type": "apiKey", "in": "header", "name": "X-Control-Center-Token",
                                       "description": "Token z GET /api/bootstrap, zmienia się po restarcie. Nie jest kontem/rolą użytkownika."}}},
            "x-control-center-actions": actions}
