# Raport kontroli duplikatów konfiguracji DEV

Data: 2026-10-14. Projekt: `C:\Dysk D\stuff\Bank-demo`.

## Aktualizacja: migracja zatwierdzona i wykonana

Po pierwotnym wstrzymaniu użytkownik zatwierdził zachowanie wersji z **`dev.env`** dla `BANK_API_KEY` i `BANK_API_RATE_LIMIT_PER_MINUTE`. Ponowny audyt nie wykrył nowych konfliktów. Migrację wykonano 2026-10-14.

- Usunięto **4 kopie** z `project.env`: `BANK_API_KEY`, `BANK_API_KEY_ID`, `BANK_API_KEYS`, `BANK_API_RATE_LIMIT_PER_MINUTE`. Wpisy w `dev.env` pozostały niezmienione.
- Przeniesiono **6 wpisów**, bez zmiany ich wartości, z project do dev: `ADMIN_EMAIL`, `BANK_API_URL`, `POSTGRES_PASSWORD`, `SHOP_MERCHANT_ACCOUNT_NUMBER`, `SHOP_PAYMENT_CALLBACK_URL`, `SHOP_POSTGRES_PASSWORD`.
- Efektywna mapa `project | dev` została porównana przed i po migracji: bez zmiany wartości. To sprawdzenie konfiguracji, nie test żywych usług.
- Oba oryginały zachowano w `.novabank/env/backups/dedup-20261014T113932Z-8b8c074d`. Kopie zawierają sekrety: nie udostępniaj ich, nie dodawaj do Git. Zapisano je z uprawnieniami 0600 w WSL; na Windows rzeczywista ochrona zależy także od NTFS/ustawień montowania WSL i ACL katalogu.
- Nie rotowano klucza TOTP ani hasła żadnej bazy. Nie uruchamiano ALTER USER, migracji DB, Vault, restartów banku/sklepu ani operacji płatności.

### Obowiązujący podział

| Plik | Właściciel ustawień |
|---|---|
| `project.env` | Dziesięć niewrażliwych kluczy infrastruktury: POSTGRES_HOST/PORT/DB/USER, SHOP_POSTGRES_HOST/PORT/DB/USER, REDIS_HOST/PORT. |
| `dev.env` | Hasła baz i monitoringu, API/TOTP, adresy/porty aplikacji, konfiguracja płatności i pozostałe rozpoznane ustawienia profilu DEV. |
| `application.yaml` | Wspólne domyślne ustawienia aplikacji; w tej migracji nie zmieniano tego pliku. |
| TEST / PROD | Nie zmieniano test.env, bootstrapów, sekretów Vault ani ich profili. |

Reguły są współdzielone przez kreator, edytor i generator WSL w `scripts/control-center/web/env_ownership.py`. Edytor odrzuca ustawienie w niewłaściwym pliku, nie tworzy już kopii project/dev; nieznane rozszerzenia zachowuje bez zgadywania. Zgodne stare wpisy może przenieść do właściciela, lecz niezatwierdzone konflikty blokują zapis. Jednorazowa zgoda z tego zadania nie jest automatyczną zgodą na przyszłe konflikty.

Compose w CLI/Centrali wczytuje project, następnie dev. Domyślny backup DEV czyta oba pliki jako dane. Generator monitoringu nie zapisuje już metryk do project; jego własna kopia w `.novabank/monitoring/.env.monitoring` jest osobnym wymaganym plikiem konsumenta, nie usunięto jej w tym zadaniu. Legacy generator PowerShell zachowuje pozostałe wpisy dev, zamiast zastępować cały plik pięcioma wierszami.

Docker potwierdza obsługę wielu `--env-file`, w kolejności podania: [oficjalna dokumentacja Compose](https://docs.docker.com/compose/how-tos/environment-variables/variable-interpolation/). Nie oznacza to importu sekretów DEV do wszystkich kontenerów: Compose interpoluje wyłącznie właściwości wskazane w jego YAML.

### Aktywacja i odtwarzanie

Uruchom ponownie samą Centralę i odśwież stronę przed dalszą edycją, aby stary proces Python nie używał poprzednich reguł zapisu. Zamknięcie Centrali może zatrzymać aplikacje DEV uruchomione przez panel; procesy z IDE są niezależne. Nie uruchamiano restartów automatycznie.

W WSL: `bash ./scripts/start.sh`. Bank i sklep mają już importy obu plików w application-dev.yaml, a EnvFile IntelliJ wskazuje dev.env. Działające procesy nie przeładowują konfiguracji po samym zapisie. Ponieważ efektywne wartości nie zmieniły się, migracja nie wymaga rotacji ani zmiany poświadczeń w bazach; planowy restart wystarczy do wczytania nowego układu.

Gdy potrzebne jest odtworzenie, przy zatrzymanych zapisach Centrali/CLI odtwórz **oba pliki jako parę** z powyższego katalogu, nie tylko pojedynczy plik. Stary układ przywróci także znane konflikty; nowy edytor odmówi ich samoczynnego rozstrzygnięcia. Nie usuwano kopii i nie zmieniano ich wartości.

### Weryfikacja wykonanej migracji

- Na rzeczywistych plikach: `project.env` ma **10** ustawień, `dev.env` — **22**, wspólnych nazw **0**. Wszystkie 16 wcześniejszych wartości dev zachowano. Mapa efektywnych wartości przed/po jest identyczna; nie dopisano nowych wartości.
- Przeniesione wpisy sprawdzono także pod kątem znaków cytowania, interpolacji i ucieczek, które mogłyby zmienić interpretację przez Compose/EnvFile/properties. W tym zestawie takich znaków nie znaleziono. Przyszłe przeniesienia o niejednoznacznym formacie są blokowane.
- **147 testów Python/API/Bash zaliczono, 5 opcjonalnych PostgreSQL pominięto** (152 łącznie). Migracja, backup, idempotencja, blokada niezatwierdzonych konfliktów, złego właściciela i rollback po błędzie są sprawdzane na katalogach tymczasowych, nie na rzeczywistych usługach.
- Testy JS edytora, potwierdzeń PROD oraz katalogu API zaliczone. Składnia Bash oraz AST generatora PowerShell poprawna; nie uruchamiano generatora PowerShell na prawdziwych plikach.
- Końcowy audyt nie proponuje żadnego dalszego przeniesienia. Pliki konfiguracji i kopia są ignorowane przez Git. W raporcie nie znaleziono wartości sprawdzanych sekretów.
- Nie wykonano testu żywej płatności, logowania do PostgreSQL ani restartu aplikacji. Raport nie deklaruje zdrowia działających usług.

## Historia: audyt przed wdrożeniem

Poniższe sekcje dokumentują pierwotne wstrzymanie. Opis edytora i numery linii odnoszą się do stanu sprzed powyższej migracji, nie do jego obecnych zasad.

## Decyzja: wstrzymano zmiany

Wykryto **dwa konflikty wartości** między `.novabank/env/project.env` i `.novabank/env/dev.env`. Zgodnie z poleceniem użytkownika nie usunięto żadnego wpisu i nie zmieniono konfiguracji ani kodu aplikacji. Jedynym utworzonym plikiem w ramach tego zadania jest ten raport.

Nie wybrano automatycznie jednej z różnych wartości, nie wygenerowano nowych sekretów i nie uruchomiono operacji zmieniających bazy, Vault, kontenery ani procesy banku/sklepu. Nie stwierdzono utraty danych spowodowanej tym audytem; ustalono ryzyko zmiany działania po arbitralnym usunięciu wpisów.

## 1. Wynik porównania lokalnych plików

Porównano przypisania w obu plikach, bez wypisywania wartości, ich skrótów ani fragmentów sekretów. `project.env` zawiera 20 nazw ustawień, a `dev.env` — 16. Wspólnych nazw jest **4**. Nie znaleziono wielokrotnego przypisania tej samej nazwy wewnątrz pojedynczego pliku ani nieobsługiwanych linii przypisań w przeprowadzonym audycie.

| Nazwa | Porównanie między plikami | Znaczenie dla deduplikacji |
|---|---|---|
| `BANK_API_KEY` | Różne, niepuste wartości | **Konflikt sekretu.** Nie można uznać wpisów za identyczne kopie i usunąć jednego bez decyzji o zachowywanej wartości. |
| `BANK_API_RATE_LIMIT_PER_MINUTE` | Różne, niepuste wartości | **Konflikt ustawienia.** Usunięcie wpisu może zmienić limit żądań API. |
| `BANK_API_KEY_ID` | Identyczne, niepuste wartości | Nie ma konfliktu wartości; usunięcie wymaga zachowania wpisu w pliku czytanym przez klientów i skrypty. Pozostawiono bez zmian po wykryciu konfliktów. |
| `BANK_API_KEYS` | Oba wpisy puste | Nie ma różnicy wartości. Puste pole nie jest drugim aktywnym kluczem. Pozostawiono bez zmian po wykryciu konfliktów. |

To jest wynik audytu plików w tym zadaniu, a nie dowód ustawień już uruchomionych procesów. Nie odczytywano środowiska procesów Java ani wartości z Vault. Nie sprawdzano równości sekretów DEV względem TEST/PROD — zakres obejmuje wskazane dwa pliki DEV.

## 2. Dlaczego nie można bezpiecznie usunąć wszystkich duplikatów

### 2.1. Klucz komunikacji bank–sklep

- [KOD] Bank wiąże `BANK_API_KEY`, `BANK_API_KEYS` i `BANK_API_RATE_LIMIT_PER_MINUTE` z konfiguracją API: [application.yaml](</C:/Dysk D/stuff/Bank-demo/src/main/resources/application.yaml:48>).
- [KOD] Sklep pobiera pojedynczy klucz z `BANK_API_KEY`: [application-dev.yaml](</C:/Dysk D/stuff/Bank-demo/shop-app/src/main/resources/application-dev.yaml:41>) i przesyła go w nagłówku `X-API-Key`: [BankAuthorizationClient.java](</C:/Dysk D/stuff/Bank-demo/shop-app/src/main/java/org/demo/shopapp/payment/BankAuthorizationClient.java:26>).
- [KOD] Gdy bank nie dopasuje klucza lub klucz jest nieużywalny, filtr zwraca HTTP 401: [PaymentApiKeyFilter.java](</C:/Dysk D/stuff/Bank-demo/src/main/java/org/demo/bankdemo/config/PaymentApiKeyFilter.java:72>).
- [WNIOSEK] Zastąpienie obowiązującego klucza inną wartością bez uzgodnienia banku, sklepu i klientów może zablokować wywołania płatności. **Sama różnica plików nie dowodzi, że płatności obecnie nie działają** — oba procesy mogą używać tego samego źródła o wyższym priorytecie.

### 2.2. Limit żądań API

- [KOD] Filtr pobiera limit przez `properties.getRateLimitPerMinute()`: [PaymentApiKeyFilter.java](</C:/Dysk D/stuff/Bank-demo/src/main/java/org/demo/bankdemo/config/PaymentApiKeyFilter.java:144>).
- [KOD] Odrzucenie przez mechanizm limitu kończy się HTTP 429: [PaymentApiKeyFilter.java](</C:/Dysk D/stuff/Bank-demo/src/main/java/org/demo/bankdemo/config/PaymentApiKeyFilter.java:89>).
- [WNIOSEK] Wybranie jednej z różnych wartości może zaostrzyć lub osłabić ograniczenie ruchu. Nie jest to usunięcie identycznej kopii, lecz zmiana konfiguracji wymagająca świadomej decyzji.

## 3. Zweryfikowane miejsca odczytu i zapisu

| Konsument | Potwierdzone zachowanie | Źródło |
|---|---|---|
| Bank DEV | Importuje `project.env`, następnie `dev.env` jako pliki properties. | [application-dev.yaml](</C:/Dysk D/stuff/Bank-demo/src/main/resources/application-dev.yaml:3>) |
| Sklep DEV | Importuje oba pliki; przewidziano ścieżki względem katalogu sklepu oraz root projektu. | [application-dev.yaml](</C:/Dysk D/stuff/Bank-demo/shop-app/src/main/resources/application-dev.yaml:3>) |
| Centrala — start aplikacji | Sprawdza obecność klucza API i TOTP **w `dev.env`**, a konfigurację pomocniczą składa jako `project \| dev`, gdzie wpis DEV zastępuje ten sam klucz projektu. | [server.py](</C:/Dysk D/stuff/Bank-demo/scripts/control-center/web/server.py:401>) |
| IntelliJ — Bank DEV / Shop DEV | EnvFile wskazuje `dev.env`; katalog roboczy to root projektu. Oprócz środowiska działa także import z YAML aplikacji. | [Bank DEV.run.xml](</C:/Dysk D/stuff/Bank-demo/.run/Bank DEV.run.xml:4>), [Bank DEV.run.xml — EnvFile](</C:/Dysk D/stuff/Bank-demo/.run/Bank DEV.run.xml:15>), [Shop DEV.run.xml](</C:/Dysk D/stuff/Bank-demo/.run/Shop DEV.run.xml:15>) |
| Test E2E | Gdy klucza nie podano w środowisku, czyta `BANK_API_KEY` oraz jego ID bezpośrednio z `dev.env`. | [e2e-smoke.sh](</C:/Dysk D/stuff/Bank-demo/scripts/e2e-smoke.sh:26>) |
| Edytor „Pliki i sekrety” | Przy zmianie istniejącego duplikatu synchronizuje wpis w drugim pliku. Przy usunięciu pola usuwa istniejący duplikat również w drugim pliku. | [configuration_files.py](</C:/Dysk D/stuff/Bank-demo/scripts/control-center/web/configuration_files.py:178>) |

[FRAMEWORK] Dla listy lokalizacji w jednym `spring.config.import` późniejszy import ma pierwszeństwo. Zmienne środowiskowe i argumenty procesu mogą mieć wyższy priorytet niż pliki. Dlatego konfiguracja na dysku nie rozstrzyga samodzielnie ustawień działającego procesu. Źródło: [Spring Boot — Externalized Configuration / Property Ordering](https://docs.spring.io/spring-boot/reference/features/external-config.html#features.external-config.files.importing.property-ordering) oraz sekcja kolejności źródeł na tej samej stronie.

**Ważne:** nie należy używać obecnego „Usuń pole” jako narzędzia usuwania tylko jednej kopii. Potwierdzona implementacja może usunąć obie kopie i pozbawić aplikację ustawienia. Deduplikacja musi mieć osobną kontrolę zachowania wartości w docelowym pliku.

## 4. Warunki bezpiecznego wznowienia

1. Operator musi wskazać źródło obowiązującej wartości **osobno dla `BANK_API_KEY` i `BANK_API_RATE_LIMIT_PER_MINUTE`**: `dev.env` albo `project.env`. Nie należy przesyłać wartości sekretu w czacie. Kierunek sugerowany przez obecny odczyt DEV nie jest zgodą na usunięcie alternatywnego sekretu.
2. Jeżeli oba różne klucze nadal są potrzebne różnym klientom, zadanie przestaje być prostą deduplikacją. Należy przygotować osobną procedurę zgodności/rotacji; nie usuwać żadnego klucza automatycznie.
3. Przed zapisem ponownie porównać pliki i przerwać, jeżeli zmieniły się od audytu. Zachować chronioną kopię do odtworzenia — bez publikowania sekretów ani dodawania ich do Git.
4. Sprawdzić wszystkie konsumenty i generatory dla planowanego podziału. Dla wskazanych kluczy bank–sklep `dev.env` jest przewidzianym plikiem aplikacyjnym; pozostawienie API tylko w `project.env` nie spełni obecnej kontroli startu Centrali i odczytu E2E.
5. Usuwać wyłącznie potwierdzone kopie po zachowaniu wybranej wartości w pliku docelowym. Nie zmieniać klucza TOTP, haseł baz, danych biznesowych ani profili TEST/PROD w ramach porządkowania tych czterech wpisów.
6. Testy migracji/edytora wykonać na atrapach; następnie zweryfikować, że generatory i edytor nie odtwarzają usuniętych kopii. Ewentualny restart i test płatności wymagają oddzielnego świadomego działania — nie wykonano ich podczas audytu.

## 5. Bilans wykonanych zmian

- Usunięte duplikaty: **0**.
- Zmienione sekrety/ustawienia: **0**.
- Zmiany kodu: **0**.
- Uruchomione mutujące operacje środowiska: **0**.
- Utworzono: `docs/dev-env-deduplication-report.md`.
- Wynik: **dalsze zmiany wstrzymane na konflikcie; wymagany wybór zachowywanych wartości bez ujawniania sekretów**.
