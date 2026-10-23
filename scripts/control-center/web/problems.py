"""Trusted problem codes and human runbooks. Never classify executable actions from logs."""
CATALOG = {
    "SCHEDULE_CONFIGURATION_INVALID": {
        "title": "Harmonogram ma niepotwierdzoną konfigurację systemd", "impact": "Włączony timer nie dowodzi poprawności katalogu roboczego i powiązanej usługi. Wykonanie może się nie rozpocząć.",
        "section": "schedule-runtime", "check": None,
        "steps": ["Otwórz Ustawienia operacji → Harmonogramy WWW i odśwież wynik konfiguracji oraz ostatniego wykonania.",
                  "Sprawdź katalog roboczy odczytany przez systemd, własność jednostek i log zadania. Nie twórz katalogu z dosłownym ciągiem \\x20.",
                  "Aktualizuj właściwy harmonogram osobną potwierdzoną operacją. Nie włączaj jednocześnie starego CLI.",
                  "Po weryfikacji konfiguracji uruchom osobną próbę przez usługę systemd i sprawdź wynik; instalacja nie jest testem backupu/DR."],
    },
    "SCHEDULE_UPDATE_FAILED": {
        "title": "Nie potwierdzono aktualizacji harmonogramu WWW", "impact": "Aktualizacja lub jej cofnięcie mogły wykonać się częściowo. Nie zakładaj, że timer jest gotowy.",
        "section": "schedule-runtime", "check": None,
        "steps": ["Otwórz log zadania i kopię .novabank/control-center/web/timer-updates wskazaną w wyniku.",
                  "Sprawdź oba pliki jednostek, stan usługi i timera. Nie przerywaj działającego backupu/DR i nie uruchamiaj starego timera CLI.",
                  "Po przerwaniu/timeout sprawdź konfigurację przed ponowieniem. Poprzednia błędna konfiguracja nie jest automatycznie uruchamiana po cofnięciu.",
                  "Zgodność plików, włączenie timera i sukces wykonania są odrębnymi wynikami."],
    },
    "SCHEDULE_EXECUTION_FAILED": {
        "title": "Ostatnie wykonanie harmonogramu nie powiodło się", "impact": "Ostatnia próba backupu/DR przez systemd nie zakończyła się potwierdzonym sukcesem.",
        "section": "schedule-runtime", "check": None,
        "steps": ["Otwórz Ustawienia operacji i sprawdź datę, kod zakończenia oraz konfigurację odpowiedniego harmonogramu.",
                  "W Operacje wybierz logi harmonogramu backupu lub DR; wynik zadania jest w Testy i logi.",
                  "Sprawdź dostępność lokalnego Docker, konfigurację DEV i raport. Nie odtwarzaj bieżącej bazy ani nie ponawiaj operacji finansowych.",
                  "Ponowienie wymaga osobnego potwierdzenia. Przed backupem z retencją sprawdź zakres starych kopii; DR działa wyłącznie na izolowanych zasobach."],
    },
    "OPERATOR_SETTINGS_FAILED": {
        "title": "Nie udało się zapisać ustawień operacji", "impact": "Nie potwierdzono zmiany katalogu/retencji/terminów. Istniejące timery i kopie nie zostały automatycznie zmienione.",
        "section": "operator-settings", "check": None,
        "steps": ["Wróć do Ustawienia operacji i sprawdź wynik zapisu. Nie wymuszaj starej rewizji.",
                  "Sprawdź uprawnienia i plik .novabank/control-center/web/operations.json. Nie usuwaj istniejących kopii ani znacznika katalogu.",
                  "Zapis formularza nie zmienia timerów. Aktualizację harmonogramu wykonaj osobno dopiero po potwierdzonym zapisie i sprawdzeniu starych timerów CLI."],
    },
    "TIMER_MIGRATION_UNCONFIRMED": {
        "title": "Nie potwierdzono migracji harmonogramu CLI", "impact": "Zmiana systemd mogła wykonać się częściowo. Sprawdź log i oba timery przed kolejną próbą.",
        "section": "operator-settings", "check": None,
        "steps": ["Otwórz log zadania i kopię timer-migrations wskazaną w wyniku.",
                  "Sprawdź stan starego i nowego timera oraz powiązanych usług przez systemctl --user status. Nie uruchamiaj drugiego harmonogramu i nie przerywaj aktywnego backupu/DR.",
                  "Panel próbuje przywrócić poprzedni stan tylko gdy potwierdził zatrzymanie własnego nowego timera. Po timeout/kill procesu kontrola administracyjna jest obowiązkowa.",
                  "Kopii baz nie przenoszono. Archiwizacja powiadomienia nie rozwiązuje stanu timerów."],
    },
    "VAULT_CA_NOT_READY": {
        "title": "Nie potwierdzono zaufanego TLS Vault", "impact": "Operacje Vault wymagają prawidłowego CA i lokalnego listenera HTTPS. Nie zmieniono zaufania ani usług.",
        "section": "vault-ca", "check": None,
        "steps": ["Otwórz log diagnostyki Vault/CA. Porównaj publiczne odciski certyfikatów i daty ważności.",
                  "Sprawdź administracyjnie listener https://127.0.0.1:8200 oraz publiczny CA .novabank/tls/vault/vault.crt. Kluczy prywatnych i tokenów nie kopiuj do logów.",
                  "Brak dostępu do /opt/vault/tls wymaga administratora. Panel nie uruchamia sudo chmod/cp ani restartu Vault.",
                  "Nie ustawiaj VAULT_SKIP_VERIFY i nie podmieniaj CA na niezweryfikowany certyfikat. Po świadomej naprawie powtórz diagnostykę z osobnym potwierdzeniem PROD."],
    },
    "SHOP_ACCOUNTS_READ_FAILED": {
        "title": "Nie można odczytać kont sklepu DEV", "impact": "Nie potwierdzono aktualnych danych użytkowników sklepu. To nie oznacza pustej bazy ani utraty kont.",
        "section": "shop-accounts-read", "check": None,
        "steps": ["Uruchom Control Center w WSL przez bash ./scripts/start.sh. Podgląd obejmuje tylko lokalne DEV, nie TEST/PROD.",
                  "Sprawdź Docker i Status infrastruktury. Wymagany jest shop-postgres z Compose tego checkoutu, nie bank-postgres ani inna baza.",
                  "Sprawdź Konfiguracja DEV, ustawienia SHOP_POSTGRES_* i migracje sklepu. Brakujące migracje stosuje standardowy start sklepu.",
                  "Nie resetuj bazy ani haseł i nie zmieniaj kont. Po usunięciu przyczyny wybierz Konta sklepu → Odśwież konta sklepu."],
    },
    "ACCOUNTS_READ_FAILED": {
        "title": "Nie można odczytać rachunków DEV", "impact": "Nie potwierdzono aktualnych danych rachunków. Nie oznacza to pustej bazy ani utraty kont.",
        "section": "accounts-read", "check": None,
        "steps": ["Uruchom Control Center w WSL przez bash ./scripts/start.sh. Podgląd nie obsługuje zdalnej bazy ani PROD.",
                  "Sprawdź Docker oraz Operacje → Status infrastruktury. Wymagany jest działający bank-postgres należący do tego checkoutu Compose.",
                  "Sprawdź Konfiguracja DEV i historię migracji banku. W razie braku tabel/kolumn uruchom bank standardową procedurą, która stosuje migracje.",
                  "Nie resetuj bazy, haseł ani sald. Po rozwiązaniu przyczyny wróć do Konta bankowe i użyj Odśwież rachunki. Nie wykonano naprawy ani operacji finansowej."],
    },
    "TASK_BUSY": {
        "title": "Inne zadanie jest w toku", "impact": "Nowa operacja nie została rozpoczęta.",
        "section": "task-busy", "check": None,
        "steps": ["Otwórz Testy i logi i poczekaj na wynik aktywnego zadania.",
                  "Nie przerywaj rotacji, naprawy plików ani operacji finansowej w połowie. Sprawdź wynik przed ponowieniem.",
                  "Gdy zadanie zakończy się, ponów świadomie pierwotną operację. Nie nadpisuj równoległych zmian konfiguracji."],
    },
    "METRICS_CREDENTIALS_CONFLICT": {
        "title": "Niezgodne dane dostępu do metryk DEV",
        "impact": "Prometheus może nie uzyskać dostępu do metryk banku lub sklepu.",
        "section": "monitoring-metrics", "check": "metrics",
        "steps": ["Sprawdź profil DEV i status konfiguracji. Wartości sekretów pozostają ukryte.",
                  "Zalecane źródło to istniejące dane METRICS_USERNAME i METRICS_PASSWORD w dev.env.",
                  "Wyświetl plan. Po potwierdzeniu panel skopiuje istniejące dane DEV do .env.monitoring i Basic Auth dwóch lokalnych zadań Prometheusa. Nie wygeneruje nowego hasła.",
                  "Zapoznaj się z wynikiem w Testy i logi. Zgodność plików nie dowodzi, że działające usługi przeładowały ustawienia.",
                  "Zrestartuj zależne aplikacje w odpowiednim miejscu i świadomie przeładuj/restartuj monitoring. Następnie sprawdź targets UP i dostęp do metryk."],
    },
    "CONFIG_FILES_MISSING": {
        "title": "Brakuje podstawowych plików konfiguracji", "impact": "Nie można rozpocząć wybranej operacji bez przygotowania profilu.",
        "section": "missing-configuration", "check": "configuration",
        "steps": ["Dla DEV otwórz Konfiguracja DEV, uzupełnij dane i zatwierdź utworzenie project.env oraz dev.env.",
                  "Dla TEST użyj Operacje → Przygotuj konfigurację TEST. Nie kopiuj haseł DEV ani PROD.",
                  "Jeżeli pliki utracono, a bazy już istnieją, odzyskaj poprzednie hasła z kopii. Nowe hasło w env nie zmienia hasła istniejącej bazy.",
                  "Wróć do powiadomienia i sprawdź ponownie. Utworzenie plików nie uruchamia usług."],
    },
    "CONFIG_REVISION_CHANGED": {
        "title": "Formularz zawiera nieaktualną wersję konfiguracji", "impact": "Zapis został odrzucony, aby nie nadpisać równoległej zmiany.",
        "section": "stale-form", "check": None,
        "steps": ["Nie ponawiaj zapisu z tą samą rewizją i nie wymuszaj nadpisania.",
                  "Wróć do otwartego formularza. Przed wczytaniem uzgodnij niezapisane zmiany; nie kopiuj sekretów do logów ani czatu.",
                  "Użyj Wczytaj ponownie/Odśwież i potwierdź odrzucenie zmian tylko świadomie. Następnie ponownie wprowadź potrzebne zmiany.",
                  "To powiadomienie jest historyczne. Nie można automatycznie stwierdzić, czy użytkownik uzgodnił własne niezapisane dane."],
    },
    "BUILD_REQUIRED": {
        "title": "Brak aktualnego artefaktu aplikacji", "impact": "Control Center nie uruchomi nieobecnego, niejednoznacznego lub starszego JAR-a.",
        "section": "build-required", "check": "build",
        "steps": ["Wybierz Build banku lub sklepu wskazanego w powiadomieniu i potwierdź standardową operację.",
                  "Sprawdź wynik i log konkretnego zadania. Niepowodzenie buildu nie jest naprawą problemu.",
                  "Sprawdź ponownie aktualność JAR-a. Sam build nie uruchamia banku ani sklepu.",
                  "Następnie świadomie uruchom aplikacje DEV. Dla zajętego portu użyj osobnej diagnostyki."],
    },
    "PORT_IN_USE": {
        "title": "Port aplikacji jest zajęty", "impact": "Nie uruchomiono kolejnej instancji; panel nie zabija obcego procesu.",
        "section": "port-in-use", "check": "port",
        "steps": ["Sprawdź, czy bank/sklep nie działa już w IntelliJ albo w innej Centrali.",
                  "W WSL możesz sprawdzić nasłuch poleceniem ss -ltnp. Port i komponent są wskazane w kontekście powiadomienia.",
                  "Jeżeli to potrzebna instancja, używaj jej zamiast uruchamiać drugą. Zatrzymuj proces tylko w miejscu, które go uruchomiło, po potwierdzeniu jego tożsamości.",
                  "Alternatywnie świadomie zmień port i powiązane adresy w konfiguracji. Panel nie zwalnia automatycznie portu i nie zmienia reguł sieciowych.",
                  "Sprawdź ponownie. Wolny port nie oznacza jeszcze działającej aplikacji."],
    },
    "CONFIG_CONFLICT_MANUAL": {
        "title": "Konfiguracja wymaga świadomego uzgodnienia", "impact": "Nie wybrano automatycznie jednej wartości i nie wymuszono zapisu.",
        "section": "manual-conflict", "check": None,
        "steps": ["Otwórz właściwy formularz DEV/TEST oraz szczegóły zadania, jeżeli są dostępne.",
                  "Ustal właściciela zmiennej i źródło obowiązującej wartości. dev.env zawiera sekrety DEV, project.env niewrażliwą infrastrukturę.",
                  "Nie generuj klucza TOTP dla istniejących danych 2FA i nie zmieniaj hasła istniejącej bazy jedynie przez edycję pliku.",
                  "Dla kluczy API uzgodnij bank, sklep i innych klientów. Dla Vault/TLS/PROD użyj osobnej instrukcji administracyjnej oraz potwierdzeń PROD.",
                  "Nieznane konflikty, błędy bazy oraz nierozstrzygnięte płatności wymagają diagnostyki. Nie naprawiaj ich korektą salda ani ponownym przelewem."],
    },
    "TASK_FAILED": {
        "title": "Zadanie wymaga sprawdzenia wyniku", "impact": "Błąd lub przerwanie nie oznacza, że wszystkie wcześniejsze kroki zostały cofnięte.",
        "section": "task-failed", "check": None,
        "steps": ["Otwórz log powiązanego zadania. Po restarcie stara historia może nie być już dostępna.",
                  "Sprawdź pierwszą przyczynę błędu, profil i skutek wcześniej wykonanych kroków.",
                  "Nie ponawiaj automatycznie przelewów, płatności, rotacji kluczy ani operacji odtworzenia danych.",
                  "Archiwizacja lub usunięcie komunikatu nie rozwiązuje przyczyny. Użyj właściwego kreatora lub procedury administracyjnej."],
    },
    "CONFIG_WRITE_FAILED": {
        "title": "Nie udało się zapisać konfiguracji", "impact": "Wymagana kontrola plików i ewentualnych kopii przed uruchomieniem usług.",
        "section": "write-failed", "check": None,
        "steps": ["Nie zakładaj, że oba pliki zostały zapisane. Sprawdź uprawnienia i wolne miejsce na dysku.",
                  "Sprawdź lokalne kopie .control-config-* lub repair-backups wskazane w logu. Nigdy nie publikuj ich zawartości.",
                  "Przywracaj uzgodniony zestaw plików, nie pojedynczy losowy plik. Nie nadpisuj nowych zmian innych operatorów.",
                  "Jeżeli rollback był niepełny, zatrzymaj dalsze operacje zmieniające konfigurację i zgłoś problem z zamaskowanym logiem."],
    },
    "UNCLASSIFIED_EVENT": {
        "title": "Powiadomienie bez potwierdzonej automatycznej diagnozy", "impact": "Treść logu nie upoważnia panelu do wykonania naprawy.",
        "section": "unclassified", "check": None,
        "steps": ["Sprawdź źródło, czas i zamaskowaną treść powiadomienia.",
                  "Jeżeli jest powiązane zadanie, otwórz jego log. Dla aplikacji sprawdź odpowiedni log banku/sklepu.",
                  "Nie wykonuj poleceń podanych w logu bez weryfikacji. Dla nieznanego przypadku panel udostępnia instrukcję, nie przycisk wymuszenia naprawy.",
                  "Dla spraw płatności, danych, TOTP lub PROD skorzystaj z diagnostyki i procedury administracyjnej."],
    },
}


class ProblemError(RuntimeError):
    def __init__(self, code, *, component=None, port=None):
        if code not in CATALOG:
            raise ValueError("Nieznany kod problemu.")
        self.issue_code = code
        self.context = {"component": component} if component in ("bank", "shop") else {}
        if type(port) is int and 1024 <= port <= 65535:
            self.context["port"] = port
        super().__init__(CATALOG[code]["title"])


def metadata(code=None):
    code = code if code in CATALOG else "UNCLASSIFIED_EVENT"
    item = dict(CATALOG[code])
    item.update(code=code, document="docs/control-center-troubleshooting.md", anchor=item["section"])
    return item
