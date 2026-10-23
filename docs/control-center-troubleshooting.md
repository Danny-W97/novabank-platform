# Control Center — rozwiązywanie typowych problemów

Stan: 2026-10-16. Te same instrukcje są dostępne po otwarciu powiadomienia w panelu. Każdy rozpoznany przypadek ma kod, kontekst profilu, opis skutków i — jeśli istnieje — odsyłacz do logu konkretnego zadania.

## Zasady wspólne

- Otwórz **Powiadomienia → wybrane zdarzenie → Diagnoza i rozwiązanie**. Uzgodnij profil przed wykonaniem operacji.
- Rozpoznanie pochodzi z kontrolowanego kodu backendu, nie z poleceń ani kodów wpisanych w treść logu. Stare i nieznane zdarzenia dostają instrukcję ogólną, bez automatycznej naprawy.
- „Sprawdź ponownie” sprawdza wyłącznie opisany warunek techniczny. Wynik jest osobny od archiwizacji/usunięcia komunikatu. Archiwizacja nie naprawia przyczyny.
- Operacje mają zwykłe potwierdzenia Control Center. Zmiana plików monitoringu ma dodatkowo plan i własne potwierdzenie. Niezapisany formularz blokuje przejście do naprawy; najpierw świadomie uzgodnij swoje zmiany.
- Ten moduł nie naprawia automatycznie PROD, bazy, TOTP, Vault, sald, przelewów ani płatności. Nie ponawiaj operacji finansowej tylko dlatego, że pojawił się błąd.
- Panel jest lokalnym narzędziem operatora; token instancji nie zastępuje kont operatorów ani kontroli ról. Nie udostępniaj serwera w LAN/Internecie.

<a id="schedule-runtime"></a>

## § schedule-runtime — SCHEDULE_CONFIGURATION_INVALID / SCHEDULE_UPDATE_FAILED / SCHEDULE_EXECUTION_FAILED

Instalacja timera, poprawność konfiguracji i ostatnie wykonanie to trzy odrębne wyniki. Otwórz **Ustawienia operacji → Sprawdź timery i historię**. „Jeszcze nie wykonano” nie jest błędem ani sukcesem backupu.

Przy błędnym katalogu nie twórz folderu z dosłownym `\x20`. Systemd musi wskazywać istniejący katalog projektu ze zwykłą spacją. Aktualizacja wymaga osobnego potwierdzenia, nie przerywa pracującej usługi i zachowuje kopie jednostek/stanu w `.novabank/control-center/web/timer-updates/<id>`. Nie instaluje drugiego timera CLI. Po błędzie/przerwaniu sprawdź obie jednostki i kopię przed ponowieniem; nie ma transakcji odpornej na zabicie procesu. Poprzednia błędna konfiguracja nie jest automatycznie uruchamiana w ramach cofnięcia.

Po pozytywnej kontroli konfiguracji wykonaj osobną próbę **Uruchom harmonogram backupu/DR teraz**. Sprawdź kod wyjścia, datę i log usługi. W Operacjach są osobne logi harmonogramów, a wykonania z panelu mają log w **Testy i logi**. Backup z retencją może usuwać stare kopie dopiero po udanym nowym backupie; sprawdź zakres. DR odtwarza dane w osobnych zasobach Docker, nigdy w bieżącej bazie aplikacji. Nie ponawiaj operacji finansowych w celu naprawienia nieudanego testu.

Timery są usługami użytkownika WSL; bez działającego WSL nie wykonają zadania. Stan `Linger=no` wymaga świadomego ustalenia pracy po zakończeniu sesji. Panel nie zmienia linger ani konfiguracji rozruchu automatycznie. Menu CLI nie musi być otwarte, ale serwer WWW uruchomiony z terminala pozostaje związany z tym procesem. Nie usuwaj współdzielonych skryptów zaplecza.

<a id="vault-ca"></a>

## § vault-ca — VAULT_CA_NOT_READY

1. Wybierz **PROD → Vault → Diagnostyka Vault i certyfikatów CA**, potwierdź ostrzeżenie i wpisz PROD. Odczyt pokazuje tylko publiczne odciski/daty i stan usługi. Nie wymaga tokenu Vault i nie odczytuje kluczy prywatnych/KV.
2. Panel ufa wyłącznie `.novabank/tls/vault/vault.crt`. Publiczne certyfikaty w `/opt/vault/tls` lub starej lokalizacji projektu są tylko porównywane; nie są automatycznie wybierane ani kopiowane.
3. Administrator powinien potwierdzić konfigurację aktywnego listenera `https://127.0.0.1:8200`, wpis IP SAN `127.0.0.1` w certyfikacie, datę ważności i właściwy łańcuch CA. Diagnostyka sprawdza tożsamość IP tak samo jak klient Vault Centrali; samo DNS `localhost` nie wystarcza. Brak dostępu do plików systemowych nie jest powodem do nadania szerokich uprawnień.
4. Nie używaj `VAULT_SKIP_VERIFY`, niesprawdzonego certyfikatu ani automatycznego restartu. Świadomą korektę CA/listenera/truststore wykonaj zgodnie z instrukcją Vault, potem ponów diagnostykę.
5. Pozytywny wynik TLS nie potwierdza poprawności AppRole, danych KV, baz ani startu aplikacji. Sekretów i tokenów nigdy nie publikuj w logu.

<a id="operator-settings"></a>

## § operator-settings — OPERATOR_SETTINGS_FAILED / TIMER_MIGRATION_UNCONFIRMED

`OPERATOR_SETTINGS_FAILED` oznacza błąd zapisu parametrów, a `TIMER_MIGRATION_UNCONFIRMED` — niepotwierdzone zakończenie migracji harmonogramu. Otwórz log konkretnego zadania, jeżeli jest dostępny; sam błąd nie oznacza przywrócenia wszystkich poprzednich ustawień systemd.

Ustawienia są w **Ustawienia operacji**, osobno DEV/TEST. Zapis nie przenosi kopii i nie zmienia istniejącego timera. Godziny dotyczą lokalnego czasu WSL/systemd, nie strefy przeglądarki. Po zapisie użyj osobnej operacji instalacji/aktualizacji harmonogramu i sprawdź jego status/log.

Katalog kopii musi być podkatalogiem `.novabank/backups` albo dedykowanym katalogiem `novabank-dev-backups[-nazwa]` poza projektem. Niepusty katalog bez znacznika własności nie jest przejmowany. Nie usuwaj znacznika, żeby ominąć konflikt. Stare kopie pozostają w poprzedniej lokalizacji; przeniesienie archiwum wymaga odrębnej procedury, nie zmiany pola w formularzu.

**Migracja timerów:** odśwież plan → sprawdź stare/nowe nazwy i katalog → potwierdź zakres. Panel rozpoznaje tylko jednostki użytkownika tego repozytorium bez drop-in i bez aktywnego backupu/DR. Kopia starych jednostek jest w `.novabank/control-center/web/timer-migrations/<id>`. Stary timer jest wyłączany, ale nie usuwany. Nowy timer jest instalowany i włączany; `Persistent` może uruchomić zaległe zadanie. Nie wykonuj równocześnie zmian przez CLI/inne narzędzie.

Jeżeli instalacja nie powiedzie się, panel próbuje cofnąć wyłącznie własne nowe jednostki i przywrócić poprzedni stan starego timera. Gdy nie potwierdzono cofnięcia lub działa nowe zadanie, **nie włączaj drugiego timera**. Sprawdź `systemctl --user status` dla wskazanych starych/nowych timerów i usług oraz kopię jednostek. Nie zatrzymuj trwającego backupu/DR w połowie. Nie ponawiaj w ciemno po timeout/kill panelu; sekwencja zmian systemd nie jest transakcją odporną na awarię procesu.

**Historia CLI:** import obejmuje końcówkę do 256 KB pliku `activity.log` (źródło do 16 MB), zachowuje do 500 poprawnych wpisów i deduplikuje kolejne importy. Oryginał pozostaje niezmieniony. Profil jest nieznany; to osobny dziennik, nie kolejka wykonywalnych zadań. Importy z większego/uszkodzonego/dowiązanego źródła wymagają ręcznej diagnostyki. Archiwum jest maskowane również przy odczycie.

<a id="shop-accounts-read"></a>

## § shop-accounts-read — SHOP_ACCOUNTS_READ_FAILED

Nieudany odczyt nie oznacza pustej bazy ani utraty klientów. Nie ponawiaj rejestracji i nie resetuj bazy.

1. Control Center uruchom w WSL: `bash ./scripts/start.sh`. Podgląd obejmuje tylko DEV, nie TEST/PROD ani zdalne bazy.
2. Sprawdź **Operacje → Status infrastruktury** i Docker. Wymagany jest działający `shop-postgres` z Compose tego checkoutu. Panel nie odczytuje kont sklepu z `bank-postgres` ani z kontenera innego repozytorium.
3. Sprawdź **Konfiguracja DEV / Pliki i sekrety**: `SHOP_POSTGRES_HOST`, `SHOP_POSTGRES_DB`, `SHOP_POSTGRES_USER`. Nazwy muszą odpowiadać istniejącej bazie. Zmiana hasła w pliku nie zmienia hasła PostgreSQL. Nie generuj nowego hasła w celu obejścia błędu.
4. Podgląd wymaga migracji sklepu, w tym tabeli `shop_users` i rozszerzenia profilu/adresu z V9. Jeśli brakuje schematu, uruchom sklep standardową procedurą stosującą migracje. Baza o gotowym schemacie może być odczytywana bez działającego procesu HTTP sklepu.
5. Wybierz **Konta sklepu → Odśwież konta sklepu**. Przy przeciążeniu poczekaj; 404 w szczegółach oznacza brak wskazanego ID, więc odśwież listę.

Instrukcja niczego nie naprawia automatycznie. Konta sklepu nie są rachunkami bankowymi; identyczny e-mail nie jest dowodem wspólnej tożsamości. Nie udostępniaj odpowiedzi API ani zrzutów rzeczywistych danych klientów.

<a id="accounts-read"></a>

## § accounts-read — ACCOUNTS_READ_FAILED

Nieudany odczyt **nie oznacza pustej bazy ani utraty kont**. Wróć do **Konta bankowe** dopiero po sprawdzeniu poniższych punktów.

1. Uruchom Control Center w WSL przez `bash ./scripts/start.sh`; przeglądarka może być w Windows. Odczyt działa tylko dla DEV, nie dla TEST/PROD lub zdalnej bazy.
2. Sprawdź Docker i **Operacje → Status infrastruktury**. Kontener `bank-postgres` musi działać i mieć etykiety Compose wskazujące ten checkout. Panel odmawia odczytu kontenera z innego projektu.
3. Sprawdź konfigurację DEV i migracje banku. Jeśli brakuje tabel/kolumn, uruchom bank standardową procedurą stosującą migracje. Zainicjalizowanej bazy nie resetuj i nie generuj w ciemno innych haseł.
4. Wybierz **Odśwież rachunki**. Odczyty mają limit czasu i ograniczoną współbieżność — po chwilowym przeciążeniu poczekaj. Szczegóły nieistniejącego rachunku zwracają 404; odśwież listę.

Panel nie zapisuje odczytanych danych rachunków w historii zadań/powiadomień. Nie udostępniaj odpowiedzi API ani zrzutów rzeczywistych danych. Ta instrukcja nie uruchamia SQL naprawczego, nie zmienia sald i nie odtwarza kont.

<a id="monitoring-metrics"></a>

## § monitoring-metrics — METRICS_CREDENTIALS_CONFLICT

Źródłem obowiązujących danych metryk DEV jest **istniejąca** para `METRICS_USERNAME` / `METRICS_PASSWORD` w `.novabank/env/dev.env`. Konflikt wykrywany przed przygotowaniem monitoringu nie wybiera sam hasła.

1. Otwórz powiadomienie i przeczytaj plan. Naprawa jest dostępna tylko w DEV, gdy istnieją wymagane pliki, poprawna para ustawień i dwa standardowe lokalne zadania Prometheusa.
2. Plan wskazuje zapis wyłącznie `.novabank/monitoring/.env.monitoring` i `.novabank/monitoring/prometheus.yml`. Nie zmienia `dev.env`, haseł Grafany, bazy ani działających procesów; nie tworzy nowego sekretu. Pozostałe ustawienia i zadania Prometheusa są zachowywane.
3. Zaznacz potwierdzenie zakresu i uruchom naprawę. Backend ponownie sprawdzi podpis planu oraz rewizję plików. Zmiana między podglądem a zapisem zatrzymuje operację.
4. Przed zapisem powstaje lokalna kopia **dwóch zmienianych plików**, pod `.novabank/control-center/web/repair-backups/<requestId>/`. Kopie zawierają sekrety; nie publikuj ich ani nie dodawaj do Git. Tworzone są z ograniczonymi uprawnieniami; na dysku Windows montowanym w WSL sprawdź także rzeczywiste ACL/uprawnienia montowania.
5. W **Testy i logi** sprawdź wynik zadania i ścieżkę kopii, bez wartości haseł. Wynik „konfiguracja zweryfikowana” oznacza zgodność plików, **nie przeładowanie i zdrowie usług**.
6. Świadomie przeładuj/restartuj zależne aplikacje i monitoring właściwą procedurą, a następnie sprawdź metryki i `targets UP`. Dla pojedynczego pliku bind-mount w Docker samo uruchomienie istniejącego kontenera może nie wczytać pliku po jego atomowej podmianie; może być potrzebne kontrolowane odtworzenie kontenera Prometheusa. Nie usuwaj wolumenów Grafany.

Ten sam identyfikator żądania zwraca to samo zadanie i nie powtarza zapisu. Po niepewnym wyniku sprawdź log i kopię; nową próbę uruchamiaj świadomie. Podpis jest związany z bieżącą instancją serwera. Po jej restarcie pobierz nowy plan.

Niestandardowy/odległy target, niezgodny port, niejednoznaczny YAML lub dowiązanie blokują plan — użyj ręcznej kontroli. Mechanizm zapisuje pliki przez podmiany i wycofuje wykryty błąd zapisu, lecz **nie zapewnia atomowości dwóch plików na wypadek utraty zasilania/zabicia procesu**. Po takim przerwaniu sprawdź oba pliki i kopie przed uruchomieniem usług. Przywracaj uzgodnioną parę, nie nadpisując późniejszych zmian operatora.

<a id="missing-configuration"></a>

## § missing-configuration — CONFIG_FILES_MISSING

1. DEV: otwórz **Konfiguracja DEV**, uzupełnij dane i zatwierdź przygotowanie `project.env` oraz `dev.env`.
2. TEST: użyj **Operacje → Przygotuj konfigurację TEST**. Nie kopiuj sekretów PROD/DEV do TEST.
3. Jeżeli pliki utracono, ale baza jest już zainicjalizowana, odzyskaj poprzednie dane dostępu. Wygenerowanie innego hasła w pliku nie zmienia hasła istniejącej bazy.
4. Wróć do powiadomienia i sprawdź ponownie. Kontrola potwierdza podstawową konfigurację, nie dostępność baz ani poprawność logowania. Utworzenie plików nie uruchamia usług.

<a id="stale-form"></a>

## § stale-form — CONFIG_REVISION_CHANGED

Zapis został odrzucony, aby nie nadpisać równoległych zmian. Nie wymuszaj zapisu starej rewizji. Wróć do formularza, uzgodnij niezapisane dane i dopiero świadomie użyj wczytania/odświeżenia. Nie kopiuj sekretów do czatu ani logu. Ponownie wprowadź potrzebne zmiany na aktualnej wersji. Panel nie potrafi stwierdzić, czy użytkownik uzgodnił swój szkic — ten przypadek pozostaje do ręcznej oceny.

<a id="build-required"></a>

## § build-required — BUILD_REQUIRED

Wybierz build wskazanego komponentu (bank/sklep), potwierdź operację i sprawdź jej log. Następnie użyj ponownego sprawdzenia. Kontrola szuka jednego JAR-a nie starszego od źródeł i `pom.xml`; nie bada poprawności binarnej, działania biznesowego ani zgodności całego środowiska. Build sam nie uruchamia aplikacji. Po sukcesie świadomie uruchom DEV.

<a id="port-in-use"></a>

## § port-in-use — PORT_IN_USE

Sprawdź komponent i port w powiadomieniu. W WSL użyj diagnostyki `ss -ltnp`; sprawdź też IntelliJ lub inną Centralę. Potrzebną działającą instancję wykorzystaj zamiast uruchamiać drugą. Proces zatrzymuj tylko w miejscu, które go uruchomiło, po ustaleniu jego tożsamości. Alternatywnie świadomie uzgodnij port i zależne adresy. Panel nie zabija obcych procesów. Ponowna kontrola to sonda TCP na lokalnym porcie, nie identyfikacja procesu ani healthcheck aplikacji.

<a id="manual-conflict"></a>

## § manual-conflict — CONFIG_CONFLICT_MANUAL

Otwórz właściwy formularz i powiązany log. Ustal źródło i właściciela ustawienia: `project.env` — niewrażliwa infrastruktura, `dev.env` — sekrety i ustawienia aplikacji DEV. Nie usuwaj sprzecznych wartości bez uzgodnienia. Zmiana hasła w env nie wykonuje `ALTER USER`; zmiana klucza szyfrowania TOTP może uniemożliwić odczyt istniejących sekretów. Klucze API wymagają uzgodnienia wszystkich klientów. Vault/TLS/PROD wymagają właściwej procedury administracyjnej. Nie rozwiązuj nierozstrzygniętej płatności korektą salda ani ponowieniem przelewu.

<a id="task-failed"></a>

## § task-failed — TASK_FAILED

Otwórz **log konkretnego zadania**. Sprawdź pierwszą przyczynę, profil i skutki poprzednich kroków. Błąd/przerwanie nie oznacza pełnego wycofania. Nie powtarzaj automatycznie płatności, transferu, rotacji ani odtworzenia. Po restarcie stara historia zadań może być niedostępna; powiadomienie pozostaje historycznym zdarzeniem, nie dowodem aktualnego stanu usługi.

<a id="task-busy"></a>

## § task-busy — TASK_BUSY

Nowa operacja nie rozpoczęła się, ponieważ trwa inne zadanie. Otwórz **Testy i logi**, poczekaj na wynik i oceń go przed ponowieniem. Nie przerywaj naprawy, rotacji ani operacji finansowej w połowie. Nie nadpisuj równoległych zmian konfiguracji.

<a id="write-failed"></a>

## § write-failed — CONFIG_WRITE_FAILED

Sprawdź uprawnienia i wolne miejsce. Nie zakładaj, że zapisano cały zestaw. Sprawdź log oraz dostępne kopie `.control-config-*` / `repair-backups`. Przywracaj tylko uzgodnione pliki, bez nadpisania nowszych zmian operatora. Przy niepełnym rollbacku przerwij dalsze operacje zmieniające konfigurację. Kopie zawierają sekrety; zgłaszając problem udostępniaj wyłącznie zamaskowany log.

<a id="unclassified"></a>

## § unclassified — UNCLASSIFIED_EVENT

Sprawdź źródło, czas, zamaskowaną treść oraz dostępny log zadania/aplikacji. Kod lub polecenie wpisane w logu nie uruchomi żadnej naprawy. Starsze zdarzenia nie są wstecznie uznawane za potwierdzoną diagnozę. Nieznany przypadek, płatność, baza, TOTP lub PROD wymagają diagnostyki i odpowiedniej procedury, nie przycisku „wymuś”.
