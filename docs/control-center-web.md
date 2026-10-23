# NovaBank Control Center — panel lokalny

## Lista zadań (2026-10-16)

W **Testy i logi → Zadania** lista do 8 wpisów ma naturalną wysokość. Od 9. wpisu przewijanie działa wewnątrz listy, której wysokość odpowiada pierwszym 8 pełnym pozycjom (także przy zawijaniu nazw i zmianie szerokości okna). Nagłówek z licznikiem oraz podgląd logu nie należą do tego przewijanego obszaru. Dostępne są kółko myszy, suwak i klawiatura po ustawieniu fokusu na liście. Odświeżenie statusów zachowuje pozycję przewijania, o ile mieści się ona w aktualnej liście. Zmiana nie ogranicza pobierania ani nie usuwa historii zadań. Wystarczy odświeżyć stronę panelu, bez restartu serwera.

Weryfikacja: testy układu dla 0, 1, 7, 8, 9 i 30 pozycji, wielowierszowych nazw, zmiany rozmiaru i ukrycia widoku. W przeglądarce potwierdzono 30 zadań, wysokość 8 pozycji, przewijanie do końca oraz wybór starszego zadania bez powrotu na początek. Układ mobilny nie wychodzi poza szerokość ekranu; konsola bez błędów i ostrzeżeń. [Podgląd](control-center-job-list-scroll.jpg).

## Weryfikacja i aktualizacja harmonogramów WSL (2026-10-16)

Naprawiono generator `WorkingDirectory` dla ścieżek ze spacją, np. `Dysk D`. W tym polu zapisujemy rzeczywistą spację, nie dosłowne `\x20`. Sprawdzenie nie opiera się na wyglądzie pliku: backend odczytuje przez D-Bus katalog interpretowany przez systemd, porównuje go z checkoutem i sprawdza istnienie oraz powiązanie timer → usługa.

W **Ustawienia operacji → Harmonogramy WWW i migracja CLI** widoczne są osobno:

- instalacja jednostek oraz włączenie/aktywność timera;
- wynik kontroli konfiguracji;
- ostatnie wykonanie: jeszcze niewykonane / w toku / sukces / błąd / niepotwierdzone, z datami, kodem i następnym terminem.

Aktualizacja widoku: sekcja nazywa się **Harmonogramy i status wykonania**. Kafelki „Stare harmonogramy CLI” są ukrywane po potwierdzonym przejściu na WWW (również gdy pozostały wyłączone pliki CLI). Wracają tylko dla jednostek wymagających uwagi: dostępna migracja, włączony/aktywny dawny timer albo pozostała instalacja CLI bez odpowiednika WWW lub podczas pracy usługi. Pole `serviceRunning` obejmuje usługę CLI lub WWW, więc ten ostatni przypadek jest ostrożnościowym wskazaniem do kontroli, a nie dowodem pracy starej usługi. Niepełny lub nieudany odczyt pozostawia ostrzeżenie zamiast udawać zakończoną migrację. TEST/PROD nie pokazują tych kafelków. Zmiana dotyczy tylko widoku; nie usuwa jednostek, historii, kopii ani kontroli backendu. Wystarczy odświeżenie strony, bez restartu serwera.

Weryfikacja widoku 16.10.2026: wszystkie 7 zestawów testów JS zakończone powodzeniem. W działającym panelu potwierdzono dwie karty WWW, brak widocznej sekcji CLI i brak błędów/ostrzeżeń konsoli przeglądarki. [Zrzut po zmianie](control-center-schedules-without-cli.jpg). Nie uruchamiano ani nie zmieniano timerów.

`enabled/active` i domyślny kod `0` niewykonanej usługi nie są sukcesem backupu. Odczyt podczas zadania może pokazać wykonanie, ale nie tworzy planu migracji; podczas aktualizacji jednostek czeka na jej wynik. Błędy konfiguracji i wykonania tworzą deduplikowane powiadomienia z [instrukcją schedule-runtime](control-center-troubleshooting.md#schedule-runtime). Archiwizacja alertu nie naprawia jego przyczyny.

Instalacja/aktualizacja z WWW najpierw renderuje jednostki, zachowuje kopie i stan w `.novabank/control-center/web/timer-updates/<id>`, sprawdza rewizję/własność, zatrzymuje tylko timer i atomowo zastępuje poszczególne pliki. Następnie wykonuje `daemon-reload`, weryfikuje rzeczywistą konfigurację i dopiero włącza timer. Nie przerywa pracującej usługi. Przy błędzie próbuje przywrócić wyłącznie własne zmiany; nie uruchamia ponownie poprzedniej błędnej konfiguracji ani CLI. Nie gwarantuje atomowości dwóch plików i systemd na wypadek kill/awarii komputera — wtedy obowiązuje ręczna kontrola kopii i obu jednostek.

Próba **Uruchom zaplanowany backup/DR DEV** korzysta z rzeczywistej usługi systemd. Limity oczekiwania WWW wynoszą 31/91 minut, uwzględniając limity usług 30/90 minut i minutę bufora. Aktualizacji jednostek i rozpoczętej próby nie można anulować w połowie; łagodne zamknięcie panelu czeka na wynik. Retencja i DR zachowują dotychczasowy zakres i wymagają osobnych potwierdzeń.

Menu terminalowe nie musi być uruchomione. **Nie usuwaj współdzielonych skryptów zaplecza.** Nie zmieniamy automatycznie `linger` (przy wdrożeniu `no`) ani sposobu startu WSL. Zamknięcie WSL/wyłączenie komputera uniemożliwia wykonanie w tym czasie; `Persistent` może nadrobić zaległy termin. Serwer WWW uruchomiony z terminala nadal jest procesem, nie zainstalowaną usługą systemową.

Potwierdzone przebiegi, kopie i ograniczenia wdrożenia: [raport naprawy](control-center-schedule-repair.md). Niższe sekcje opisują również wcześniejsze etapy rozwoju.

## Ustawienia operacji i domknięcie migracji CLI → WWW (2026-10-16)

Nowa zakładka **Ustawienia operacji** zbiera parametry utrzymania środowiska, wykrywanie starych harmonogramów oraz osobny podgląd historii terminalowej Centrali. Rozszerzenie nie uruchamia migracji, backupu, retencji ani diagnostyki Vault przy samym starcie panelu. Nie przywraca PowerShell/Windows JDK jako ścieżki pracy — operacje środowiska uruchamiaj z WSL/Bash.

### Parametry i użycie

1. Wybierz DEV lub TEST i otwórz **Ustawienia operacji**. Pierwszy odczyt pokazuje wartości domyślne, bez tworzenia plików ustawień/katalogu backupów.
2. DEV: ustaw katalog backupów, retencję kopii, godzinę codziennego backupu, dzień/godzinę cotygodniowego DR i retencje artefaktów/tmp. TEST: wyłącznie retencje artefaktów/tmp, bez timerów i kopii DEV. Retencja raportów/logów/kopii: 1–3650 dni; tmp: 1–365 dni.
3. Potwierdź zapis. Parametry bez sekretów trafiają do `.novabank/control-center/web/operations.json`. Wymagane są token instancji, kontrola Host/Origin i aktualna rewizja; zmiana równoległa daje 409. Zapis jest zablokowany podczas zadania. Nie edytuj pliku równolegle innym narzędziem.
4. **Zapis nie aktualizuje istniejącego timera.** Następnie osobno zatwierdź instalację/aktualizację harmonogramu. Usługa systemd utrwala katalog i retencję z tej instalacji, nie odczytuje późniejszych zmian z otwartego formularza. Zwykłe operacje korzystają ze snapshotu parametrów przypisanego do zadania; zobacz go w jego logu.

Domyślnie: `.novabank/backups/dev`, kopie/raporty/logi 30 dni, tmp 7 dni, backup codziennie 02:00, DR w niedzielę 04:00. Godziny są **czasem lokalnym WSL/systemd**, nie przeglądarki. Timer wymaga działającego systemd użytkownika; `Persistent` może uruchomić zaległe zadanie po włączeniu. Zamknięcie przeglądarki nie wyłącza zainstalowanego harmonogramu.

Katalog kopii: podkatalog `.novabank/backups` albo dedykowany lokalny katalog WSL poza repozytorium o nazwie `novabank-dev-backups[-nazwa]`, np. `/mnt/e/novabank-dev-backups`. Bez `..`, dowiązań i znaków shell; nie można wskazać korzenia dysku, całego projektu ani dowolnego niepustego folderu. Przed operacją tworzony/sprawdzany jest znacznik `.novabank-dev-backup-owner.json`, wiążący lokalizację z checkoutem. Nieznany niepusty katalog nie jest przejmowany; wyjątkiem zgodności jest historyczna domyślna lokalizacja WWW `.novabank/backups/dev`. Zmiana ścieżki nie przenosi ani nie usuwa poprzednich kopii; nie usuwa się znacznika, aby wymusić dostęp.

Retencje artefaktów pozostają ograniczone do rozpoznanych raportów, rotowanych logów i wygenerowanych tmp — nie rozszerzają zakresu na kod, sekrety ani aktywne logi. Archiwizacja jest odzyskiwalna przez pliki archiwum; trwałe usuwanie nadal wymaga osobnego potwierdzenia i `USUN`. Alias „Archiwizuj stare raporty i logi” używa tej samej bezpiecznej operacji porządkowania, z ustawieniami wybranego profilu.

### Stare harmonogramy CLI — plan przed zmianą

**Sprawdź timery i historię** odczytuje znane jednostki użytkownika `novabank-backup` / `novabank-dr-test` i ich odpowiedniki WWW `novabank-web-dev-backup` / `novabank-web-dev-dr-test`. Instalacja drugiego timera jest blokowana, gdy stary jest aktywny/włączony lub trwa usługa. Nie przejmujemy nieznanych jednostek, drop-in, poleceń z innego checkoutu ani niepełnych par.

Przy gotowym planie wybierz **Pokaż plan migracji**, sprawdź nazwy, katalog, retencję i termin, a potem świadomie potwierdź. Panel kopiuje stare jednostki do `.novabank/control-center/web/timer-migrations/<id>`, ponownie sprawdza rewizję, wyłącza stary timer i instaluje/włącza nowy. Nie usuwa starych jednostek ani nie przenosi dumpów. Aktywnego backupu/DR nie zatrzymuje w połowie. Migracji nie można anulować przyciskiem zatrzymania zadania.

Po błędzie próbuje wycofać tylko jednostki utworzone przez tę próbę i odtworzyć poprzednie włączenie/aktywność CLI. Gdy działa nowa usługa albo nie potwierdzono własności/cofnięcia, nie włącza w ciemno drugiego harmonogramu i zgłasza problem do ręcznej kontroli. Systemd i zapis plików nie są jedną transakcją odporną na awarię procesu: po timeout/kill sprawdź obie pary jednostek, kopie i logi przed ponowieniem. Nie wykonuj równoległych zmian CLI/IDE. Instrukcja: [§ operator-settings](control-center-troubleshooting.md#operator-settings).

### Historia CLI i Vault/CA

**Importuj historię CLI** jest osobną, potwierdzaną operacją. Źródło `.novabank/control-center/activity.log` pozostaje nietknięte. Import obejmuje końcówkę do 256 KB źródła nie większego niż 16 MB, zachowuje do 500 poprawnych wpisów, maskuje dane i deduplikuje kopie. Podgląd jest oddzielony od zadań WWW, nie ma przycisku ponownego wykonania; profil historycznych wpisów jest nieznany. Archiwum: `.novabank/control-center/web/cli-history.json`.

W PROD dostępna jest nowa **Diagnostyka Vault i certyfikatów CA** (także Operacje → Vault), ze standardowym ostrzeżeniem i wpisaniem `PROD`. Pokazuje tylko publiczne odciski/daty, stan usługi i wynik TLS dla lokalnego Vault przez skonfigurowane `.novabank/tls/vault/vault.crt` oraz tożsamość IP `127.0.0.1`, tak samo jak istniejący klient Vault Centrali. Nie czyta tokenu, bootstrapu, kluczy prywatnych ani KV; nie wyłącza weryfikacji, nie kopiuje CA i nie restartuje usług. Certyfikaty w innych rozpoznanych lokalizacjach są wyłącznie porównywane. Sukces TLS nie oznacza gotowego AppRole/KV/aplikacji. Instrukcja: [§ vault-ca](control-center-troubleshooting.md#vault-ca).

Kontrakt API obejmuje **26 operacji HTTP i 90 dozwolonych akcji**. Dodano `GET/POST /api/operations/settings` oraz `GET /api/operations/legacy`, opisane w Dokumentacja → API Control Center. Rewizja planu migracji nie jest sekretem ani poleceniem i nie jest publikowana w historii zadań.

Aktywacja: zakończ zadania i sprawdź procesy DEV zarządzane przez bieżącą Centralę, zrestartuj ją poleceniem `bash ./scripts/start.sh`, odśwież stronę. Nie wymaga migracji baz banku/sklepu. Istniejące timery, kopie, ustawienia env i CA nie są automatycznie zmieniane. CLI nadal jest dostępnym trybem zgodności — nie został wyłączony w tym wdrożeniu.

Niższe datowane sekcje zachowują historię wcześniejszych wdrożeń; bieżące parametry utrzymania opisano powyżej.

Weryfikacja rozszerzenia: **208 zaliczonych testów Python/API/Bash, 11 opcjonalnych SQL pominiętych (219 łącznie)**; wszystkie **7 zestawów JS**, składnia i modele/odpowiedzi JSON Schema — OK. Przeglądarka na fikcyjnych danych potwierdziła zapis po potwierdzeniu, aktualny plan, izolację DEV/TEST/PROD i układ 390 px; konsola bez ostrzeżeń/błędów. Podglądy: [ustawienia](control-center-operator-settings-preview.jpg), [plan](control-center-cli-migration-plan-preview.jpg), [mobilny](control-center-operator-settings-mobile-preview.jpg). Zachowano motyw i nawigację istniejącej Centrali; weryfikację wykonano lokalnie, bez publikacji. Rzeczywista integracja systemd/Vault nie była uruchamiana, podobnie jak backupy, usługi i bazy użytkownika. Przed użyciem migracji wymagany test integracyjny w skonfigurowanym WSL. Szczegóły i granice: [raport wdrożenia](control-center-cli-parity.md).

## Konta sklepu — podgląd DEV (2026-10-15)

Pod **Konta bankowe** dodano osobną zakładkę **Konta sklepu**. Służy do odczytu zarejestrowanych klientów i administratorów NovaTech Shop, bez zapytań SQL wpisywanych przez operatora. Zachowuje obecną stylistykę Centrali, responsywną tabelę i kafelki szczegółów. Nie służy do tworzenia/usuwania kont, zmiany ról, hasła ani aktywności.

- Wyszukiwanie po imieniu/nazwisku, e-mailu, identyfikatorze klienta (`customer_id`) lub ID konta; tekst, nie SQL/wzorzec LIKE. Filtry: aktywne/nieaktywne i klient/administrator.
- Lista: imię/nazwisko (lub jawna informacja o nieuzupełnionym profilu), e-mail, identyfikatory, rola i aktywność. Stronicowanie po 50, ID malejąco, poprzednia/następna strona i ręczne odświeżenie. ID int64 są tekstem, bez zaokrągleń JavaScript; strony nie tworzą wspólnego zamrożonego snapshotu.
- Szczegóły: profil, daty utworzenia/aktualizacji i informacja, czy zapisano telefon/adres. Treść telefonu/adresu, hasło/hash, tokeny, zamówienia i płatności nie są pobierane do panelu. Data ostatniego logowania nie jest pokazywana — aktualny model `shop_users` jej nie przechowuje.
- **Konto sklepu nie jest rachunkiem bankowym.** Rejestracja sklepu tworzy własne `shop_users`; istniejąca historyczna kolumna `bank_account_number` jest opcjonalna i nie jest zwracana. Nie ma automatycznego łączenia kont po e-mailu. Źródła: `shop-app/src/main/java/org/demo/shopapp/auth/ShopUserRepository.java`, `ShopAuthService.java`, `shop-app/src/main/java/org/demo/shopapp/account/ShopAccountRepository.java` oraz migracje sklepu V3/V4/V8/V9.

Wymagania: panel w WSL/Bash, lokalny `shop-postgres` z Compose **tego checkoutu** i schemat sklepu po migracjach. Nie wystarcza działająca baza banku. Odczyt używa `SHOP_POSTGRES_*` z istniejącej konfiguracji DEV, lokalnego socketu Docker i sprawdzenia etykiet projektu/usługi/katalogu/pliku Compose. Nie ma dowolnego DSN ani przełączania na inną bazę. Aplikacja HTTP sklepu nie musi działać, jeśli schemat jest już gotowy. TEST/PROD są blokowane po stronie API i interfejsu.

Bezpieczeństwo: ustalone zapytania w `BEGIN READ ONLY`, `statement_timeout=5s`, `lock_timeout=1s`, najwyżej 51 rekordów (50 + kursor). Odczyty banku i sklepu współdzielą limit dwóch równoległych żądań. Używana jest istniejąca rola PostgreSQL DEV, nie nowa rola tylko do odczytu; ochrona zapisu opiera się na transakcji i stałych zapytaniach. Token instancji, kontrola Host/Origin i `no-store` pozostają wymagane. Panel nie zapisuje odczytanych danych w zadaniach/powiadomieniach ani localStorage; usuwa je z DOM po wyjściu, zamknięciu szczegółów lub zmianie profilu, ignorując spóźnione odpowiedzi. Fraza wyszukiwania znajduje się w URL żądania GET API, ale nie w adresie strony ani logach żądań panelu. Dane osobowe są widoczne dla operatora — nie udostępniaj panelu w sieci ani zrzutów rzeczywistych danych.

API: `GET /api/shop-accounts?profile=dev&q=...&status=ALL&role=ALL&before=...` i `GET /api/shop-accounts/{id}?profile=dev`. Wbudowana dokumentacja Control Center obejmuje teraz **23 operacje HTTP**. Nieudany odczyt ukrywa stare wyniki i zgłasza `SHOP_ACCOUNTS_READ_FAILED` z [instrukcją](control-center-troubleshooting.md#shop-accounts-read), nie udaje pustej bazy i nie uruchamia napraw SQL.

Weryfikacja: 181 zaliczonych testów Python/API z 192 (11 opcjonalnych SQL pominięto), a trzy nowe testy SQL uruchomione osobno na własnym jednorazowym PostgreSQL bez sieci i portów hosta — 3/3 OK. Wszystkie sześć zestawów JS i walidacja modeli/odpowiedzi OpenAPI przez JSON Schema — OK. Nowy zestaw JS sprawdza stronicowanie, precyzję ID, tekst zamiast HTML, brakujące dane, błędy, czyszczenie i spóźnione odpowiedzi. W przeglądarce z 63 fikcyjnymi kontami potwierdzono filtry roli/aktywności, kolejne strony, pusty wynik, nieuzupełniony profil, szczegóły i czyszczenie przy przełączeniu na TEST/PROD. Przy szerokości 390 px tabela przewija się we własnym kontenerze, a szczegóły mają jedną kolumnę; konsola bez ostrzeżeń/błędów. Nie odczytywano ani nie zmieniano rzeczywistych kont, plików env ani usług DEV. Testy biznesowe Java nie były częścią zmiany.

Aktywacja: po zakończeniu zadań i sprawdzeniu procesów DEV zarządzanych przez panel zrestartuj Control Center przez `bash ./scripts/start.sh`, następnie odśwież stronę. Nie jest wymagana nowa migracja aplikacji. Podglądy na danych demonstracyjnych: [lista](control-center-shop-accounts-preview.jpg) i [szczegóły](control-center-shop-account-detail-preview.jpg).

## Konta bankowe — podgląd DEV (2026-10-15)

Nowa zakładka **Konta bankowe** udostępnia listę rachunków i ich właścicieli bez ręcznego SQL. Wcześniejsza lista aktywnych rachunków PLN w formularzu korekty salda pozostała bez zmian. Podgląd nie tworzy kont ani nie zmienia ich statusów, sald lub uprawnień.

- Lista obejmuje rachunki ACTIVE/BLOCKED/CLOSED, z filtrem statusu i kodu waluty; właściciel, e-mail, maskowany numer, ID i saldo rachunku głównego. Nie jest to wykaz wszystkich użytkowników bez rachunku ani zestawienie portfela walutowego.
- Wyszukiwanie: imię/nazwisko, e-mail, dokładne ID rachunku/użytkownika lub fragment numeru (również ze spacjami/prefiksem PL). Fraza jest tekstem, nie SQL ani wzorcem `%`/`_`.
- **Szczegóły** pobierają pełny numer, statusy rachunku i użytkownika, rolę, flagi 2FA/weryfikacji e-mail oraz daty utworzenia, aktualizacji i ostatniego logowania. Brak daty logowania jest pokazany jawnie.
- Rachunki są stronicowane po 50, malejąco po ID, z przyciskami Poprzednie/Następne i ręcznym odświeżeniem. ID int64 i kwoty pozostają tekstem, bez zaokrągleń JavaScript. Strony nie są jednym zamrożonym snapshotem — równoległe zmiany w aplikacji mogą zmienić wyniki.
- Hasła/PIN-y, ich hashe, sekret TOTP, kody odzyskiwania, telefon i data urodzenia nie są zwracane. Dane listy/szczegółów nie są zapisywane w historii zadań, powiadomieniach, adresie strony ani localStorage; DOM jest czyszczony przy wyjściu i zmianie profilu. Żądania wyszukiwania GET zawierają frazę w URL API, ale panel nie loguje URL-i żądań. Dane osobowe pozostają widoczne dla lokalnego operatora — nie udostępniaj odpowiedzi/zrzutów ani panelu w sieci.

Wymagania: Control Center uruchomiony w **WSL/Bash**, działający `bank-postgres` z lokalnego Compose **tego checkoutu** oraz zastosowane migracje banku. Odczyt używa istniejących ustawień DEV i weryfikuje etykiety kontenera przez lokalny socket Docker, nie dowolny DSN. Bank jako proces HTTP nie musi działać, jeśli baza ma już właściwy schemat. TEST/PROD i nieznane/zdublowane parametry są odrzucane.

Backend wykonuje wyłącznie ustalone zapytania w `BEGIN READ ONLY`, z `statement_timeout=5s`, `lock_timeout=1s`, limitem 51 wyników (50 + kursor) i najwyżej dwoma równoległymi odczytami. Nie przyjmuje SQL, nazw tabel/kolumn ani adresów baz od przeglądarki. API wymaga tokenu instancji oraz poprawnego Host/Origin i zwraca `no-store`. Nie jest to osobny mechanizm kont/ról operatorów ani izolowany użytkownik PostgreSQL tylko do odczytu — połączenie używa istniejącej roli DEV, a ograniczenie zapisu realizuje transakcja i stałe zapytania.

Kontrakt: `GET /api/bank-accounts?profile=dev&q=...&status=ALL&currency=ALL&before=...` oraz `GET /api/bank-accounts/{id}?profile=dev`; opis w Dokumentacja → API Control Center. Po dodaniu kont sklepu kontrakt panelu obejmuje 23 operacje HTTP. Błąd odczytu ukrywa stare wyniki i tworzy bezpieczne powiadomienie `ACCOUNTS_READ_FAILED` z instrukcją [§ accounts-read](control-center-troubleshooting.md#accounts-read). Nie oznacza pustej bazy i nie uruchamia naprawy SQL.

Testy SQL na własnym jednorazowym PostgreSQL (bez sieci/portów hosta), wyłącznie z fikcyjnymi danymi: 3/3 OK — wyszukiwanie, filtry, stronicowanie, precyzja, maskowanie i odrzucenie zapisu przez transakcję. Dane i usługi DEV nie były odczytywane ani zmieniane w ramach tych testów. Pełny zestaw Python/API: 173 zaliczone, 8 opcjonalnych SQL pominiętych (181 łącznie); trzy nowe SQL uruchomiono osobno, pięciu wcześniejszych SQL nie ponawiano. Pięć zestawów JS i modele/odpowiedzi JSON Schema — OK. Testy biznesowe Java nie należały do tej zmiany.

W przeglądarce na osobnym serwerze z 63 fikcyjnymi rachunkami sprawdzono wyszukiwanie, pusty wynik, kolejne strony, szczegóły i wyczyszczenie danych przy zmianie na TEST/PROD. Przy szerokości 390 px tabela przewija się we własnym kontenerze, a szczegóły układają się w jedną kolumnę; konsola aplikacji bez ostrzeżeń i błędów. Zachowano istniejący motyw bordowo-złoty, dodając czytelny formularz, tabelę i kafelki szczegółów. Nie wykonano odczytu rzeczywistych rachunków DEV ani operacji finansowych.

Nową zakładkę aktywuje restart Control Center, nie banku. Zakończ zadania i sprawdź procesy DEV zarządzane przez panel przed jego zatrzymaniem. Podglądy z danymi demonstracyjnymi: [lista](control-center-accounts-preview.jpg), [szczegóły](control-center-account-detail-preview.jpg) i [widok mobilny](control-center-accounts-mobile-preview.jpg).

## Diagnoza i rozwiązanie z powiadomienia (2026-10-15)

Po otwarciu zdarzenia w **Powiadomieniach** dostępne są kod problemu, opis skutków, instrukcja krok po kroku z numerem sekcji dokumentu oraz log konkretnego zadania, jeśli jest dostępny. Katalog obejmuje brak konfiguracji, konflikt danych metryk DEV, nieaktualny formularz, brak aktualnego buildu, zajęty port, zadanie w toku, błąd zapisu i przypadki wymagające ręcznej diagnostyki. Nieznane/stare logi nie uzyskują automatycznie uprawnień do naprawy na podstawie ich treści.

MVP ma jeden nowy mechanizm naprawy plików: **uzgodnienie monitoringu z istniejącymi danymi metryk DEV**. Wymaga planu, osobnego potwierdzenia, zgodnej rewizji i lokalnej kopii dwóch zmienianych plików. Nie generuje hasła, nie zmienia `dev.env`, Grafany ani bazy, nie restartuje usług. Dla braku konfiguracji lub buildu panel kieruje do istniejącego kreatora/operacji ze standardowym potwierdzeniem. Inne przypadki mają instrukcję; PROD nie ma napraw w tym module.

„Sprawdź ponownie” zapisuje **osobny wynik diagnostyki**, bez archiwizacji/usunięcia zdarzenia i bez zmiany plików. Zgodność plików nie potwierdza przeładowania usług. Powtórzenie tej samej próby naprawy nie wykonuje ponownie zapisu. Panel chroni niezapisane formularze przed automatycznym odrzuceniem.

Dokładne kroki, ograniczenia, kopie i postępowanie po przerwaniu: [Rozwiązywanie typowych problemów](control-center-troubleshooting.md). Instrukcje są również wbudowane w panel — nie trzeba szukać plików, żeby przeczytać procedurę.

Weryfikacja tego rozszerzenia: **165 testów Python/API/Bash zaliczonych, 5 opcjonalnych PostgreSQL pominiętych (170 łącznie)** oraz cztery zestawy testów JavaScript — OK. Modele OpenAPI i odpowiedzi dla 10 kodów × 3 profile zweryfikowano lokalnym JSON Schema; kontrakt obejmuje 19 operacji HTTP i 85 dozwolonych akcji (naprawa nie jest zwykłą kartą Operacji). Testy obejmują plan/potwierdzenie, rewizję i podpis, powtórzenia UUID, rollback drugiego pliku, kopie bez niepowiązanych sekretów DEV, kontrolę profilu i ochronę HTTP, migrację starej bazy powiadomień, zachowanie formularzy i brak napraw na podstawie tekstu logu.

W przeglądarce, na osobnym serwerze i **wyłącznie tymczasowych danych demonstracyjnych**, sprawdzono plan, potwierdzenie, udaną naprawę, log oraz ponowną kontrolę bez archiwizacji. Konsola bez błędów. Podglądy: [plan](control-center-problem-plan-preview.jpg) i [wynik](control-center-problem-result-preview.jpg). Nie zmieniano rzeczywistych plików env, sekretów, powiadomień ani usług użytkownika. Nie potwierdzano rzeczywistego przeładowania Prometheusa/Grafany ani działania banku; testy biznesowe Java nie były częścią tej zmiany. Nowy backend wymaga ponownego uruchomienia panelu w WSL; przed zatrzymaniem bieżącej Centrali zakończ zadania i sprawdź, czy zarządza uruchomionymi procesami DEV.

## Uruchomienie jednym skryptem

Zalecany sposób pracy: **terminal WSL/Bash**, z katalogu projektu. Na Linuxie i macOS obowiązuje ta sama komenda:

```bash
bash ./scripts/start.sh
```

Przykład dla bieżącej lokalizacji repozytorium (cudzysłowy są potrzebne z powodu spacji w ścieżce):

```bash
cd "/mnt/c/Dysk D/stuff/Bank-demo"
bash ./scripts/start.sh
```

Adres: **http://127.0.0.1:18110**. Skrypt otwiera przeglądarkę Windows, jeśli dostępne jest `wslview` albo interop z `explorer.exe`; w każdym przypadku wypisuje adres panelu. Terminal WSL pozostaje uruchomiony jako serwer. Sam start panelu nie uruchamia banku ani kontenerów. Wymagany Python **3.10+ w WSL**, bez pip/npm i bez kompilacji frontendu. Nie instalujemy narzędzi automatycznie.

Opcje również podaje się w składni Bash:

```bash
bash ./scripts/start.sh --no-browser
bash ./scripts/start.sh --no-browser --port 18111
# Opcjonalnie: własny interpreter Python dostępny w WSL.
NOVABANK_CONTROL_PYTHON=/usr/bin/python3 bash ./scripts/start.sh
```

Poprzedni CLI: `bash ./scripts/start.sh --cli`. Bezpośrednie `bash ./scripts/control-center/control-center.sh` nadal otwiera menu terminalowe. Skrypty `.ps1` pozostają dla zgodności z wcześniejszą obsługą Windows, ale nie są zalecaną ścieżką uruchamiania środowiska. Nie wpisuj `./scripts/start.ps1` ani `.\scripts\start.ps1` do terminala WSL — wybierz skrypt `.sh`.

## Konfiguracja DEV — pliki i dane z poziomu Control Center

Ten rozdział opisuje **kreator pierwszego uruchomienia**, który nadal chroni istniejące hasła/klucze. Do świadomej edycji istniejących sekretów, ustawień Stripe i TEST służy nowa zakładka **Pliki i sekrety** opisana poniżej.

Wcześniej **Uruchom PostgreSQL i Redis**, **Buduj bank**, **Buduj sklep** i **Uruchom aplikacje DEV** były dostępne w Operacjach, ale nie było formularza danych. Nowa zakładka **Konfiguracja DEV** zbiera przygotowanie plików i kolejne kroki w jednym miejscu. Nie trzeba ręcznie tworzyć plików env w edytorze ani wracać do CLI dla standardowego lokalnego DEV.

1. Przygotuj WSL z Pythonem 3, JDK 25 i Docker Engine, następnie uruchom panel przez `bash ./scripts/start.sh`. Konfigurator nie instaluje narzędzi i nie wywołuje `sudo`.
2. Otwórz **Konfiguracja DEV**. Odczyt pokazuje istnienie `.novabank/env/project.env` i `.novabank/env/dev.env`; sam nie tworzy tych plików. Zapisane sekrety i pełny numer rachunku nie trafiają do odpowiedzi API ani do formularza.
3. Przy pierwszej konfiguracji uzupełnij dane baz: nazwa/użytkownik, lokalny host i port. Możesz podać początkowe hasło (minimum 16 znaków; litery/cyfry i `_+=./!:@%-`, bez spacji, `$`, cudzysłowów, backslash ani znaków sterujących) albo pozostawić puste, aby wygenerować losowe. **Jeżeli baza/jej wolumen już istnieje, użyj poprzednich poświadczeń. Wygenerowanie sekretu w pliku nie zmienia hasła ani nazwy bazy w PostgreSQL.** Nie usuwaj starych env, żeby „zresetować” dostęp.
4. Uzupełnij Redis, porty banku/sklepu oraz Actuator, a opcjonalnie dane monitoringu i rachunek sklepu. Wszystkie edytowane hosty muszą być `localhost` lub `127.0.0.1`; porty 1024–65535, różne od siebie. To walidacja konfiguracji, nie rezerwacja portów: aktualne zajęcie portu sprawdza się przy starcie aplikacji/diagnostyce.
5. Zaznacz potwierdzenie i kliknij **Utwórz pliki i zapisz dane** albo **Zapisz dane / uzupełnij braki**. Brakujące hasła baz/monitoringu i klucze API/TOTP są generowane przez `secrets`; klucz TOTP to 32 bajty Base64. Wypełniane są podstawowe ustawienia potrzebne dla obu aplikacji i Compose, bez kopiowania produkcyjnych plików. Nie zakłada się automatycznie kont klientów ani konta sprzedawcy. Rachunek sklepu musi być utworzony w banku; formularz sprawdza 26 cyfr, nie istnienie/status rachunku w bazie.
6. Po zapisie skorzystaj z przycisków **Kolejne kroki — bez terminala**: uruchom bazy/Redis → buduj bank → buduj sklep → uruchom aplikacje DEV. Każdy przycisk korzysta z istniejącej allowlisty i osobnego potwierdzenia. Zapis nie uruchamia żadnego z tych kroków automatycznie. Ich wynik/log pojawia się w **Testy i logi**. Przy niezapisanych zmianach albo niegotowej konfiguracji przyciski są nieaktywne. Nie wykonano nowych procedur automatycznej instalacji czy niszczenia kontenerów.

**Zachowanie istniejących ustawień:** puste pole sekretu/rachunku oznacza „zachowaj”, nie usunięcie. Istniejące prawidłowe hasła baz i monitoringu oraz klucze API/TOTP nie są rotowane. Nazwy i użytkownicy istniejącej konfiguracji baz także są chronieni po zapisaniu prawidłowego hasła; ich zmiana wymaga odrębnej procedury migracyjnej. Formularz nie umożliwia rotacji API/TOTP ani odczytu aktualnych haseł. Niepoprawny istniejący klucz TOTP jest zachowany i zgłaszany ostrzeżeniem, nie podmieniany. Wartości typu `change-me…` / `replace-with-…` / `generate-…` traktuje się jako placeholdery do pierwszego uzupełnienia. Jeśli użyto ich przy wcześniejszej inicjalizacji bazy, nie generuj nowych poświadczeń bez procedury zmiany/odtworzenia dostępu.

Nieznane pola i komentarze są zachowywane. Formularz ma zamkniętą listę pól, nie jest dowolnym edytorem `.env`. Nie dotyka TEST, PROD, Vault, certyfikatów ani `.env` w katalogu głównym. Od 2026-10-14 każda znana zmienna DEV ma jednego właściciela: `project.env` zawiera niewrażliwe hosty/porty/nazwy/użytkowników infrastruktury; `dev.env` zawiera hasła baz, klucze API/TOTP, monitoring i ustawienia aplikacji DEV. Compose wczytuje **oba pliki**. Zgodne stare kopie są porządkowane przy zapisie bez zmiany wartości; różne wartości blokują zwykły zapis. Jednorazowa migracja z jawnym wyborem DEV jest opisana w [raporcie](dev-env-deduplication-report.md). Zmiana portu banku/sklepu aktualizuje lokalne URL-e API/callback, nie zmienia samodzielnie działających procesów/kontenerów.

Zapis wymaga tokenu instancji, zaufanego Host/Origin, JSON, `confirmed=true`, widoku DEV i rewizji aktualnych plików. Zmiana przez inny formularz/edytor powoduje **409** — wczytaj dane ponownie. Zapis jest zablokowany podczas bieżącego zadania panelu. Nie edytuj tych samych plików równolegle przez CLI/IDE; rewizja wykrywa zmianę przed zapisem, nie zapewnia blokady dowolnego zewnętrznego programu.

Pliki są zapisywane przez etapowe pliki tymczasowe i `os.replace`, z próbą praw `0600`. Błąd drugiego zapisu wycofuje pierwszy do kopii sprzed operacji. To **nie jest transakcja odporna na utratę zasilania/kill procesu między dwoma rename**: po takiej awarii sprawdź oba env przed startem. Gdy wycofanie także zawiedzie, zachowane kopie `.control-config-*` w katalogu env wymagają ręcznego sprawdzenia; mogą zawierać sekrety. Nie commituj ich. Uprawnienia Windows/WSL na NTFS zależą też od ACL/ustawień montowania — `chmod` nie jest dowodem wyłącznego dostępu na każdym systemie.

Po zmianie plików uruchom ponownie zależne aplikacje. Dla banku/sklepu z IDE ustawienia procesu/EnvFile mogą mieć wyższy priorytet niż plik; ujednolić je ręcznie. Zmiana hasła w pliku nigdy nie wykonuje `ALTER USER` w PostgreSQL. Dla zmiany portów infrastruktury użyj istniejącej operacji Compose start/restart w zaplanowanym momencie. Sekrety nie trafiają do historii zadań ani zwykłego odczytu stanu; wyjątkiem jest jawnie potwierdzony endpoint podglądu sekretu w nowym edytorze. Wbudowany zapis DEV nie uruchamia shell z danymi użytkownika.

Aktywacja samej funkcji: restart serwera Control Center i odświeżenie strony, bez migracji baz ani automatycznego zapisu rzeczywistej konfiguracji. Procesy banku/sklepu z IDE pozostają niezależne; Ctrl+C zatrzymuje te uruchomione przez panel. Dawna akcja **Przygotuj konfigurację DEV** w interfejsie kieruje teraz do formularza; wywołana przez API zadań również tworzy bezpieczny podstawowy zestaw env bez rotacji istniejących kluczy.

## Zakres i środowiska

| Widok | Dostępne funkcje | Ograniczenia |
|---|---|---|
| DEV | Kreator DEV; edytor plików/sekretów; własne aplikacje; Compose; sesje Redis (status/unieważnianie); buildy i testy; E2E; monitoring; Stripe TEST; backupy/restore-test/DR i osobne harmonogramy; narzędzia; korekty PLN | Nie zatrzymuje IDE. Edycja pliku nie zmienia hasła istniejącej bazy ani hasła w istniejącej bazie Grafany. TOTP wymaga osobnej migracji. Bez kasowania wolumenów lub odtworzenia w bieżącej bazie aplikacji. |
| TEST | Tworzenie/edycja test.env, buildy, unit-tests oraz integration-tests/Testcontainers, diagnostyka i narzędzia, archiwizacja raportów, generator PDF, dziennik | Nie uruchamia osobnego banku TEST i nie zmienia kontenerów/kluczy DEV. Testcontainers może nadpisać endpointy z test.env. |
| PROD | Jawne procedury lokalnego Vault (status, udział unseal, AppRole, rotacja/finalizacja Secret ID, instalacja), bootstrap/mapa/przygotowanie, buildy PROD i lokalne keystore TLS; dziennik | Każde uruchomienie wymaga ostrzeżenia, checkboxa i wpisania PROD także po stronie API. Tylko HTTPS Vault 127.0.0.1:8200 z CA projektu. Bez root, init/seal/rekey, zdalnej produkcji, zapisu biznesowych KV, deploy/startu aplikacji i migracji haseł/TOTP. |

Profil to **widok i allowlista operacji**, nie przełączenie globalnego `SPRING_PROFILES_ACTIVE` lub baz aplikacji już uruchomionej. Od 2026-10-14 dopuszczono nazwane procedury lokalnego PROD-like z osobnym potwierdzeniem, nie ogólny terminal/edytor PROD. Operacja spoza profilu jest odrzucana przez serwer. Diagnostyka zakończona poprawnie oznacza wykonanie raportu — nie gotowość produkcji; raport może zawierać brakujące pliki.

Panel jest oddzielną aplikacją operatorską, nie stroną banku dla klientów i nie ekranem logowania Alior. Jasny motyw inspirowano podanym [widokiem Alior Mobile](https://www.aliorbank.pl/dodatkowe-informacje/bankowosc-elektroniczna/bankowosc-mobilna.html): biel, bordo i złoto; marka i monogram pozostają własne: NovaBank.

## Pierwszy start DEV

1. Otwórz **Konfiguracja DEV** w panelu, uzupełnij dostępne pola i zatwierdź utworzenie/zapis `project.env` oraz `dev.env`. Nie zmieniaj poświadczeń istniejącej bazy bez procedury migracji/odtworzenia.
2. Skontroluj stan plików i ostrzeżenia. Brakujące `BANK_API_KEY`, `TOTP_ENCRYPTION_KEY` i hasła zostały wygenerowane przy zapisie; istniejące prawidłowe wartości zachowano. Jeśli konto sprzedawcy jeszcze nie istnieje, utwórz je później w banku i wróć do formularza z jego numerem.
3. Uruchom PostgreSQL i Redis. Docker Engine i Compose muszą być dostępne w WSL, z którego uruchomiono panel.
4. Wykonaj **Buduj bank** i **Buduj sklep**. Build używa `-Punit-tests clean package`, nie profilu PROD. W zalecanym wariancie JDK 25 i Maven Wrapper działają w WSL, a panel korzysta z istniejącego silnika Bash.
5. **Uruchom aplikacje DEV** uruchamia aktualne JAR-y przez Java z wymuszonym `--spring.profiles.active=dev` i adresem `127.0.0.1`. Źródła nowsze od JAR-a lub zajęty port blokują start. Nie ma automatycznego builda, zabijania obcych procesów ani rotacji klucza API.
6. Sprawdź pulpit i otwórz bank/sklep. Start procesu nie oznacza zakończenia inicjalizacji Springa; błędy startu są w lokalnym logu aplikacji.

Java powinna pochodzić z **JDK 25 w WSL**. `JAVA_HOME` ma pierwszeństwo. Historyczny fallback Maven do Windows nie jest zalecanym wariantem i nie zastępuje Java potrzebnego do uruchomienia JAR-ów w WSL. Dane konfiguracji DEV importują same aplikacje według ich `application-dev.yaml`; panel nie wykonuje `source`/`eval` na plikach env i nie przekazuje sekretów w argumentach Java. Czyść potencjalnie niebezpieczne ustawienia w samych plikach DEV zgodnie z instrukcją projektu.

## Statusy, zadania i logi

- Pulpit pokazuje **otwarcie lokalnego portu TCP**. To nie potwierdza tożsamości procesu, zdrowia bazy ani gotowości biznesowej. Bank/sklep i porty baz są pobierane z konfiguracji DEV; Prometheus/Grafana mają domyślne lokalne porty 9090/3000. Zdalne hosty z konfiguracji nie są sondowane.
- Linki do banku i sklepu używają `localhost`, zgodnie z domyślnymi adresami integracji DEV; sam panel pozostaje pod `127.0.0.1`. Nie mieszaj `localhost` i `127.0.0.1` w sesji użytkownika banku, ponieważ cookie jest powiązane z hostem.
- Diagnostyka Compose sprawdza kontenery i healthchecki; Flyway odczytuje podsumowania, nie wykonuje `repair`. Redis odczytuje PING/liczbę kluczy, nie dane sesji.
- Przyciski operacji wywołują allowlistę identyfikatorów. Nie ma dowolnej komendy ani zdalnego terminala. Tylko formularz Konfiguracja DEV przyjmuje ograniczony zestaw danych początkowych; istniejących sekretów nie odczytuje do interfejsu.
- Jedno zadanie na raz. Serwer odrzuca równoległą operację kodem 409. Blokada pliku chroni przed dwiema instancjami panelu dla jednego repozytorium, także na różnych portach.
- Blokadę instancji sprawdzono w jednym systemie uruchomieniowym. Nie zweryfikowano wspólnej blokady między natywnym Windows a WSL — używaj jednego wybranego środowiska panelu, nie obu równocześnie.
- Operacje zapisujące wymagają potwierdzenia. Build/testy też zmieniają `target/`, a integracyjne tworzą kontenery. Automatyczne sondowanie i odświeżanie logów nie uruchamia tych operacji.
- Zadania mają timeout: standardowo 120 s, start/restart kontenerów 600 s, build/testy 1800 s. **Przerwij** zatrzymuje proces zadania i jego potomków w systemie serwera; nie cofa wykonanych zmian. Gdy używany jest fallback WSL → Windows Maven, nie gwarantujemy zakończenia wszystkich procesów po drugiej stronie granicy systemów — skontroluj je ręcznie.
- Historia i maskowane logi zadań: `.novabank/control-center/web/job-*.json`, maksymalnie 60 wyników i około 256 KB logu zadania. Usuwane są wyłącznie najstarsze **logi wygenerowane przez ten panel**, nie dane banku. Logi aplikacji: `bank-dev.log`, `shop-dev.log`, do 10 MB każdy, zastępowane przy kolejnym starcie tej aplikacji przez panel. Nie commituj katalogu runtime.
- Maskowanie obejmuje rozpoznane nazwy/wartości secretów z lokalnych env, tokeny, hasła w URL, klucze PEM, adresy email i ciągłe numery rachunków. Jest ochroną pomocniczą, nie dowodem, że dowolny log jest wolny od wszystkich danych osobowych; przed udostępnieniem logów sprawdź je ręcznie.
- Ctrl+C zamyka panel, przerywa zadania i zatrzymuje tylko jego własne procesy aplikacji DEV. Kontenery i procesy z IntelliJ pozostają. Zamknięcie karty przeglądarki nie wyłącza serwera. Po awarii procesu panelu nie przejmujemy automatycznie starych PID-ów; sprawdź pozostawione procesy ręcznie, żeby uniknąć zabicia procesu po ponownym użyciu PID.

## Dodawanie i odejmowanie środków — Operacje → Salda DEV

To **korekty testowego salda**, nie realizacja przelewu, wpłaty gotówki ani płatności sklepu. Dostępne są dwie operacje: **Dodaj środki do konta** i **Odejmij środki z konta**. Nie są dostępne w TEST ani PROD; serwer także odrzuca takie żądania.

1. Uruchom panel w WSL poleceniem `bash ./scripts/start.sh`. Baza musi być lokalnym, działającym kontenerem `bank-postgres` z głównego `docker-compose.yaml` tego checkoutu.
2. Bank musi mieć zastosowaną migrację **V25**. Flyway wykonuje ją przy następnym starcie aplikacji; panel sam nie wykonuje migracji.
3. Wejdź w **Operacje → Salda DEV**, wybierz dodawanie lub odejmowanie. Formularz pobiera aktywne konta PLN: właściciel, końcówka rachunku, ID i bieżące saldo. Pełny numer rachunku i email nie są wysyłane do formularza. Limit listy wynosi 500 kont; przekroczenie jest jawnie sygnalizowane.
4. Wybierz konto, podaj dodatnią kwotę od **0,01 do 999 999 999,99 PLN**, z maksymalnie dwoma miejscami po przecinku. Interfejs akceptuje przecinek lub kropkę, serwer przelicza wartość przez `Decimal`, bez używania zmiennoprzecinkowego typu dla księgowania.
5. Zaznacz potwierdzenie i uruchom korektę. Samo otwarcie formularza, wybór konta i wpisanie kwoty **nie zmieniają salda**.
6. Wynik pojawi się w **Testy i logi**: ID konta, saldo przed/po i identyfikator korekty. Bank odczyta nowe saldo przy następnym żądaniu; istniejąca strona klienta wymaga odświeżenia.

Zapis jest jedną transakcją PostgreSQL: blokada wiersza konta `FOR UPDATE`, kontrola `ACTIVE`/`PLN`, ochrona przed saldem ujemnym, aktualizacja `balance`, `version` i `updated_at` oraz zapis w **`dev_cash_adjustments`**. Zwiększenie wersji chroni także przed nadpisaniem nowego salda przez starszy obiekt JPA. Nie dodajemy fikcyjnego przelewu do historii klienta — to osobny audyt korekt operatorskich.

Identyfikator `requestId` (UUID) jest trwałym kluczem idempotencji audytu. Równoczesne lub późniejsze ponowienie **tych samych danych i UUID** zwraca zapisany wynik, bez drugiej zmiany salda. Ten sam UUID z innym kontem, kierunkiem lub kwotą jest odrzucany. Po błędzie sieci formularz zachowuje identyfikator; dla nieudanego/przerwanego zadania dostępne jest **Ponów tę samą korektę**. Timeout lub utrata odpowiedzi nie dowodzą, że nie doszło do zapisu — **nie twórz nowej korekty z nowym UUID, zanim nie sprawdzisz wyniku poprzedniej**.

Rozpoczętej korekty nie można anulować przyciskiem „Przerwij”; zamknięcie panelu także nie stanowi cofnięcia transakcji. Odczyt listy kont jest informacyjny: ostateczna kontrola salda odbywa się pod blokadą podczas zapisu. Brak środków, konto zablokowane lub nieistniejące nie powodują częściowego zapisu.

Serwer wymusza `docker --host unix:///var/run/docker.sock`, sprawdza etykiety Compose (projekt `bank-demo`, serwis `postgres`, katalog i dokładny plik Compose tego repozytorium) oraz odrzuca zdalny host bazy. Nie wykonuje SQL otrzymanego od przeglądarki. Kwota/ID/UUID są ściśle walidowane, a dopuszczone zapytania są stałe. Operator nadal odpowiada za to, żeby do lokalnej bazy DEV nie kopiować danych produkcyjnych.

## Powiadomienia — ostrzeżenia i błędy

Przycisk **Powiadomienia** znajduje się w górnym pasku, przed wyborem środowiska. Licznik pokazuje **aktywne wpisy**, nie liczbę nieprzeczytanych ani zawartość archiwum/kosza. Otworzenie listy niczego nie usuwa ani automatycznie nie archiwizuje. Zakładki **Aktywne**, **Archiwum** i **Kosz** mają osobne liczniki. Lista ma widoczny pasek przewijania, poziom Błąd/Ostrzeżenie, źródło i czas. Pierwsza strona wybranego widoku zawiera 50 wpisów; **Pokaż starsze** udostępnia kolejne. Kolejność odpowiada rejestracji wpisów w panelu.

### Szczegóły, archiwizacja i usuwanie

- Kliknięcie alertu otwiera **wysuwany panel z prawej strony**. Widać źródło, poziom, czas, ID, stan i pełną treść zapisaną w powiadomieniu (nie cały plik logu/stack trace). Długie treści przewijają się wewnątrz panelu; przyciski pozostają w jego stopce.
- **Archiwizuj** przenosi aktywny alert do archiwum i zmniejsza licznik aktywnych. **Przywróć z archiwum** przenosi go z powrotem na aktywną listę.
- **Usuń powiadomienie** wymaga potwierdzenia i przenosi jeden wpis do kosza. To usunięcie odwracalne, nie fizyczne kasowanie rekordu. **Przywróć z kosza** przywraca jego poprzedni stan: aktywny albo zarchiwizowany. W drugim przypadku można następnie przywrócić go z archiwum.
- **Usuń wszystkie aktywne** to przycisk ogólny pod listą. Po potwierdzeniu przenosi do kosza wszystkie wpisy aktywne w chwili wykonania, również te niewczytane przez paginację. **Archiwum pozostaje bez zmian**. Przycisk jest nieaktywny, gdy nie ma aktywnych alertów.
- Anulowanie potwierdzenia lub Escape nie usuwa wpisów. Panel i zakładki są obsługiwane także z klawiatury (Tab, Enter, strzałki, Home/End).
- Stan wpisu przetrwa restart. Zachowujemy jego fingerprint, dlatego ponowny import tego samego komunikatu z pliku lub archiwum logów **nie przywraca** usuniętego/zarchiwizowanego alertu. Nowe wystąpienie problemu, z nowym czasem/identyfikatorem zdarzenia, jest nowym aktywnym powiadomieniem.

Operacje dotyczą wyłącznie historii w lokalnym `notifications.sqlite`. **Nie usuwają plików logów, nie naprawiają przyczyny błędu, nie odwieszają sprawdzania płatności i nie zmieniają danych banku/sklepu.** Nie dodano trwałego opróżniania kosza. Serwer waliduje ID, operację i potwierdzenie, wymaga tokenu i zaufanego Origin także dla operacji zbiorczej. Stan i liczniki aktualizują się atomowo w SQLite; aplikacja nie nadpisuje istniejącej historii podczas migracji jej schematu.

Źródła:

- **Bank i Sklep:** nagłówki logów o poziomie `WARN`/`ERROR` z `.novabank/logs/bank-dev.log` i `shop-dev.log`, także gdy aplikację uruchomiono w IntelliJ. Dla sklepu z katalogiem roboczym `shop-app` obsługujemy też `shop-app/.novabank/logs/shop-dev.log`.
- **Control Center:** ostrzeżenia/błędy wyjścia zadań, nieudane/przerwane zadania oraz błąd odczytu kont DEV. Szczegóły pełnego zadania pozostają w zakładce **Testy i logi**.
- Znane archiwa rotowanych logów DEV (`bank-dev.YYYY-MM-DD.N.log.gz`, `shop-dev.YYYY-MM-DD.N.log.gz`) i maskowane logi aplikacji uruchomionych przez panel. Kopie tego samego datowanego komunikatu z konsoli i pliku są deduplikowane.

Collector działa w serwerze co około 2 sekundy, **także bez otwartej karty przeglądarki**. Interfejs odświeża licznik co 5 sekund. Historia i kursory odczytu są w lokalnym **`.novabank/control-center/web/notifications.sqlite`**, zachowywanym między restartami panelu. Dane są maskowane przed zapisaniem i wyświetleniem; bloki kluczy prywatnych i nadmiernie długie wiersze są pomijane. Archiwizacja i kosz są ręczne; nie ma automatycznego usuwania wpisów ani potwierdzania ich przeczytania.

**Wymagany jest restart banku i sklepu z profilem DEV**, żeby nowy zapis plikowy zaczął działać. To działa niezależnie od tego, czy proces wystartował z panelu, czy IDE. Nie odczytujemy bezpośrednio konsoli IDE, logów PROD, wszystkich plików dysku ani dowolnego wskazanego katalogu. Konfiguracja DEV rotuje logi przy 10 MB, z historią 7 okresów i limitem 100 MB na aplikację. Usuniętych wcześniej plików nie da się odzyskać; wyjątek całkowicie przemilczany przez program również nie tworzy wpisu. Przy braku logów panel jawnie pokazuje ten stan zamiast udawać działającą telemetrię. Sama zmiana widoku na PROD nie zmienia źródła powiadomień: nadal są to **lokalne logi DEV i zadania panelu**.

## Scheduler płatności — zawieszenie po trzech nieudanych sprawdzeniach

Zmiana dotyczy **`PaymentReconciliationJob` w sklepie**: odczytu statusu płatności kartą albo autoryzacji bankowej. To nie scheduler przelewów cyklicznych banku i nie ponowne wykonanie obciążenia.

- Pierwsze i drugie nieudane sprawdzenie: `WARN`, zwiększenie `shop_orders.reconciliation_failures`, zapis `reconciliation_last_error` i `payment_reconciled_at`. Kolejne sprawdzenie jest możliwe dopiero po skonfigurowanym interwale.
- Trzecie kolejne nieudane sprawdzenie: ustawienie **`reconciliation_suspended_at`**, licznik 3 i `ERROR` z markerem **`RECONCILIATION_SUSPENDED`**. Zamówienie nie będzie wybierane w kolejnych przebiegach. Błąd pojawi się w Powiadomieniach po odczycie logu sklepu.
- HTTP 404, inne błędy HTTP/timeouty oraz niezgodna lub nierozpoznana odpowiedź są nieudanym sprawdzeniem. W szczególności **404 nie dowodzi odrzucenia płatności**.
- Poprawny wynik sprawdzenia aktywnego zamówienia zeruje licznik kolejnych błędów i ostatnią przyczynę. Poprawne `PENDING` to także udane sprawdzenie, nie błąd.
- **Nie zmieniamy statusu zamówienia na FAILED tylko z powodu trzech błędów.** Nie cofamy potwierdzonej płatności, nie wysyłamy ponownie obciążenia i nie generujemy nowego klucza płatności. Brak rozstrzygnięcia nadal może blokować kolejną płatność kartą w koszyku — to ochrona przed podwójnym obciążeniem, nie coś, co należy omijać przez automatyczne odrzucenie.

Liczniki są atomowo zapisywane w osobnej transakcji `REQUIRES_NEW`, nie tylko w pamięci. Restart sklepu nie zeruje prób i nie odwiesza zamówienia. Wymagana jest migracja sklepu **V12**. Niedostępność bazy uniemożliwiająca zapis licznika jest osobnym `ERROR`; wtedy nie można obiecać zawieszenia po trzech przebiegach, dopóki baza nie zacznie przyjmować zapisów. Wiele instancji schedulera może już mieć pobrane zamówienie — zawieszenie wyłącza kolejne wybory, nie anuluje wywołania już będącego w toku.

Po zawieszeniu operator powinien sprawdzić referencję/idempotency key i faktyczny wynik po stronie banku. **Nie dodano automatycznego odwieszania ani przycisku odrzucenia nierozstrzygniętej płatności.** Ewentualne ręczne rozstrzygnięcie/odwieszenie wymaga najpierw ustalenia wyniku obciążenia; nie traktuj korekty salda DEV jako sposobu naprawienia stanu zamówienia.

## Aktywacja zmian z 2026-10-11

**Aktualizacja zarządzania alertami** (panel boczny, archiwum i kosz): wystarczy restart serwera Control Center i ponowne otwarcie/odświeżenie strony. Schemat lokalnego SQLite aktualizuje się automatycznie, zachowując dotychczasowe wpisy jako aktywne. Nie wymaga nowej migracji banku ani sklepu. Uwaga: Ctrl+C nadal zatrzymuje aplikacje, jeśli zostały uruchomione przez tę instancję panelu; w takim przypadku trzeba uruchomić je ponownie. Procesy z IDE pozostają.

Poniższe kroki z migracjami V25/V12 dotyczą wcześniejszego dodania korekt salda i zawieszania schedulera:

Zatrzymaj tylko własny serwer panelu `Ctrl+C`, następnie uruchom ponownie w WSL:

```bash
cd "/mnt/c/Dysk D/stuff/Bank-demo"
bash ./scripts/start.sh
```

Uruchom ponownie bank i sklep w IDE (profil DEV), aby zastosowały konfigurację logów i migracje V25/V12. Jeśli korzystasz z JAR-ów, najpierw wykonaj **Buduj bank/Buduj sklep**, a następnie restart ich własnych procesów. Nie uruchamiaj drugiego banku/sklepu na portach zajętych przez IDE. Samo odświeżenie strony nie aktualizuje działającego serwera Python ani klas Springa.

## Dokumentacja API — Swagger i OpenAPI

W zakładce **Dokumentacja** są trzy odrębne karty:

| Karta | Domyślny adres | Zakres |
|---|---|---|
| Swagger banku | `http://localhost:8080/swagger-ui/index.html` i `/v3/api-docs` | API NovaBank / SpringDoc |
| Swagger sklepu | `http://localhost:8081/swagger-ui/index.html` i `/v3/api-docs` | API NovaTech Shop / SpringDoc |
| API Control Center | `http://127.0.0.1:18110/docs/api` i `/api/openapi.json` | Własne API lokalnego panelu Python |

Bank/sklep muszą działać, żeby otworzyć ich Swagger. Linki są udostępniane **tylko w widoku DEV**; port pochodzi z `BANK_APP_PORT` / `SHOP_APP_PORT`, najpierw `project.env`, z pierwszeństwem `dev.env`. Niepoprawny port wyłącza link zamiast tworzyć dowolny URL. Host jest zawsze `localhost`, protokół HTTP; nie dodano proxy do banku/sklepu, CORS ani linków do zdalnego PROD. Link sam nie sprawdza portu ani aktywnego profilu procesu. Po zmianie portów odśwież całą stronę panelu. TEST nie tworzy osobnych aplikacji; PROD nadal oznacza diagnostykę lokalnych plików. Istniejąca konfiguracja Security banku i sklepu wymaga `ROLE_ADMIN` dla Swagger/OpenAPI w profilu `prod`; poza `prod` dopuszcza dostęp bez logowania. Tych reguł nie zmieniano.

Dokumentacja panelu działa na tym samym porcie co serwer, również po uruchomieniu z `--port`. Specyfikacja **OpenAPI 3.1** obejmuje **12 ścieżek / 14 operacji HTTP**:

- dostępność, bootstrap, specyfikację i stan usług;
- odczyt oraz potwierdzony zapis konfiguracji DEV (`/api/config/dev`);
- odczyt kont PLN DEV oraz uruchamianie dozwolonych zadań, w tym korekt salda;
- listę/szczegóły zadań i ich przerywanie;
- listę/szczegóły alertów oraz archiwizację, kosz, przywracanie i usuwanie zbiorcze.

Katalog `/docs/api` pozwala wyszukać endpoint, rozwinąć opis i obejrzeć parametry, modele JSON oraz odpowiedzi HTTP. Modele rozwijają także odwołania do innych modeli. Dokładna granica ID `int64` jest zachowana w wyświetlanym kodzie, bez zaokrąglenia przez JavaScript. Formularze/zasady zadań są generowane z rzeczywistej allowlisty `ACTIONS`; pozostałe schematy opisano jawnie w `scripts/control-center/web/openapi.py` i objęto testami. Przy zmianie endpointów trzeba aktualizować kontrakt — nie jest to automatyczny skaner funkcji Python.

**Katalog panelu nie jest Swagger UI i nie wykonuje operacji („Try it out”).** Pobiera wyłącznie publiczny kontrakt, bez bootstrapu/tokenu, danych kont i logów. Nie potrzebuje pip/npm, internetu ani CDN. JSON można otworzyć lub zaimportować do kompatybilnego narzędzia OpenAPI. Uruchomienie żądań z zewnętrznego narzędzia nie omija tokenu, limitu jednej operacji, potwierdzeń, walidacji ani ograniczeń profilu. Swagger banku/sklepu ma własną możliwość wykonywania żądań — używaj jej świadomie, zwłaszcza dla operacji zmieniających dane.

Kontrakt rozróżnia odczyty wymagające `X-Control-Center-Token` (konta, powiadomienia i konfiguracja DEV) od publicznych lokalnych odczytów (bootstrap, status, zadania, kontrakt). Każdy POST wymaga tokenu instancji i JSON: limit standardowy 4096 bajtów, `/api/jobs` 8192, zapis edytora plików 32768. Wszystkie zasoby zachowują weryfikację Host/Origin/Sec-Fetch-Site, CSP i `no-store`. W specyfikacji nie ma rzeczywistych tokenów/sekretów ani danych użytkowników. Modele parametrów PROD mają `writeOnly`; nie są zwracane w historii zadań. Kontrakt zawiera obowiązkowe `productionConfirmation="PROD"` i `confirmed=true` dla profilu PROD.

Aktywacja: zrestartuj własny serwer Control Center (`Ctrl+C`, potem w WSL `bash ./scripts/start.sh`) i odśwież stronę. Nie są potrzebne nowe migracje ani restart banku/sklepu. Uwaga: jeżeli panel uruchomił ich procesy, Ctrl+C zgodnie z dotychczasowym zachowaniem zatrzymuje także te procesy; aplikacje z IDE pozostają.

## Zabezpieczenia i granice

Serwer nasłuchuje tylko na `127.0.0.1`. API sprawdza Host, Origin i Sec-Fetch-Site. Zapis wymaga losowego tokenu instancji i JSON; token nie trafia do URL ani logu. CSP, brak CORS i zakaz osadzania w ramce ograniczają wywołania z obcych stron. Serwowane są tylko jawnie wskazane pliki frontendu, katalog API, specyfikacja i dwie instrukcje; nie ma udostępniania katalogu repozytorium, `.env`, źródeł Python ani runtime. Polecenia mają statyczne argumenty, brak `shell=True` i brak wejścia interaktywnego.

To **nie jest** system uwierzytelniania wieloużytkownikowego. Inny proces/użytkownik na tej samej maszynie, mający dostęp do localhost, może otworzyć panel. Nie wystawiaj przez proxy, tunel, LAN lub publiczny adres. Wpisanie PROD jest potwierdzeniem operatora, nie jego uwierzytelnieniem ani RBAC. Nowe funkcje są dla edukacyjnego/local PROD-like, nie do wystawienia centrali prawdziwego banku. Administrator musi utrzymać rozdzielenie danych/poświadczeń. Zdalny Docker/Vault i dowolne polecenia pozostają zablokowane; ograniczamy ścieżki, nie zastępujemy ochrony konta systemowego. Dla rzeczywistej produkcji potrzebne są m.in. uwierzytelnienie operatorów, role/uprawnienia, HTTPS panelu i zewnętrzny audyt.

Nie przeniesiono z Mini Order Platform AI/chatu, Chaos Engineering ani eksploratora danych biznesowych: w banku nie są częścią zleconego panelu. Procedury Vault/AppRole, bootstrap/TLS i build PROD zostały dodane 2026-10-14. Poza panelem pozostają inicjalizacja Vault, konfiguracja jego listenera HTTPS/CA, wydanie tokenu operatora przez administratora, interaktywne hasło sudo, zapisy biznesowych KV i migracje poświadczeń/danych. Instalacja JDK/Docker w CLI także była kontrolą wymagań, nie automatycznym instalatorem.

## Vault i jawne procedury PROD — 2026-10-14

W **Operacje** jest osobny kafelek **HashiCorp Vault** oraz filtr **Vault**, widoczny także z DEV/TEST. Kafelek prowadzi do widoku PROD, nie uruchamia żadnej procedury. W PROD dostępne są status, pojedynczy udział Shamir, konfiguracja polityk/AppRole, rotacja Secret ID banku/sklepu/obu, finalizacja banku/sklepu/obu i instalacja pakietu Vault w WSL. Pozostałe kategorie: **Środowisko PROD**, **Build PROD**, **TLS PROD**. Każda karta ma oznaczenie PROD.

Każde faktyczne zadanie PROD — także odczyt statusu — wymaga checkboxa i wpisania dokładnie `PROD`. Backend wymusza to niezależnie od UI; zmiana dropdownu sama niczego nie uruchamia. Przewodnik jest nawigacją, nie zadaniem. Formularze przyjmują ukryte wartości wyłącznie dla bieżącej operacji. Token/udział/hasło nie są zapisane w Job JSON, logach, URL, localStorage ani trwałej kolejce. Pola czyszczą się po anulowaniu/zamknięciu, przekazaniu do zadania i przejściu karty w tło. Python nie zapewnia kryptograficznego zerowania wszystkich kopii stringów w pamięci — nie jest to ochrona przed administratorem komputera/debuggerem.

### Wymagania Vault

- Serwer Vault jest wcześniej przygotowany na `https://127.0.0.1:8200`; panel nie przyjmuje dowolnego URL, namespace, polityki lub polecenia. Proxy i przekierowania HTTP są wyłączone. Timeout pojedynczego HTTP: 8 s.
- CA jest w `.novabank/tls/vault/vault.crt`; TLS jest sprawdzany, bez `skip_verify` i bez obchodzenia ostrzeżeń przeglądarki.
- Shamir: podaj **jeden udział** i powtarzaj z innym, aż Vault osiągnie swój rzeczywisty próg. Panel nie ustala arbitralnie 3/5 i nie przechowuje udziałów. Auto-unseal/recovery/migracja seal nie są obsługiwane.
- Do AppRole używaj krótkotrwałego tokenu operatora, nie tokenu aplikacji i **nie root**. Panel sprawdza polityki przez lookup-self i odrzuca root. Przykład minimalnej polityki: `infra/vault/policies/control-center-operator.hcl`. Administrator Vault musi ją zatwierdzić/zainstalować oraz wydać odpowiedni token poza panelem. Nie przyznawaj jej aplikacjom bank-prod/shop-prod.
- Setup ustawia role bank-prod/shop-prod i ich polityki **tylko odczytu KV**, zgodnie z dotychczasowym CLI: token TTL 1 h/max 24 h; Secret ID TTL 24 h i 5 użyć. Każdy test logowania zużywa jedno użycie ID. Nie tworzy biznesowych sekretów ani magazynu KV; odpowiednie dane muszą już istnieć w `secret/data/Bank-demo/prod` / `secret/data/shop-app/prod`.

### Rotacja i finalizacja

1. **Rotuj Secret ID**: sprawdzenie operatora i sealed → odczyt Role ID i poprzedniego accessora → wygenerowanie nowego ID → test AppRole/login i odczytu KV → unieważnienie tokenu testowego → zapis bootstrap + stanu poprzedniego accessora z kontrolą rewizji i wycofaniem plików po błędzie.
2. **Operator restartuje aplikację PROD** z IDE/udokumentowanej procedury i potwierdza jej poprawne działanie. Panel nie wykonuje deploy/startu PROD.
3. **Finalizuj rotację**: dodatkowy checkbox restartu/działania → ponowny test nowego ID → lookup/destroy poprzedniego accessora → wyczyszczenie stanu po sukcesie. HTTP 403/5xx nie są traktowane jako wygaśnięcie i nie usuwają stanu. Jawny brak 404 może oznaczać wcześniejsze wygaśnięcie. Niezakończona rotacja blokuje kolejną.

Jest to rotacja **Secret ID AppRole**, nie kluczy API/TOTP, root tokenu ani udziałów unseal. Dla „obu aplikacji” kroki są kolejne, nie jedną transakcją Vault; po błędzie sklepu wcześniejszy sukces banku pozostaje w logu i bootstrap. Timeout może nastąpić po zmianie po stronie Vault — sprawdź jego audyt przed ponowieniem. Błąd wycofania plików zostawia chronione `.control-config-*`; gdy bootstrap mógł już wskazywać nowy ID, panel nie unieważnia go na ślepo. Napraw stan administracyjnie przed kolejnymi krokami. Nie wykonuj równocześnie rotacji z CLI/innego narzędzia. Procedury Vault/TLS nie mają przycisku „Przerwij w połowie”; zamknięcie panelu/awaria procesu nadal może przerwać je w nieznanym stanie. Po takiej awarii sprawdź Vault, bootstrap i historię przed ponowieniem.

### Bootstrap, build i TLS

Bootstrap tworzy/uzupełnia `.novabank/env/prod.bank.env` i `prod.shop.env`. Istniejące poświadczenia pozostają; inny Vault/role są odrzucane, nie nadpisywane. Opcjonalne pola pozwalają uzupełnić brakujące Role ID/Secret ID i hasło istniejącego `vault-truststore.p12`; rotacja AppRole może uzupełnić ID. Nie tworzy CA/truststore ani KV. Mapa pokazuje tylko ścieżki, bez czytania plików. Kontrola przygotowania sprawdza pola/pliki i sealed, nie gotowość baz ani poprawność hasła truststore.

Buildy używają `-Pprod -DskipTests clean package` jak CLI; są to **artefakty, nie deployment**. Nadpisują `target`, co może wymagać późniejszego buildu DEV. Keystore generuje `keytool` dostępny w WSL: RSA 3072, PKCS12, lokalny self-signed CN/SAN localhost/127.0.0.1, 825 dni. Hasła są w środowisku procesu przez `-storepass:env`/`-keypass:env`, nie w argv. Pierwsza operacja odrzuca istniejące keystore; odnowienie zachowuje chronione `.previous` i blokuje kolejne nadpisanie tej kopii. Hasła keystore trzeba ustawić w właściwych KV Vault administracyjnie; panel ich tam nie zapisuje. **Nie są to certyfikaty publicznej produkcji.** Instalacja Vault używa oficjalnego repozytorium HashiCorp i `sudo -n`; panel nie przyjmuje hasła administratora systemu.

Zachowane granice: edytor **Pliki i sekrety** nadal obejmuje tylko DEV/TEST. Dopuszczenie jawnych procedur PROD nie otwiera dowolnego edytora PROD ani zarządzania root/unseal/rekey, a tokeny aplikacji nadal mają tylko odczyt.

Źródła protokołu: [unseal](https://developer.hashicorp.com/vault/api-docs/system/unseal), [AppRole](https://developer.hashicorp.com/vault/api-docs/auth/approle), [polityki ACL](https://developer.hashicorp.com/vault/api-docs/system/policies), [keytool JDK 25](https://docs.oracle.com/en/java/javase/25/docs/specs/man/keytool.html). Implementacja ma stałą allowlistę, nie przekazuje dowolnych opcji tych API do przeglądarki.

## Dodatkowe odpowiedniki CLI

- **Interaktywny przewodnik uruchomienia**: kafelek w Narzędziach, osobne kolejne kroki DEV/TEST/PROD. Nic nie uruchamia się automatycznie.
- **Konfiguracje IntelliJ**: raport nazw konfiguracji i ścieżek EnvFile, bez zmiany IDE i bez wartości sekretów.
- **Porządkuj raporty, logi i tmp**: odzyskiwalna archiwizacja raportów surefire/failsafe/test-results i JVM, rotowanych logów `.gz/.zip` oraz rozpoznanych wygenerowanych plików `tmp`. Retencje z **Ustawienia operacji** danego profilu (domyślnie 30/7 dni). Archiwum pod `.novabank/control-center/web/artifacts-archive/<job>`.
- **Trwale usuń wygasłe artefakty**: ten sam stały zakres, osobne potwierdzenie i wpisanie `USUN`. Usuwa pliki, nie katalogi; **bez odzyskiwania z panelu**. Env/sekrety/certyfikaty/źródła/aktywne logi/historia są wyłączone; symlink/junction/hardlink odrzucane. To nie jest szeroki terminalowy `find` całego repozytorium; parametry dni są ściśle walidowane.
- **PL/EN**: nawigacja, nazwy operacji i podstawowe przyciski/etykiety. Opisy techniczne, logi i instrukcje pozostają w języku źródła, co panel jawnie wyjaśnia. Persistowana jest wyłącznie preferencja języka, nigdy sekrety. Nazwy zmiennych i potwierdzenia PROD/USUN nie są tłumaczone.

Aktywacja nowych operacji: zatrzymaj własny panel i uruchom go ponownie w WSL: `bash ./scripts/start.sh`, potem Ctrl+F5. Nie restartowano ani nie zmieniano Vault, baz lub sekretów podczas wdrożenia. Ctrl+C panelu zatrzymuje również należące do niego aplikacje DEV, nie aplikacje z IDE.

## Pliki i sekrety — edytor DEV/TEST

Uruchom ponownie serwer panelu i odśwież stronę. Otwórz **Pliki i sekrety**, wybierz widok DEV lub TEST, a następnie plik:

- DEV: `.novabank/env/project.env`, `.novabank/env/dev.env`, `.novabank/monitoring/.env.monitoring`;
- TEST: `.novabank/env/test.env` (może być wygenerowany przez **Operacje → Przygotuj konfigurację TEST**).

Lista pokazuje ścieżki, obecność, rozmiar i liczbę ustawień. Nie trzeba szukać plików w IDE. To edytor **klucz/wartość**, nie dowolny edytor tekstu ani menedżer całego dysku. Komentarze i nieedytowane linie pozostają. Można dodać pole z podpowiedzi, zmienić istniejące albo zaznaczyć **Usuń pole**. Dopuszczone są lokalne ustawienia aplikacji/monitoringu, bez zmiennych shell, wyboru PROD, Vault/TLS ani wykonywania treści pliku. Nie obsługujemy wieloliniowych wartości, poleceń shell ani certyfikatów.

Edytor pokazuje każde ustawienie w osobnym kafelku: nazwa pola, oznaczenie „Ustawienie” lub „Sekret”, wartość i własne akcje. Stonowane szaro-beżowe tło oraz granice kafelków oddzielają pola; sekrety mają dodatkowy złoty akcent, a pola tylko do odczytu — szary. Liczba kolumn dostosowuje się do szerokości panelu; na telefonie pozostaje jedna. Opcje **Pokaż na 45 s** i **Usuń pole** są zachowane. Zaznaczenie usunięcia wyróżnia wyłącznie dany kafelek; do zapisu można je cofnąć. Zmiana układu nie zmienia wartości ani zasad zapisu plików.

Nad kafelkami są filtry **Pokaż wszystko / Sekrety / Ustawienia** z licznikami. „Ustawienia” oznacza pola nieoznaczone jako sekret, także te tylko do odczytu. Filtry dotyczą wybranego pliku i nie zmieniają kolejności pól w pliku ani ich wartości. Ukryte kafelki zachowują niezapisane zmiany i zaznaczenia usunięcia; zapis obejmuje również te zmiany, a licznik informuje o ukrytych zmienionych polach. Nowo dodane pole wchodzi od razu do odpowiedniej grupy; jeśli bieżący filtr je ukrywa, pojawia się komunikat. Brak pasujących pól ma własne wyjaśnienie. Przełączenie filtra ponownie maskuje ujawnione sekrety i nie pozwala spóźnionej odpowiedzi podglądu przywrócić ich widoczności. Wpisane przez operatora wartości pozostają w formularzu. Wczytanie plików ponownie lub zmiana widoku/profilu resetuje filtr do „Pokaż wszystko”.

Sekrety domyślnie mają pusty formularz i `value=null` w odpowiedzi listy. Brak zmiany/puste pole sekretu zachowuje zapisany sekret; usunięcie wymaga jawnego zaznaczenia. **Pokaż na 45 s** wymaga dodatkowego potwierdzenia i zwraca wyłącznie wskazany sekret przez chroniony endpoint POST, bez logowania jego wartości. Interfejs ukrywa go po 45 sekundach, zmianie widoku/profilu lub przejściu karty w tło. Nie ma localStorage, kopiowania do schowka ani umieszczania wartości w URL. Jeśli użytkownik sam skopiuje/udostępni wartość, panel nie może jej cofnąć. To narzędzie localhost bez kont operatorów — inne procesy/użytkownicy tej maszyny mogą uzyskać dostęp; nie wystawiaj panelu do LAN/proxy/tunelu.

Zapis wymaga potwierdzenia i aktualnej rewizji. **Istniejące hasła i klucze można zmienić**, ale trzeba dodatkowo zaznaczyć rozumienie skutków. **Zapis zmienia pliki, nie wykonuje ALTER USER, nie migruje zaszyfrowanych sekretów TOTP i nie restartuje aplikacji.** Zmiana TOTP bez migracji może uniemożliwić odszyfrowanie dotychczasowych danych 2FA. Zmiana danych bazy bez równoległej procedury PostgreSQL może odciąć dostęp aplikacji. Własne API/TOTP ustawiasz w edytorze; oddzielna operacja **Wygeneruj nowy klucz API DEV** generuje losowy klucz, nie zachowuje starego i wymaga restartu banku oraz sklepu.

Znane pola można tworzyć i zmieniać wyłącznie w ich docelowym pliku; błędne umieszczenie jest odrzucane z nazwą właściwego pliku. API nie synchronizuje już kopii project/dev. Zgodne stare kopie są mechanicznie przenoszone do właściciela; nowe konflikty wstrzymują zapis bez wybierania wartości. „Usuń pole” dotyczy ustawienia właściciela, nie służy do migracji pojedynczej kopii. Zmiana portów aktualizuje lokalny URL API/callback; porty muszą się różnić. Metryki mają źródło w `dev.env`; osobna kopia konsumowana przez monitoring pozostaje synchronizowana z `.novabank/monitoring/.env.monitoring`. Potem uruchom **Przygotuj monitoring DEV**, żeby odświeżyć Prometheus, i zrestartuj zależne procesy. Nie jest to rotacja hasła zapisanej bazy Grafany.

TEST dopuszcza wyłącznie pola TEST_* występujące w `application-test.yaml` banku/sklepu. Panel przekazuje je jako środowisko procesów testów Maven, bez sekretów w argumentach. Adresy są lokalne, nazwy baz `bank_test`/`shop_test`. To nie oznacza tworzenia takich baz ani uruchamiania stałych aplikacji TEST. W testach integracyjnych Testcontainers/DynamicPropertySource może nadpisać te ustawienia. Testy z widoku DEV nie importują test.env. Nie przekazujemy pól DEV/PROD do testów.

Weryfikacja podziału DEV (2026-10-14): **147 testów zaliczono, 5 opcjonalnych PostgreSQL pominięto** (152 łącznie), także testy JS edytora/PROD/API. Zatwierdzona migracja rzeczywistych plików zachowała efektywne wartości i wszystkie wcześniejsze wartości dev, usunęła 4 kopie i przeniosła 6 wpisów. Oryginały pozostają w chronionej kopii lokalnej, bez wartości w raporcie. Szczegóły, ograniczenia ochrony Windows/WSL i sposób odtworzenia: [raport migracji](dev-env-deduplication-report.md). Nie wykonywano żywych operacji DB/Vault/płatności ani restartów aplikacji.

Zapis blokowany podczas bieżącego zadania. Zmieniona przez inne okno/IDE/CLI rewizja daje 409; wczytaj ponownie. Dowiązania symboliczne, junction, hardlink, ścieżki spoza listy i pliki >128 KB są odrzucane. Pliki etapowe mają próbę praw 0600. Częściowy błąd jest wycofywany; to nadal nie jest transakcja odporna na awarię zasilania. Gdy wycofanie zawiedzie, kopie `.control-config-*` pozostają przy plikach i mogą zawierać sekrety. Stan po takiej awarii sprawdź przed uruchomieniem usług. Edytor nie utrzymuje trwałego archiwum poprzednich haseł.

## Przeniesione funkcje CLI — odpowiedniki w przeglądarce

| Funkcja CLI | Odpowiednik WWW | Profil / ważna różnica |
|---|---|---|
| Pierwszy start, project.env/dev.env, przygotowanie DEV | Kreator Konfiguracja DEV + kolejne kroki + kontrola wymagań | DEV; jawne operacje zamiast automatycznego przenoszenia plików legacy |
| Rotacja lokalnego klucza API | Wygeneruj nowy klucz API DEV / edytor | DEV; sekret bez wartości w logu |
| Docker/PostgreSQL/Redis, Flyway, build obu projektów | Docker/Diagnostyka/Build | Infrastruktura tylko DEV; buildy DEV/TEST |
| Unieważnianie sesji banku | Redis → Unieważnij sesje banku DEV | Tylko spring:session:*; nie FLUSHALL i nie sesje sklepu |
| Monitoring: setup/start/status/logi/stop/rotacja | Kategoria Monitoring | DEV; lokalny socket Docker; bez profilu PROD |
| Backup/create/restore-test/prune | Backup DEV | Katalog z Ustawienia operacji (domyślnie `.novabank/backups/dev`), walidacja własności; restore do osobnych baz, nie zastąpienie bieżących |
| Harmonogramy backupu i DR | Backup DEV / Disaster Recovery | Osobne timery użytkownika `novabank-web-dev-backup`, `novabank-web-dev-dr-test`; godziny konfigurowalne (domyślnie 02:00 codziennie i niedziela 04:00); wymaga systemd w WSL/Linux |
| Test DR/raport/logi | Disaster Recovery | Kopie baz DEV, izolowane kontenery i aplikacje z profilem test, bez PROD |
| Stripe status/login/listener | Stripe TEST | Tylko testowe klucze; autoryzacja przeglądarkowa Stripe nadal wymaga udziału operatora |
| Klucz webhooka wypisywany przez CLI | Zapisz sekret webhooka Stripe TEST / edytor | Sekret z `listen --print-secret` trafia do dev.env bez wypisywania; restart sklepu |
| Unit/integration/E2E | Testy i logi | E2E tylko na uruchomionym DEV: tworzy klienta/zamówienie/autoryzację; nie zatwierdza przelewu |
| Instalacje/kontrola Docker/JDK/narzędzi | Narzędzia / Stripe TEST | Instalacja WSL używa sudo -n; brak wpisywania hasła administratora w WWW; JDK/Docker to diagnostyka, nie instalator |
| Porządkowanie artefaktów | Archiwizuj stare raporty i logi | DEV/TEST; ta sama bezpieczna archiwizacja raportów, rotowanych logów i wygenerowanych tmp z retencjami profilu; bez sekretów |
| PDF regulaminu / instrukcje | Narzędzia / Dokumentacja | DEV/TEST; istniejący generator, venv i zależności projektu |

Harmonogramy wykonują się niezależnie od otwartej przeglądarki i po zamknięciu panelu; ich usunięcie wymaga osobnej operacji. Start zaplanowanej usługi nie oznacza sukcesu backupu — sprawdź status/logi. WSL bez sesji systemd użytkownika nie uruchomi timera; panel pokaże błąd zamiast symulować harmonogram. Backupy są bez szyfrowania aplikacyjnego, mogą zawierać dane klientów i sekrety w dumpach — chroń katalog i nie commituj. Retencja usuwa stare kopie tylko z wybranego, zweryfikowanego katalogu po udanym nowym backupie. Chroniony dispatcher sprawdza lokalny socket Docker; web-mode backupu czyta env jako dane, nie wykonuje source/eval, a usługi systemd zachowują ten sam tryb, stałe kontenery i przypisany przy instalacji katalog/retencję.

Listener Stripe jest zadaniem pierwszoplanowym do **Przerwij** lub limitu 1 h i blokuje inne zadania (tak jak terminalowy listener). Najpierw zapisz sekret webhooka, zrestartuj sklep, potem uruchom listener. Adapter wybiera obsługiwany przez zainstalowaną wersję CLI przepływ loginu; nowy przepływ dwuetapowy parsuje tylko allowlistę kroków, bez shell. Linków autoryzacji nie udostępniaj razem z dziennikiem. Operacje Stripe nie mają `--live`; wpisanie klucza live jest odrzucane. Oficjalne źródła opcji: [Stripe CLI](https://github.com/stripe/stripe-cli), [listen](https://github.com/stripe/stripe-cli/blob/master/pkg/cmd/listen.go), [login](https://github.com/stripe/stripe-cli/blob/master/pkg/cmd/login.go).

Powyższe operacje nie zostały uruchomione na rzeczywistych bazach podczas wdrożenia. Testy sprawdzają politykę profili, argumenty i adaptery na atrapach/plikach tymczasowych; testy rzeczywistego Docker/systemd/Stripe wymagają skonfigurowanego środowiska i zgody operatora na skutki.

## Weryfikacja zmian

Rozszerzenie CLI/Vault/PROD (2026-10-14): **135 testów Python/API/Bash zaliczono, 5 opcjonalnych PostgreSQL pominięto** (140 łącznie). Testy JS potwierdziły typowane potwierdzenia PROD/USUN, ukryte przejściowe pola, czyszczenie/anulowanie/Esc, dodatkowy warunek restartu przed finalizacją i kafelek Vault; regresje edytora i katalogu API także poprawne. Schematy OpenAPI zweryfikowano JSON Schema; katalog ma **84 operacje**. Składnia Bash/JS poprawna. Testy są offline/na atrapach, bez kontaktu z rzeczywistym Vault, instalacji pakietów, zmiany prawdziwych env, certyfikatów, polityk, danych baz lub procesów aplikacji.

W izolowanej przeglądarce z fikcyjną konfiguracją i wyłączonym wykonywaniem zadań sprawdzono kafelek/filtr Vault, 10 kart Vault oznaczonych PROD, formularz Shamir, blokadę uruchomienia bez kompletnych potwierdzeń/danych, finalizację wymagającą restartu, anulowanie, przewodnik PROD i przełączanie PL/EN. Przycisk stanu sprawdzono bezpośrednio przez właściwość DOM `disabled`; ogólne dopasowanie przycisku „Uruchom” mogło wskazać także przycisk z tła. Nie klikano wykonania zadań. Widok 731 px nie miał poziomego overflow; konsola bez błędów. Podglądy: `control-center-vault-prod-preview.jpg`, `control-center-vault-prod-confirmation-preview.jpg` — **bez realnych sekretów i bez dowodu wykonania procedur na żywym Vault**. Weryfikacja zainstalowanego Vault, praw operatora, CA/truststore, rzeczywistego keytool i systemowego sudo wymaga osobnego kontrolowanego przebiegu operatora.

Filtry kafelków (2026-10-12): test JS sprawdza trzy widoki i liczniki, puste wyniki/plik, pola tylko do odczytu, klasyfikację nowych pól, zachowanie edycji i zaznaczeń usunięcia w ukrytych kafelkach oraz ich uwzględnienie w zapisie. Potwierdzono maskowanie po zmianie filtra i ignorowanie spóźnionej odpowiedzi podglądu sekretu. **114 testów Python/API/Bash zaliczono, 5 opcjonalnych PostgreSQL pominięto**; składnia JS i test dokumentacji API poprawne. Zmiana dotyczy wyłącznie lokalnego interfejsu, bez zapisu rzeczywistych env i sekretów. Weryfikację przeprowadzono na atrapach DOM/API; wygląd filtrów w przeglądarce pozostaje niezweryfikowany z powodu wcześniejszej blokady podglądu przez kontrolę dostępu. Odśwież panel przez Ctrl+F5, bez restartowania banku/sklepu.

Układ kafelkowy edytora (2026-10-12): test JS potwierdza powiązanie etykiet z polami, rozróżnienie sekretów/ustawień i pól tylko do odczytu, zachowanie „Pokaż na 45 s”, ukrycie w tle oraz usuwanie/cofanie bez utraty niezapisanej wartości. **114 testów Python/API/Bash zaliczono, 5 opcjonalnych PostgreSQL pominięto**; składnia JS i test dokumentacji API poprawne. Nie zmieniano rzeczywistych konfiguracji ani sekretów. Weryfikacja wizualna nowego układu pozostaje niepotwierdzona: automatyczna kontrola dostępu do osobnego podglądu localhost zwróciła 429, zanim przeglądarka otworzyła stronę. Podgląd został zatrzymany. Ponieważ zmieniono tylko pliki statyczne, działający panel pobierze je po odświeżeniu strony (Ctrl+F5); restart banku/sklepu nie jest potrzebny.

Rozszerzenie DEV/TEST (2026-10-12): **114 testów Python/API/Bash zaliczono, 5 opcjonalnych PostgreSQL pominięto** (119 łącznie). Składnia czterech skryptów Bash i JS poprawna; test JS edytora potwierdza anulowanie/zgodę na podgląd, timer 45 s, ukrycie w tle, zapis TEST i reset. Schematy OpenAPI zweryfikowano JSON Schema (17 operacji HTTP), a katalog operacji obejmuje 62 pozycje. Testy używały plików tymczasowych i atrap Docker/sudo/npm/Stripe; nie zmieniono rzeczywistych env, baz, kont, harmonogramów, sekretów ani procesów użytkownika. Sprawdzono w przeglądarce listę plików, zapis portu wraz z URL banku, dodanie ustawienia TEST i blokadę PROD. Dalszy podgląd panelowego okna sekretu był ograniczony przez zatrzymany natywny dialog starej karty podglądu; samą logikę nowego okna zweryfikowano testem JS. To nie jest potwierdzenie rzeczywistego działania usług Docker/systemd/Stripe na tej instalacji.

```bash
python3 -B -m unittest discover -s scripts/control-center/web -v
node --check scripts/control-center/web/app.js  # opcjonalny lokalny check; Node nie jest wymagany przez panel
node --check scripts/control-center/web/api-docs.js
node scripts/control-center/web/test_api_docs.js  # opcjonalny test precyzji int64; tylko stdlib Node
bash -n scripts/start.sh scripts/control-center/control-center.sh
# Opcjonalne testy SQL: oddzielny, własny kontener PostgreSQL, usuwany po testach.
NOVABANK_CASH_SQL_TEST=1 python3 -B -m unittest discover -s scripts/control-center/web -p test_cash_sql.py -v
```

Testy API używają wyłącznie mocków i katalogów tymczasowych: kontrolują PROD, allowlistę, potwierdzenia, kolejkę, blokadę instancji, maskowanie i ochronę endpointów. Nie uruchamiają banku, Docker ani operacji finansowych. Sprawdzenie panelu nie oznacza ponownego wykonania testów biznesowych banku ani testów wszystkich operacji na działającej infrastrukturze.

Weryfikacja nowych funkcji z 2026-10-11: **51 testów jednostkowych/API Python i 5 testów izolowanego PostgreSQL — OK**. Testy SQL sprawdzają migracje, powtórzenie korekty, konflikt danych pod tym samym UUID, równoczesne dodawanie/odejmowanie, blokadę salda ujemnego oraz trwałe zawieszenie po trzech błędach. Testy jednostkowe Java: bank **120**, sklep **27** — bez błędów. JavaScript i Bash: składnia poprawna. Testy oraz podgląd w przeglądarce nie zmieniały istniejących sald DEV i nie restartowały aplikacji użytkownika. Powstał i został usunięty tylko własny kontener testowy, bez wolumenów baz DEV.

W przeglądarce sprawdzono formularze obu korekt, wymóg potwierdzenia, niepoprawną precyzję kwoty, blokadę korekt w PROD, licznik, przewijanie i doładowanie 58 testowych powiadomień oraz układ mobilny. Podglądy **z danymi przykładowymi**, nie z realnymi kontami, zapisano jako `control-center-notifications-preview.jpg` i `control-center-cash-preview.jpg`. Nie wysyłano korekty salda z przeglądarki do działającej bazy DEV.

Po dodaniu zarządzania alertami: **59 testów Python/API — OK** (5 opcjonalnych testów PostgreSQL pominięto w tym przebiegu, bo SQL banku/sklepu nie zmieniano). Sprawdzono migrację starszego SQLite, archiwizację/kosz/przywracanie, idempotencję, brak ponownego importu odłożonych wpisów, zakres usuwania zbiorczego, liczniki, paginację oraz ochronę tokenem/Origin i walidację parametrów. JavaScript: poprawna składnia. W przeglądarce na danych tymczasowych sprawdzono panel z prawej strony, potwierdzenie i anulowanie usuwania, usuwanie zbiorcze z zachowaniem archiwum oraz przywrócenie z kosza. Rzeczywistych alertów użytkownika nie zmieniano. Zmianę wymiarów podglądu mobilnego blokował mechanizm podglądu (pozostawał 1280 px); nowe media queries nie zostały zweryfikowane wizualnie na wąskim ekranie. Aktualne podglądy: `control-center-notifications-management-preview.jpg` i `control-center-notification-drawer-preview.jpg` — oba z przykładowymi danymi.

### Weryfikacja dokumentacji API

Po dodaniu **Konfiguracja DEV**: **86 testów Python/API zaliczono, 5 opcjonalnych PostgreSQL pominięto**. Sprawdzono tworzenie dwóch plików, maskowanie danych, zachowanie sekretów i nieznanych ustawień, blokadę tożsamości baz, walidację, profile i token, rewizję/równoległe zapisy, odmowę symlink/hardlink oraz wycofanie po błędzie drugiego pliku. Testy nie wykonywały poleceń Docker, nie zmieniały prawdziwych env i nie uruchamiały banku/sklepu. Specyfikacja obejmuje teraz 14 operacji; lokalny walidator JSON Schema potwierdził nowe modele, formaty i granice portów. JavaScript: poprawna składnia i test precyzji dokumentacji. W przeglądarce na osobnym serwerze/katalogu tymczasowym sprawdzono wymagane potwierdzenie, kolizję portów, poprawny zapis, ochronę pól, dostępność kolejnych kroków, odrębne potwierdzenie startu (anulowane) oraz ukrycie formularza w PROD. Zachowano aktualny motyw; podgląd `control-center-dev-configuration-preview.png` zawiera wyłącznie testowe ustawienia. To nie jest weryfikacja gotowości rzeczywistych baz ani udanego startu aplikacji. Aktywuj nową zakładkę przez restart panelu.

Po dodaniu dokumentacji API: **66 testów Python/API — OK**, 5 opcjonalnych testów PostgreSQL pominięto (SQL nie zmieniano). Dodatkowo lokalny walidator JSON Schema 2020-12 potwierdził wszystkie modele oraz 43 poprawne przypadki żądań/odpowiedzi; sprawdzono też wybrane niedozwolone dane. Nie instalowano nowych bibliotek. Test Node potwierdza zachowanie dokładnych granic int64 i treści tekstów. Składnia obu skryptów JavaScript jest poprawna. W izolowanej przeglądarce zweryfikowano karty/linki, ukrycie linków DEV w TEST/PROD, katalog 12 operacji, wyszukiwanie, pusty wynik, rozwijanie opisów i modeli. Nie wykonywano operacji bankowych ani nie zmieniano rzeczywistych powiadomień. Podglądy: `control-center-api-links-preview.png` i `control-center-api-catalog-preview.png`. Testy schematów JSON i odwołań nie zastępują testów banku/sklepu ani pełnego testu importu we wszystkich klientach OpenAPI.


## Grafiki koncepcyjne

Przed implementacją wygenerowano trzy makiety narzędziem imagegen w trybie wbudowanym. Statusy, daty i wyniki w grafikach są **przykładowe**, nie z aktualnego środowiska; finalny panel korzysta z sond i rzeczywistych logów. Przykładowe liczby/testy z grafiki nie są raportem QA. Grafiki:

- `control-center-concept-01-dashboard.png` — pulpit i status usług.
- `control-center-concept-02-operations.png` — operacje i potwierdzenie.
- `control-center-concept-03-tests-logs.png` — testy i logi.

Dokładne prompty wszystkich trzech grafik są zapisane w [control-center-image-prompts.md](control-center-image-prompts.md).

Wspólny prompt: wysokiej jakości płaski desktopowy UI operatorski „NovaBank Control Center”; język polski; białe karty i tło #f6f3f0, bordo #8b003a i złoto #c5a35c; własny monogram NB, bez logo Alior, osób, telefonów i secretów; lewa nawigacja Pulpit/Operacje/Testy i logi/Dokumentacja; DEV/TEST i odznaka „PROD — tylko diagnostyka”; czytelna hierarchia; stopka „Wizualizacja — dane przykładowe”. Dodatki do promptów:

1. Pulpit: „Centrum operacyjne”, „Bank i sklep w jednym miejscu”; trzy karty Aplikacje/Infrastruktura/Ostatnie zadanie, tabela NovaBank 8080/Shop 8081/PostgreSQL 5300 i 5400/Redis 6379/Monitoring; panel szybkich akcji; ostatnia aktywność.
2. Operacje: „Operacje środowiska”; kategorie Środowisko/Docker/Build/Diagnostyka; karty przygotowania, startu/stopu DEV, statusu i buildów; szuflada potwierdzenia startu DEV, ostrzeżenie o zmianie lokalnego środowiska i przyciski Anuluj/Uruchom.
3. Testy/logi: „Weryfikacja i dziennik zadań”; testy banku/sklepu/integracyjne z wymaganiem Docker; lista zadań obok jasnego monospaced logu, Odśwież/Kopiuj, przykładowe BUILD SUCCESS i oznaczenie fikcyjnych danych makiety.

W finalnym kodzie nazwy/przyciski i opisy doprecyzowano po analizie rzeczywistych operacji — np. „Buduj” oznacza JAR Maven, nie obraz Docker; „dostępny” port nie oznacza zdrowego serwisu. Zachowano kierunek graficzny, nie fikcyjne informacje z obrazu.
