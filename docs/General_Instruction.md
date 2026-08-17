# NovaBank i NovaTech Shop

## Generalna instrukcja uruchomienia i obsługi

Ten dokument opisuje pełny lokalny przepływ pracy z projektem: WSL 2, Docker Engine, PostgreSQL, Redis, profile Spring/Maven, IntelliJ IDEA, HashiCorp Vault, TLS/HTTPS, monitoring, Stripe, testy, audyt i kopie zapasowe.

To środowisko edukacyjne uruchamiane lokalnie. Nie jest produkcyjnym bankiem ani operatorem prawdziwych płatności. Wariant Stripe używa testowego konta Stripe, a wariant bank-demo symuluje przelewy w bazach aplikacji.

> **Ważne: użyj własnej ścieżki projektu.** W instrukcji mogą występować przykłady
> `C:\Dysk D\stuff\Bank-demo` oraz `/mnt/c/Dysk D/stuff/Bank-demo`. Są to ścieżki
> środowiska autora i nie są wymagane przez aplikację. Przed uruchomieniem komendy
> zamień je na lokalizację, w której użytkownik ma repozytorium. Dotyczy to
> PowerShell, WSL/Linux/macOS, IntelliJ IDEA, plików EnvFile, Control Center,
> monitoringu, Vault oraz backupów. Szczegółową instrukcję konwersji ścieżek,
> także dla katalogów zawierających spacje, znajdziesz w sekcji **Zastąp
> przykładową ścieżkę własną** w części **Procedury rozszerzone i własna
> ścieżka projektu**.

### Jedna instrukcja główna

`General_Instruction.md` jest głównym i docelowo jedynym podręcznikiem użytkownika projektu. Zawiera procedury pierwszego uruchomienia, pracy z profilami `dev`, `test` i `prod`, konfiguracji IntelliJ, Docker/WSL, Vault, TLS, monitoringu, backupów, testów, płatności i NovaBank Control Center. Wszystkie komendy potrzebne do codziennej obsługi są opisane w kolejnych rozdziałach tego dokumentu.

Pozostałe pliki Markdown w katalogu `docs` pozostają obecnie zachowane jako materiały źródłowe i kopie robocze. Nie są wymagane do zwykłego uruchamiania aplikacji ani działania Control Center. Można je później przenieść lub usunąć po dodatkowej weryfikacji, ponieważ bieżące odsyłacze operacyjne prowadzą do tej instrukcji głównej.

## Zacznij tutaj: przewodnik i NovaBank Control Center

Jeżeli uruchamiasz projekt pierwszy raz, zacznij od interaktywnego przewodnika. Pokazuje on architekturę, ekrany aplikacji, kolejność uruchomienia, profile `dev`, `test` i `prod`, monitoring, Vault, backupy oraz podstawowe przepływy banku i sklepu.

W WSL, Linuxie albo macOS uruchom z katalogu projektu:

```bash
bash scripts/control-center/interactive-guide.sh
```

## Jak czytać instrukcję

Numerowana ścieżka `1-20` jest główną kolejnością operacyjną. Sekcje bez numerów
zawierają wprowadzenie techniczne, procedury rozszerzone i końcowe uzupełnienia;
nie są dodatkowymi etapami do wykonania po rozdziale `20`.

**Kolejność pracy:**

1. Przeczytaj rozdziały `1-4` i przygotuj WSL, Docker, dane, konta oraz role.
2. Wybierz środowisko: `5. Profil dev`, `6. Profil test` albo `9-12. Profil prod`.
3. Włącz `7. Monitoring dev` dopiero po uruchomieniu obu aplikacji.
4. Do codziennej pracy używaj `14. Backup PostgreSQL` i `16. Checklista po przerwie`.
5. Przy problemach przejdź do `17. Najczęstsze problemy`, a następnie do `19. NovaBank Control Center`.

## Spis treści

- `1-4`: architektura, sekrety, infrastruktura, konta i role
- `5-8`: profile dev/test, monitoring i płatności
- `9-12`: profil prod, Vault, TLS i rotacja kluczy
- `13-15`: retencja, backup, CI/CD, OpenAPI i Postman
- `16-20`: checklista, problemy, kolejność startu, Control Center i instalacja
- `Szybki przewodnik po projekcie`: komponenty, E2E, Vault, monitoring i backupy
- `Procedury rozszerzone i własna ścieżka projektu`: ścieżki, konfiguracje i procedury operacyjne
- `Uzupełnienia końcowe`: pierwszy start, katalog lokalny, retencja i operacyjna ściąga

## Szybki przewodnik po projekcie

Ten rozdział zbiera procedury z pozostałych notatek projektowych w jedną ścieżkę operacyjną. Pozostałe pliki Markdown pozostają w repozytorium jako dokumentacja szczegółowa, ale użytkownik może przejść przez cały projekt wyłącznie na podstawie tego rozdziału.

### Co uruchamiasz i za co odpowiadają komponenty

Projekt składa się z dwóch niezależnych aplikacji Spring Boot:

1. Bank NovaBank działa domyślnie na porcie 8080.
2. NovaTech Shop działa domyślnie na porcie 8081.

Bank obsługuje użytkowników, rachunki, salda, przelewy, odbiorców, karty, waluty, 2FA, historię, administrację i API autoryzacji płatności.

Sklep obsługuje katalog produktów, szczegóły produktu, konto klienta, koszyk, zamówienia, płatności przez bank-demo lub Stripe oraz refundacje.

Infrastruktura pomocnicza:

- WSL 2 zapewnia środowisko Linux na Windowsie.
- Docker Engine i Docker Compose uruchamiają PostgreSQL, Redis oraz monitoring. Docker Desktop nie jest wymagany.
- PostgreSQL przechowuje dane banku w bankdb oraz dane sklepu w shopdb.
- Redis przechowuje sesje banku, limity, krótkotrwałe wyzwania, rate limiting i stan wybranych procesów.
- Shop-app używa również sesji JDBC, dlatego nie każda sesja sklepu znajduje się w Redisie.
- Flyway wykonuje wersjonowane migracje schematu przy starcie aplikacji.
- HashiCorp Vault przechowuje sekrety profilu PROD i wydaje poświadczenia AppRole.
- Prometheus pobiera metryki, Grafana pokazuje dashboardy, a Alertmanager grupuje alerty.
- Stripe CLI jest opcjonalnym tunelem webhooków dla płatności Stripe w profilu DEV.
- IntelliJ IDEA uruchamia bank i sklep jako procesy Java oraz pozwala wybrać profil i plik EnvFile.
- GitHub Actions wykonuje testy i budowanie na podstawie pliku .github/workflows/ci.yml.

Ważne rozróżnienie: Docker uruchamia usługi infrastrukturalne, ale lokalne aplikacje mogą działać jako procesy IntelliJ. Dlatego kontenery mogą być aktywne, a port 8080 lub 8081 nadal niedostępny, dopóki nie uruchomisz odpowiedniej konfiguracji Spring Boot.

### Pierwsze uruchomienie nowej kopii projektu

Najpierw otwórz WSL w katalogu projektu. Ścieżka Windows jest zamieniana na ścieżkę WSL, na przykład:

~~~bash
cd "/mnt/c/Dysk D/stuff/Bank-demo"
pwd
~~~

Uruchom Centralę:

~~~bash
bash scripts/control-center/control-center.sh
~~~

Na Windowsie możesz użyć adaptera:

~~~powershell
.\scripts\control-center\start.ps1
~~~

Wybierz Pierwsze uruchomienie. Centrala:

1. sprawdzi system, Bash, Docker, Compose, Java, keytool, curl, jq, OpenSSL i GPG;
2. utworzy lokalny katalog .novabank;
3. przygotuje .novabank/env/project.env na podstawie przykładu;
4. wygeneruje brakujące lokalne hasła baz, ale nie zastąpi istniejących wartości;
5. przygotuje .novabank/env/dev.env;
6. pokaże pliki wymagane dla PROD i lokalizację sekretów;
7. utworzy konfigurację monitoringu DEV, gdy wybierzesz tę opcję;
8. pokaże brakujące komponenty i przejście do ich instalacji.

Nie przenoś plików .novabank do repozytorium i nie dodawaj ich do commitów. Są to dane lokalne, które mogą zawierać hasła, tokeny, certyfikaty, logi lub pliki bootstrap.

Jeżeli projekt jest w innej lokalizacji, uruchamiaj skrypty z katalogu projektu. Skrypty wyliczają PROJECT_ROOT na podstawie własnej lokalizacji. Nie wpisuj na stałe C:\Dysk D\stuff\Bank-demo do plików środowiskowych. W IntelliJ ustaw Working directory na katalog projektu, a EnvFile wskaż przez ścieżkę do bieżącego repozytorium.

### PostgreSQL, Redis i start infrastruktury

Przed uruchomieniem aplikacji sprawdź kontenery:

~~~bash
docker ps
~~~

Uruchom wymagane usługi z konfiguracją przygotowaną dla bieżącej kopii projektu:

~~~bash
docker compose --env-file .novabank/env/project.env up -d postgres shop-postgres redis
~~~

Sprawdź stan:

~~~bash
docker compose --env-file .novabank/env/project.env ps
~~~

Oczekiwany stan to healthy dla bank-postgres i shop-postgres oraz running dla bank-redis. Jeżeli kontenera nie ma, sprawdź pliki docker-compose.yaml, .env.example i .novabank/env/project.env. Nie wykonuj FLUSHALL na Redisie i nie usuwaj baz tylko dlatego, że aplikacja nie widzi biblioteki Maven.

Porty lokalne:

- bank-postgres: 127.0.0.1:5300 -> PostgreSQL 5432;
- shop-postgres: 127.0.0.1:5400 -> PostgreSQL 5432;
- bank-redis: 127.0.0.1:6379 -> Redis 6379.

Przy diagnostyce bazy używaj użytkownika i hasła z project.env. Nie zakładaj, że rola postgres istnieje w kontenerze. W tym projekcie często używana jest dedykowana wartość POSTGRES_USER. Przykład zapytania:

~~~bash
docker exec -e PGPASSWORD="$POSTGRES_PASSWORD" bank-postgres \
  psql -U "$POSTGRES_USER" -d bankdb -c \
  "select version, checksum, success from flyway_schema_history order by installed_rank;"
~~~

Nazwy zmiennych muszą odpowiadać aktualnemu project.env. Jeżeli nie są załadowane do bieżącej powłoki, odczytaj je z pliku tylko lokalnie albo wykonaj polecenie przez skrypt Centrali.

### Konta, rachunki i dane testowe

Do podstawowego testu potrzebujesz co najmniej:

1. zwykłego konta użytkownika banku z aktywnym rachunkiem PLN;
2. konta administratora banku z rolą administratora;
3. konta klienta sklepu, rejestrowanego niezależnie od konta bankowego;
4. aktywnego rachunku bankowego sklepu jako rachunku kupca;
5. opcjonalnie konta technicznego do metryk;
6. opcjonalnie konta Stripe sandbox, jeżeli testujesz operatora Stripe.

Rejestracja sklepu używa e-maila i hasła. Numer rachunku bankowego nie jest częścią rejestracji sklepu. Przy zakupie przez NovaBank klient loguje się do banku i wskazuje lub posiada aktywny rachunek. Rachunek kupca musi istnieć w banku, mieć status ACTIVE i mieć numer ustawiony w konfiguracji sklepu.

Administrator banku wchodzi do panelu przez zwykłe logowanie bankowe, a uprawnienie wynika z roli zapisanej w bazie. Nie wystarczy znać adresu /admin. Po zmianie roli wyloguj się i zaloguj ponownie.

Do szybkiego testu sklepu:

1. otwórz katalog;
2. zarejestruj konto klienta sklepu;
3. zaloguj się;
4. dodaj produkt do koszyka;
5. przejdź do kasy;
6. wybierz NovaBank albo płatność kartą bank-demo;
7. sprawdź status zamówienia, saldo rachunku klienta i saldo rachunku sklepu.

Nie używaj prawdziwych haseł, numerów kart ani danych produkcyjnych. Numer karty w profilu banku służy do demonstracji karty, a formularz operatora bank-demo nie powinien zapisywać pełnego numeru, CVV ani daty ważności po zakończeniu autoryzacji.

### Profile DEV, TEST i PROD

#### DEV

DEV jest profilem codziennej pracy lokalnej. Sekrety znajdują się w .novabank/env/dev.env, a aplikacje uruchamiasz z IntelliJ.

W konfiguracji Bank DEV ustaw:

- Active profiles: dev;
- Working directory: katalog repozytorium;
- Enable EnvFile: włączone;
- EnvFile: .novabank/env/dev.env;
- port aplikacji: 8080;
- port serwera Actuator: 8082.

W konfiguracji Shop DEV ustaw te same elementy, ale:

- port aplikacji: 8081;
- port serwera Actuator: 8083.

Możesz uruchomić oba procesy przez konfigurację złożoną NovaBank DEV. Nie uruchamiaj równolegle drugiego banku lub drugiego sklepu na tych samych portach.

Po zmianie BANK_API_KEY, BANK_API_URL, haseł baz, kluczy TOTP, danych Stripe lub danych monitoringu zatrzymaj i uruchom oba procesy ponownie. Sam refresh przeglądarki nie przeładowuje zmiennych środowiskowych procesu Java.

#### TEST

TEST jest przeznaczony do testów automatycznych. Zależności testowe obejmują PostgreSQL i Redis uruchamiane przez Testcontainers. Nie używaj w nim produkcyjnych baz, kluczy, AppRole ani certyfikatów.

Testy integracyjne mogą pobierać obrazy kontenerów, dlatego Docker Engine musi działać. Jeżeli środowisko CI nie ma dostępu do obrazów, test zakończy się błędem infrastruktury, a nie błędem logiki aplikacji.

#### PROD

PROD wymaga:

1. działającego Vault z aktywnym certyfikatem TLS;
2. odpieczętowania Vault trzema z pięciu kluczy Shamir;
3. polityk i AppRole bank-prod oraz shop-prod;
4. aktualnych Role ID i Secret ID;
5. truststore zawierającego certyfikat aktywnego listenera Vault;
6. keystore bank-server.p12 i shop-server.p12;
7. wartości sekretów przechowywanych w Vault;
8. osobnych konfiguracji IntelliJ Bank PROD i Shop PROD;
9. ustawionego HTTPS i bezpiecznych cookies.

W PROD nie używaj .novabank/env/dev.env. W plikach bootstrap prod.bank.env i prod.shop.env trzymaj tylko wartości potrzebne do połączenia z Vault, zgodnie z mapą sekretów. Hasła baz, BANK_API_KEYS, TOTP_ENCRYPTION_KEY, klucze operatora płatności i hasła keystore powinny być odczytywane z Vault albo przekazywane przez bezpieczny mechanizm uruchomieniowy.

### CI/CD, Maven i Testcontainers

Bank i shop-app są dwoma niezależnymi projektami Maven. Bank używa wrappera w katalogu głównym, a sklep własnego pom.xml i wrappera lub wrappera projektu głównego zgodnie z aktualną strukturą repozytorium.

Lokalne testy jednostkowe banku:

~~~powershell
.\mvnw.cmd -Punit-tests test
~~~

Lokalne testy jednostkowe sklepu:

~~~powershell
.\mvnw.cmd -f shop-app\pom.xml -Punit-tests test
~~~

Testy integracyjne banku:

~~~powershell
.\mvnw.cmd -Pintegration-tests verify
~~~

Testy integracyjne sklepu:

~~~powershell
.\mvnw.cmd -f shop-app\pom.xml -Pintegration-tests verify
~~~

W WSL odpowiedniki mają postać:

~~~bash
./mvnw -Punit-tests test
./mvnw -f shop-app/pom.xml -Punit-tests test
./mvnw -Pintegration-tests verify
./mvnw -f shop-app/pom.xml -Pintegration-tests verify
~~~

Jeżeli Maven zgłasza brak JAVA_HOME, sprawdź:

~~~bash
java -version
echo "$JAVA_HOME"
which java
~~~

W PowerShell sprawdź:

~~~powershell
java -version
$env:JAVA_HOME
Test-Path "$env:JAVA_HOME\bin\java.exe"
~~~

Mavenowe profile nie są tym samym co profile Spring. Profil Maven wybiera sposób budowania i testowania, a Spring profile wybiera konfigurację aplikacji. Przykład: Spring dev może być uruchomiony z IntelliJ, a unit-tests może działać jako profil Maven.

GitHub Actions z .github/workflows/ci.yml:

1. uruchamia testy jednostkowe banku i sklepu;
2. uruchamia testy integracyjne z PostgreSQL i Redis przez Testcontainers;
3. uruchamia test HTTP obu serwisów;
4. buduje i zapisuje artefakty JAR.

Workflow uruchamia się dla push, pull request i ręcznie przez workflow_dispatch. CI/CD nie wdraża automatycznie profilu PROD, nie używa sekretów produkcyjnych i nie zastępuje procesu Vault.

### Co dokładnie sprawdza test E2E

Skrypt scripts/e2e-smoke.sh wykonuje test przepływu bank-sklep na działających lokalnie aplikacjach. To nie jest tylko test dostępności portu.

Skrypt:

1. czeka na healthcheck banku i shop-app;
2. otwiera stronę logowania banku;
3. pobiera produkt z publicznego katalogu sklepu;
4. pobiera token CSRF i rejestruje testowego użytkownika sklepu;
5. loguje użytkownika przez HTTP;
6. dodaje produkt do koszyka i tworzy zamówienie;
7. wywołuje płatność sklepu;
8. ponawia tę samą płatność z identycznym Idempotency-Key;
9. sprawdza, że ponowienie nie tworzy drugiej bankowej autoryzacji;
10. sprawdza, że sklep utworzył autoryzację w rzeczywiście uruchomionym banku;
11. odczytuje stan autoryzacji jako PENDING;
12. zatrzymuje się przed faktycznym potwierdzeniem przelewu, dlatego test nie powinien przenosić środków demonstracyjnych.

Uruchomienie lokalne:

~~~bash
bash scripts/e2e-smoke.sh
~~~

Uruchomienie z jawnymi wartościami testowymi:

~~~bash
BANK_URL=http://127.0.0.1:8080 SHOP_URL=http://127.0.0.1:8081 \
BANK_HEALTH_URL=http://127.0.0.1:8082/actuator/health \
SHOP_HEALTH_URL=http://127.0.0.1:8083/actuator/health \
BANK_API_KEY=ci-bank-key BANK_API_KEY_ID=legacy bash scripts/e2e-smoke.sh
~~~

Wymagania E2E:

- bank działa na 8080;
- shop-app działa na 8081;
- endpointy health są dostępne;
- curl i jq są zainstalowane;
- bank API przyjmuje klucz testowy;
- sklepowi można utworzyć testowe konto i sesję.

Interpretacja wyniku:

- zielony health oznacza, że serwer odpowiada;
- pomyślna rejestracja i logowanie oznaczają poprawny CSRF oraz sesję sklepu;
- ta sama autoryzacja po drugim żądaniu oznacza poprawną idempotencję;
- PENDING oznacza, że utworzono autoryzację, ale nie pobrano środków;
- błąd 401 wskazuje na klucz, profil lub ochronę endpointu;
- błąd 429 wskazuje na rate limiting;
- timeout oznacza niedostępny proces, zły adres lub problem WSL/Windows;
- błąd w komunikacji bank-sklep należy sprawdzać w logach obu aplikacji.

### Test HTTP i ręczne API

Health banku i sklepu:

~~~powershell
Invoke-WebRequest -UseBasicParsing http://localhost:8082/actuator/health
Invoke-WebRequest -UseBasicParsing http://localhost:8083/actuator/health
~~~

Metryki są na oddzielnych portach:

~~~text
http://localhost:8082/actuator/prometheus
http://localhost:8083/actuator/prometheus
~~~

W DEV mogą wymagać Basic Auth. Przykład sprawdzenia w PowerShell:

~~~powershell
$pair = "metrics-reader:<METRICS_PASSWORD>"
$basic = [Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes($pair))
Invoke-WebRequest -UseBasicParsing -Headers @{ Authorization = "Basic $basic" } http://localhost:8082/actuator/prometheus
~~~

Kod 200 i tekst z seriami Prometheus oznaczają poprawne połączenie. Kod 401 oznacza brak lub błędny Basic Auth. Kod 404 zwykle oznacza zły port, wyłączony management endpoint albo uruchomienie innego profilu.

W starszym PowerShell metoda RandomNumberGenerator.Fill może nie istnieć. Użyj kompatybilnej generacji:

~~~powershell
$bytes = New-Object byte[] 32
$rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
$rng.GetBytes($bytes)
$metricsPassword = [Convert]::ToBase64String($bytes).TrimEnd('=').Replace('+','-').Replace('/','_')
$rng.Dispose()
~~~

### Monitoring: Prometheus, Grafana i Alertmanager

Monitoring jest osobnym zestawem kontenerów. Bank i sklep muszą najpierw działać
w IntelliJ IDEA, ponieważ Prometheus odczytuje ich endpointy Actuatora.

W profilu DEV przygotuj oba procesy z:

~~~text
MANAGEMENT_SERVER_ADDRESS=0.0.0.0
METRICS_USERNAME=metrics-reader
METRICS_PASSWORD=<to samo dlugie haslo>
BANK_MANAGEMENT_PORT=8082
SHOP_MANAGEMENT_PORT=8083
~~~

W IntelliJ IDEA włącz EnvFile .novabank/env/dev.env dla konfiguracji banku
i sklepu. Po zmianie wartości zatrzymaj i uruchom ponownie oba procesy.

Następnie w WSL uruchom centralny skrypt monitoringu:

~~~bash
cd "/mnt/c/Dysk D/stuff/Bank-demo"
bash scripts/monitoring/monitoring.sh setup
bash scripts/monitoring/monitoring.sh start --profile dev
bash scripts/monitoring/monitoring.sh status --profile dev
~~~

Skrypt przygotowuje .novabank/monitoring/.env.monitoring oraz konfigurację
Prometheusa, uruchamia trzy kontenery i sprawdza gotowość usług. Można też
uruchomić Compose bezpośrednio:

~~~bash
docker compose --env-file .novabank/monitoring/.env.monitoring -f docker-compose.monitoring.yaml up -d
~~~

Sprawdź:

~~~text
Prometheus:   http://localhost:9090
Grafana:       http://localhost:3000
Alertmanager:  http://localhost:9093
Bank metrics:  http://localhost:8082/actuator/prometheus
Shop metrics:  http://localhost:8083/actuator/prometheus
~~~

W Prometheus wybierz Status -> Target health. Oczekiwany wynik to:

~~~text
bank-demo: UP
shop-app:  UP
~~~

401 oznacza niezgodne dane Basic Auth, connection refused oznacza, że
aplikacja nie słucha na porcie management, a context deadline exceeded
oznacza problem z siecią między WSL i procesem uruchomionym w Windows.
Nie zmieniaj na ślepo host.docker.internal: przy bieżącym trybie sieci
Prometheus korzysta z localhost, a przy innym trybie WSL trzeba sprawdzić
adres zgodnie z monitoring-preparation.md.

W Grafanie otwórz dashboard NovaBank - przegląd usług. Puste panele mogą
oznaczać brak ruchu w wybranym zakresie czasu, a nie awarię. W Alertmanagerze
sprawdzaj aktywne alerty:

- BankOrShopDown - aplikacja lub endpoint metrics jest niedostępny;
- HighHttp5xxRate - wzrost odpowiedzi HTTP 5xx;
- HighJvmMemoryUsage - wysoki poziom pamięci JVM;
- PrometheusTargetErrors - Prometheus nie może pobrać metryk;
- RetentionJobFailed - błąd zadania retencji;
- CertificateExpiryWarning - zbliża się termin wygaśnięcia certyfikatu.

Logi i zatrzymanie:

~~~bash
bash scripts/monitoring/monitoring.sh logs
bash scripts/monitoring/monitoring.sh logs prometheus
bash scripts/monitoring/monitoring.sh stop
~~~

Monitoring nie uruchamia się sam po restarcie komputera, chyba że użytkownik
skonfiguruje autostart Compose. W DEV można go uruchamiać tylko na czas pracy.
W PROD endpointy Actuatora muszą być ograniczone do sieci monitoringu i konta
technicznego. Hasło Grafany, hasło metrics i pliki wygenerowane przez skrypt
pozostają poza repozytorium.

Na Windows z WSL skrypt otworzy przewodnik w domyślnej przeglądarce Windows. Jeżeli przeglądarka nie uruchomi się automatycznie, skrypt poda dokładną ścieżkę do pliku `docs/interactive-guide.html`.

Następnie uruchom NovaBank Control Center:

```bash
bash scripts/control-center/control-center.sh
```

Na Windows można użyć także PowerShell:

```powershell
.\scripts\control-center\start.ps1
```

W Centrali wybierz `Pierwsze uruchomienie`. Ta sekcja sprawdza wymagania i prowadzi przez przygotowanie lokalnych plików konfiguracyjnych, kontenerów oraz profilu. `Instalacja i wymagania` służy do sprawdzania lub instalowania komponentów, a pozostałe sekcje uruchamiają konkretne operacje, takie jak monitoring, backup, Vault, testy i diagnostyka.

Ważny podział odpowiedzialności:

- Control Center może wykryć środowisko, utworzyć lokalne katalogi i pliki szablonowe, wygenerować sekrety DEV oraz uruchomić kontrolowane operacje po potwierdzeniu użytkownika.
- Użytkownik musi samodzielnie zabezpieczyć hasła, pięć kluczy unseal i root token Vault oraz uzupełnić sekrety PROD zgodnie z instrukcją. Nie zapisuj ich w repozytorium ani w zrzutach ekranu.
- Aplikacje banku i sklepu uruchamia się z IntelliJ IDEA przez gotowe konfiguracje `NovaBank DEV`, `NovaBank PROD`, `Bank DEV`, `Bank PROD`, `Shop DEV` i `Shop PROD`.

Jeżeli korzystasz wyłącznie z profilu `dev`, nie musisz konfigurować Vault ani Stripe CLI. Wystarczą Docker Engine z Compose, PostgreSQL i Redis uruchomione przez Compose, plik `.novabank/env/project.env`, plik `.novabank/env/dev.env` oraz konfiguracja IntelliJ z aktywnym profilem `dev`.

### Vault, TLS i AppRole: pełny przebieg

Vault jest magazynem sekretów używanych przez profil `prod`. Przechowuje między innymi
hasła baz, klucze API, dane operatora płatności, klucze TOTP oraz hasła do keystore.
Nie jest bazą aplikacji i nie zastępuje PostgreSQL. Po restarcie komputera usługa Vault
może działać, ale magazyn pozostaje zapieczętowany, dopóki nie zostanie odpieczętowany
trzema różnymi udziałami klucza.

Sprawdź usługę i przygotuj bieżącą sesję WSL:

~~~bash
sudo systemctl status vault --no-pager
export VAULT_ADDR="https://127.0.0.1:8200"
unset VAULT_CAPATH
unset VAULT_SKIP_VERIFY
export VAULT_CACERT="/mnt/c/Dysk D/stuff/Bank-demo/.novabank/tls/vault/vault.crt"
vault status
~~~

`VAULT_ADDR` wskazuje adres serwera Vault, a `VAULT_CACERT` plik certyfikatu,
którym klient ma zweryfikować listener HTTPS. Eksport działa tylko w bieżącym
oknie WSL. Control Center ustawia te wartości dla własnych operacji, ale przy
bezpośrednim użyciu polecenia `vault` należy je ustawić ponownie w nowej sesji.

Jeżeli pojawi się `certificate signed by unknown authority`, porównaj odciski:

~~~bash
echo | openssl s_client -connect 127.0.0.1:8200 -servername localhost 2>/dev/null | openssl x509 -noout -fingerprint -sha256 -subject
sudo openssl x509 -in /opt/vault/tls/tls.crt -noout -fingerprint -sha256 -subject
sudo openssl x509 -in /opt/vault/tls/vault.crt -noout -fingerprint -sha256 -subject
~~~

Certyfikat, którego odcisk odpowiada odciskowi listenera, skopiuj do repozytorium
lokalnego poza kontrolą sekretów. Typowy wariant dla instalacji opisanej w projekcie:

~~~bash
sudo chmod 0755 /opt/vault/tls
sudo chmod 0644 /opt/vault/tls/tls.crt
sudo cp /opt/vault/tls/tls.crt "/mnt/c/Dysk D/stuff/Bank-demo/.novabank/tls/vault/vault.crt"
sudo chown "$(id -u):$(id -g)" "/mnt/c/Dysk D/stuff/Bank-demo/.novabank/tls/vault/vault.crt"
export VAULT_CACERT="/mnt/c/Dysk D/stuff/Bank-demo/.novabank/tls/vault/vault.crt"
vault status
~~~

Nie ustawiaj `VAULT_SKIP_VERIFY`. Jeżeli WSL nie może odczytać `/opt/vault/tls`,
problem dotyczy uprawnień do katalogu lub pliku, a nie hasła Vault. Zmianę uprawnień
wykonuj świadomie i tylko dla lokalnego środowiska edukacyjnego.

Możliwe wyniki `vault status`:

- `Initialized false` oznacza, że Vault nie został jeszcze zainicjalizowany;
- `Sealed true` oznacza, że trzeba wykonać odpieczętowanie;
- `Sealed false` oznacza, że można używać sekretów i AppRole;
- błąd TLS oznacza problem z `VAULT_CACERT`, odciskiem lub prawami do certyfikatu;
- błąd `503 Vault is sealed` oznacza, że najpierw trzeba odpieczętować magazyn.

Klucze startowe są przechowywane zaszyfrowane w pliku `vault-init.json.gpg`.
Hasło GPG jest hasłem ustalonym podczas szyfrowania tego pliku; nie musi być takie
jak hasło użytkownika Vault. Odczyt kluczy i tokena wykonuj tylko lokalnie:

~~~bash
export RECOVERY_DIR="/mnt/e/Vault-Recovery"
gpg --quiet --pinentry-mode loopback \
  --decrypt "$RECOVERY_DIR/vault-init.json.gpg" |
  jq -e '(.unseal_keys_b64 | length) == 5 and (.root_token | length) > 0'
~~~

GPG poprosi o hasło do pliku. To samo hasło należy podać przy każdym odczycie
zaszyfrowanego pliku, ale nie należy wpisywać go do `.env` ani do repozytorium.
Następnie odpieczętuj Vault trzema różnymi udziałami, jeden po drugim:

~~~bash
vault operator unseal
vault operator unseal
vault operator unseal
~~~

Każde polecenie prosi o inny `unseal key`. Nie używaj do tego `root token`.
Root token służy do pierwszej administracji, na przykład `vault login`, konfiguracji
polityk i AppRole. Po zakończeniu administracji wyloguj token i nie przekazuj go
aplikacjom.

AppRole rozdziela dostęp banku i sklepu. Bank korzysta z roli `bank-prod`, a sklep
z `shop-prod`. Aplikacja otrzymuje `VAULT_ROLE_ID` i krótkotrwały
`VAULT_SECRET_ID`, a następnie pobiera z Vault tylko sekrety dozwolone polityką.
Secret ID nie jest root tokenem. W bieżącej konfiguracji ma ograniczony czas życia
i liczbę użyć, więc rotuj go przed wygaśnięciem:

~~~bash
bash scripts/vault/approle-secret-id.sh bank-prod
bash scripts/vault/approle-secret-id.sh shop-prod
~~~

Można też wybrać odpowiednią rotację w NovaBank Control Center. Po rotacji wpisz
nowe wartości do lokalnych plików środowiskowych PROD, wykonaj test połączenia,
a dopiero potem zakończ ważność starego Secret ID. Nigdy nie wyświetlaj Secret ID
w logu ani na screenie.

`VAULT_SSL_TRUST_STORE_PASSWORD` powstaje podczas tworzenia truststore. Uruchomienie
komendy `keytool -importcert ...` poprosi o hasło: jest to moment ustalenia hasła
dla pliku truststore, a nie odczyt wcześniej istniejącego hasła. To samo hasło
trzeba potem przekazać aplikacji w odpowiednim pliku `.novabank/env/prod.bank.env`
i `.novabank/env/prod.shop.env`. Skrypt `scripts/generate-prod-tls-keystores.ps1`
również poprosi o osobne hasła dla keystore banku i sklepu. Zachowaj te wartości
poza repozytorium. Certyfikat zaufania i keystore muszą odpowiadać aktywnemu
listenerowi oraz profilowi, który uruchamiasz.

### Płatności i różnica między `bank-demo` a Stripe

Sklep ma dwa tryby płatności wybierane przez `SHOP_PAYMENT_PROVIDER`:

- `bank-demo` kieruje użytkownika do banku, weryfikuje sesję, rachunek, status,
  saldo, walutę i limity, a następnie wykonuje demonstracyjny transfer z rachunku
  klienta na rachunek sklepu. Ten tryb sprawdza pełną komunikację shop-app z bankiem;
- `stripe` tworzy testową płatność w zewnętrznym środowisku Stripe. Stripe CLI
  przekazuje webhooki do shop-app i aktualizuje status zamówienia, ale nie pobiera
  środków z rachunku NovaBank ani nie zasila rachunku sklepu w bankowej bazie.

Tryb `bank-demo` nie wymaga Stripe CLI. Dla Stripe przygotuj w `.novabank/env/dev.env`
testowy klucz API i sekret webhooka, a następnie uruchom osobne nasłuchiwanie:

~~~bash
stripe listen --forward-to http://localhost:8081/api/payments/stripe/webhook
~~~

Wynik `Ready!` i `whsec_...` oznacza, że CLI działa. Wartość `whsec_...` wpisz
do konfiguracji shop-app i uruchom sklep ponownie. Logi `payment_intent.succeeded`,
`charge.succeeded` i `checkout.session.completed` pokazują zdarzenia Stripe.
Każde zdarzenie powinno kończyć się odpowiedzią HTTP `200`; `400` wymaga sprawdzenia
sekretu webhooka, identyfikatora sesji lub obsługi danego typu zdarzenia, a
`connection refused` oznacza, że shop-app nie działa na porcie 8081.

W obu trybach zamówienie jest chronione idempotencją. Ponowienie tego samego żądania
z identycznym `Idempotency-Key` nie powinno pobrać środków ani utworzyć drugiej
autoryzacji. Statusy `PENDING`, `PAID` i `FAILED` są potwierdzane przez webhook,
bank albo zadanie uzgadniania. Przy refundacji używany jest osobny klucz idempotencji.

### OpenAPI, Swagger UI i Postman

Dokumentacja OpenAPI pomaga poznać kontrakt HTTP, a Postman służy do ręcznego
wykonywania i powtarzania zapytań. Swagger nie wersjonuje kodu ani bazy danych;
wersjonowanie API odbywa się przez kontrakt, ścieżki, tagi i historię zmian.

Adresy lokalne:

~~~text
Bank Swagger UI:  http://localhost:8080/swagger-ui/index.html
Bank OpenAPI:     http://localhost:8080/v3/api-docs
Sklep Swagger UI: http://localhost:8081/swagger-ui/index.html
Sklep OpenAPI:    http://localhost:8081/v3/api-docs
~~~

W `dev` i `test` dokumentacja jest dostępna bez roli administratora. W `prod`
wymaga `ROLE_ADMIN`, ponieważ opisuje endpointy aplikacji.

Przykładowe sprawdzenie logowania banku w Postmanie:

1. Utwórz żądanie `POST http://localhost:8080/login`.
2. W `Body` wybierz `x-www-form-urlencoded`.
3. Dodaj pola `username`, `password` i `_csrf`.
4. Token `_csrf` pobierz wcześniej z HTML strony logowania albo z ciasteczka/
   odpowiedzi zgodnie z aktualną konfiguracją formularza.
5. Włącz przechowywanie cookies dla hosta `localhost` i sprawdź przekierowanie
   do dashboardu. Błąd CSRF oznacza nieaktualny token lub brak cookies sesji.

Dla API sklepu zawsze używaj zalogowanej sesji, nagłówka CSRF dla metod zmieniających
stan oraz `Idempotency-Key` przy tworzeniu płatności. Nie wpisuj numerów kart,
CVV ani dat ważności do logów. W trybie `bank-demo` używaj wyłącznie danych
demonstracyjnych opisanych w aplikacji.

### Backup PostgreSQL, Disaster Recovery i retencja

PostgreSQL jest źródłem danych finansowych i biznesowych. Redis przechowuje sesje,
stan tymczasowy i cache, dlatego backup PostgreSQL nie jest backupem Redis.
Przed kopią uruchom kontenery `postgres`, `shop-postgres` i `redis`, a następnie:

~~~bash
cd "/mnt/c/Dysk D/stuff/Bank-demo"
bash scripts/backup/backup.sh create
~~~

Skrypt poprosi o katalog zapisu. Każde uruchomienie tworzy nowy podkatalog z datą
i czasem UTC, więc nie nadpisuje poprzedniej kopii. Wykonuje kolejno kontrolę
środowiska, dump banku i sklepu, sumy SHA-256, testowe odtworzenie do tymczasowych
baz oraz raport `backup-report.log`. Sukces wymaga statusów `OK` dla backupu, sum
i testu odtworzenia. Raport zawsze pokazuje pełną ścieżkę zapisu.

Przydatne warianty:

~~~bash
bash scripts/backup/backup.sh create --backup-dir /mnt/e/Bank-demo-backups/postgres
bash scripts/backup/backup.sh create --skip-restore-test
bash scripts/backup/backup.sh prune
bash scripts/backup/backup.sh dr-test --backup-dir /mnt/e/Bank-demo-backups/postgres
~~~

`dr-test` wybiera najnowszą kopię, odtwarza ją w odseparowanym środowisku,
sprawdza migracje i testy HTTP, a wynik zapisuje w raporcie. Uruchamiaj go
cyklicznie, najlepiej raz w tygodniu, i kontroluj, czy raport nie kończy się
statusem `BLAD`. Retencja usuwa tylko dane techniczne i audytowe zgodnie z
konfiguracją; nie usuwa kart, sald, przelewów, zamówień ani refundacji.

W profilu `dev` najpierw używaj `RETENTION_DRY_RUN=true`. Dopiero po sprawdzeniu
raportu można świadomie włączyć usuwanie (`RETENTION_DRY_RUN=false`). Panel
administracyjny znajduje się pod adresem:

~~~text
http://localhost:8080/admin/data-retention
~~~

Panel zapisuje wynik w audycie. Kontroluj także metryki `bank_retention_deleted_total`
i `bank_retention_errors_total` oraz alert `RetentionJobFailed`.

### Profile i codzienny wybór środowiska

`dev` służy do ręcznej pracy w IntelliJ. Uruchamiaj PostgreSQL i Redis przez WSL,
ładuj `.novabank/env/dev.env`, ustaw aktywny profil `dev` dla banku i sklepu,
a następnie uruchom konfigurację `NovaBank DEV` lub oba procesy osobno.
Monitoring i Stripe są opcjonalne.

`test` służy do automatycznych testów. Profile Maven `unit-tests` i
`integration-tests` uruchamiaj przez wrappera Maven; testy integracyjne używają
Testcontainers i nie powinny korzystać z danych konta `dev`:

~~~bash
./mvnw -Punit-tests test
./mvnw -Pintegration-tests verify
./mvnw -f shop-app/pom.xml -Punit-tests test
./mvnw -f shop-app/pom.xml -Pintegration-tests verify
~~~

`prod` służy do uruchomienia z TLS i Vault. Przed startem muszą działać Vault,
PostgreSQL i Redis, Vault musi być odpieczętowany, a role AppRole i sekrety muszą
być dostępne. Używaj konfiguracji `NovaBank PROD`/`Shop PROD`, nie pliku dev.
Wymagane są również keystore, truststore, `VAULT_ROLE_ID`, `VAULT_SECRET_ID`,
`VAULT_SSL_TRUST_STORE_PASSWORD` i konfiguracja `TLS_TERMINATION=app` albo
reverse proxy zgodnie z przygotowaniem profilu.

Zmiana profilu w IntelliJ nie uruchamia automatycznie zależności. Kolejność to:

1. uruchom infrastrukturę WSL/Compose;
2. przygotuj plik środowiskowy wybranego profilu;
3. dla `prod` sprawdź Vault, TLS i AppRole;
4. ustaw profil i EnvFile w konfiguracji IntelliJ;
5. uruchom bank i shop-app;
6. sprawdź health, logi i dopiero potem wykonuj płatności lub testy.

### NovaBank Control Center jako centrum obsługi

Control Center nie jest kolejną aplikacją biznesową. Jest menu uruchamiającym
istniejące skrypty i pokazującym ich wynik. Start z WSL:

~~~bash
bash scripts/control-center/control-center.sh
~~~

Start z PowerShell:

~~~powershell
.\scripts\control-center\start.ps1
~~~

Najważniejsze grupy menu to: `Pierwsze uruchomienie`, `Środowiska i aplikacje`,
`Instalacja i wymagania`, `Monitoring`, `Kopie zapasowe PostgreSQL`,
`Disaster Recovery`, `Vault i AppRole`, `TLS i dokumenty generowane`, `Testy
aplikacji`, `Instrukcje`, `Diagnostyka całego środowiska` i `Utrzymanie aplikacji`.

Przed wykonaniem operacji Centrala pokazuje komendę i dokument główny. Wpisz `t`,
aby potwierdzić; sam Enter anuluje operację. Po zakończeniu przeczytaj raport,
zapisz ścieżkę dziennika i naciśnij Enter, aby wrócić do menu. `exit` kończy
pracę Centrali. Dziennik aktywności znajduje się w `.novabank/control-center`.

`Pierwsze uruchomienie` sprawdza ścieżkę projektu, tworzy katalog `.novabank`,
przygotowuje szablony `project.env`, `dev.env`, pliki PROD i monitoring oraz
wyświetla mapę sekretów. DEV może zostać wygenerowany automatycznie. W PROD
Centrala tworzy pliki, ale użytkownik sam wpisuje hasła i klucze; nie powinny
one nigdy trafić do repozytorium.

Jeżeli operacja kończy się błędem, nie ponawiaj jej w ciemno. Najpierw odczytaj
komendę, plik instrukcji, kod zakończenia i dziennik. Typowe następne kroki:

- `401` przy metrykach: sprawdź wspólne `METRICS_USERNAME`/`METRICS_PASSWORD`;
- `context deadline exceeded`: sprawdź adres WSL/Windows i port management;
- `JAVA_HOME ... not defined correctly`: uruchom Centralę w środowisku z JDK
  albo ustaw `JAVA_HOME` wskazujący na JDK, nie na samo JRE;
- `Vault is sealed`: odpieczętuj trzy udziały i ponów operację;
- błąd certyfikatu Vault: sprawdź odcisk listenera i `VAULT_CACERT`;
- `ModuleNotFoundError: reportlab`: zainstaluj zależności z
  `scripts/requirements-pdf.txt` w środowisku Python używanym do PDF;
- `connection refused` na 8080/8081: uruchom aplikację w IntelliJ i sprawdź logi;
- nieudany backup: zachowaj raport i nie usuwaj katalogu kopii do czasu diagnozy.

### Ścieżka projektu na innym komputerze

Przykłady zawierające `/mnt/c/Dysk D/stuff/Bank-demo` odnoszą się do bieżącej instalacji. Jeżeli repozytorium znajduje się w innym katalogu lub na innym systemie, najpierw przejdź do jego katalogu, a następnie uruchamiaj polecenia względne, na przykład `bash scripts/control-center/control-center.sh`. Skrypty Control Center i interaktywny przewodnik wyznaczają katalog projektu na podstawie własnego położenia. Stałe ścieżki `/mnt/c/...` trzeba podmienić tylko w poleceniach, które są uruchamiane bezpośrednio z konfiguracji WSL, IntelliJ, Prometheusa albo Vault.

## 1. Architektura i używane narzędzia

Projekt zawiera dwa serwisy Spring Boot:

| Serwis | Funkcje | Port aplikacji | Port Actuatora |
|---|---|---:|---:|
| Bank-demo | konta, rachunki, salda, przelewy, karty, kantor, 2FA, administracja | 8080 | 8082 |
| shop-app | produkty, koszyk, zamówienia, checkout, refundacje | 8081 | 8083 |

Infrastruktura:

| Kontener | Funkcja | Port hosta |
|---|---|---:|
| bank-postgres | baza bankdb, użytkownik bankuser | 5300 |
| shop-postgres | baza shopdb, użytkownik shopuser | 5400 |
| bank-redis | sesje banku, rate limiting, dane tymczasowe | 6379 |

WSL 2 zapewnia środowisko Linux na Windows 11. Docker Engine, docker compose, Vault oraz Stripe CLI są używane z WSL. Projekt po stronie WSL znajduje się w:

    /mnt/c/Dysk D/stuff/Bank-demo

Docker Compose:

- docker-compose.yaml uruchamia PostgreSQL i Redis;
- docker-compose.monitoring.yaml uruchamia Prometheus, Grafanę i Alertmanager;
- oba pliki są niezależne;
- w tym środowisku nie używamy Docker Desktop.

PostgreSQL przechowuje dane biznesowe. Bank i sklep mają osobne bazy. Redis przechowuje sesje, rate limiting i krótkotrwałe wyzwania, ale nie jest kopią danych PostgreSQL.

Flyway wykonuje migracje przy starcie aplikacji. Migracje znajdują się w:

    src/main/resources/db/migration
    shop-app/src/main/resources/db/migration

Historia migracji jest zapisywana w flyway_schema_history. Nie usuwaj tej tabeli ręcznie i nie używaj docker compose down -v bez świadomej decyzji o skasowaniu danych.

Maven kompiluje kod i uruchamia testy. Projekt korzysta między innymi z Spring Web, Thymeleaf, Spring Security, JPA/JDBC, Spring Session, PostgreSQL, Flyway, Actuator, Micrometer, OpenAPI, Vault, TOTP, PDFBox i Testcontainers.

Vault przechowuje sekrety profilu prod. Prometheus pobiera metryki, Grafana tworzy dashboardy, a Alertmanager pokazuje alerty. CI/CD z pliku .github/workflows/ci.yml buduje oba serwisy i uruchamia testy automatyczne.

## 2. Dane, które trzeba zapisać

Nie wpisuj poniższych wartości do tej instrukcji, Git ani zrzutów ekranu. Przechowuj je w menedżerze haseł lub zaszyfrowanym magazynie:

1. Hasła baz bank-postgres i shop-postgres.
2. Hasło do pliku odzyskiwania Vault.
3. Wszystkie 5 kluczy unseal Vault. Do odpieczętowania potrzebne są 3 różne klucze, ale trzeba zachować wszystkie 5.
4. Root token Vault. Służy do administracji Vault, nie do runtime aplikacji.
5. BANK_ROLE_ID, BANK_SECRET_ID, SHOP_ROLE_ID i SHOP_SECRET_ID.
6. VAULT_SSL_TRUST_STORE_PASSWORD.
7. Hasła keystore bank-server.p12 i shop-server.p12.
8. TOTP_ENCRYPTION_KEY.
9. BANK_API_KEYS oraz klucz używany przez sklep.
10. METRICS_USERNAME i wspólne METRICS_PASSWORD.
11. Hasło administratora Grafany.
12. SHOP_PAYMENT_API_KEY i SHOP_PAYMENT_WEBHOOK_SECRET, jeżeli używasz Stripe.
13. Numer dedykowanego, aktywnego rachunku sklepu.
14. Identyfikatory kluczy oraz daty ich wygaśnięcia.

### 2.1. Pliki lokalne, ignorowane przez Git

Poniższe pliki nie powinny być commitowane, ale są potrzebne w konkretnych wariantach uruchomienia:

| Plik | Kiedy potrzebny | Jak utworzyć |
|---|---|---|
| .novabank/env/project.env | zawsze do docker-compose.yaml, zawiera hasła baz | skopiuj .env.example do .novabank/env/project.env |
| .novabank/env/dev.env | dev uruchamiany z IntelliJ lub start-dev.ps1 | uruchom generate-dev-env.ps1 |
| .novabank/env/prod.bank.env | prod banku | utwórz z placeholderów i wpisz dane AppRole |
| .novabank/env/prod.shop.env | prod sklepu | utwórz z placeholderów i wpisz dane AppRole |
| .novabank/monitoring/.env.monitoring | uruchomienie Grafany i monitoringu dev | wygeneruj przez scripts/monitoring/monitoring.sh setup |
| .novabank/monitoring/prometheus.yml | Prometheus ze wspólnym hasłem metryk | wygeneruj przez scripts/monitoring/monitoring.sh setup |
| .novabank/tls/vault/vault.crt | zaufanie do lokalnego HTTPS Vault | wygeneruj lub skopiuj certyfikat Vault |
| .novabank/tls/vault/vault-truststore.p12 | połączenie Java z Vault po HTTPS | utwórz przez keytool |
| .novabank/tls/prod/bank-server.p12 | HTTPS banku w prod | wygeneruj skryptem TLS |
| .novabank/tls/prod/shop-server.p12 | HTTPS sklepu w prod | wygeneruj skryptem TLS |
| katalog backupów | kopie PostgreSQL | ustaw BACKUP_DIR poza repozytorium |

### 2.2. Zrzuty ekranu demonstracyjnego

Zrzuty ekranu dołączone do instrukcji mogą przedstawiać przykładowe konta, rachunki,
transakcje i dane płatności. Są przeznaczone wyłącznie do lokalnego środowiska DEMO.

Kod QR oraz ręczny sekret Google Authenticator widoczny na zrzucie należy traktować
jak sekret: nie używaj go ponownie po odtworzeniu środowiska, nie stosuj go w profilu
PROD i po udostępnieniu zrzutu wygeneruj nowe powiązanie 2FA. Zrzuty nie mogą zawierać
prawdziwych danych klientów, kart ani sekretów produkcyjnych.

Pierwsza konfiguracja głównego pliku środowiskowego w WSL:

    cd "/mnt/c/Dysk D/stuff/Bank-demo"
    mkdir -p .novabank/env
    cp .env.example .novabank/env/project.env
    chmod 600 .novabank/env/project.env

Następnie w `.novabank/env/project.env` zmień co najmniej `POSTGRES_PASSWORD` i `SHOP_POSTGRES_PASSWORD`. Te hasła muszą odpowiadać konfiguracji używanej przez Docker Compose. Nie uruchamiaj `docker-compose.yaml` z przykładowymi wartościami `change-me`.

W PowerShell kopię można wykonać tak:

    New-Item -ItemType Directory -Force ".novabank\env" | Out-Null
    Copy-Item ".env.example" ".novabank\env\project.env"

Plik .novabank/env/dev.env utwórz poleceniem:

    .\scripts\generate-dev-env.ps1

Pliki prod nie są generowane przez skrypt dev. Wypełnia się je lokalnym bootstrapem Vault, bez wpisywania do nich sekretów biznesowych przechowywanych w Vault.

Pliki monitoringu dev utwórz tylko przy konfiguracji monitoringu:

    bash scripts/monitoring/monitoring.sh setup

Skrypt zachowuje istniejące poprawne sekrety i adresy targetów. Nie uruchamiaj `setup --force`, jeżeli nie chcesz obrócić lokalnych haseł metryk i Grafany.

Pliki `.novabank/env/*.env`, lokalny `prometheus.yml`, materiały TLS Vault, keystory prod i backupy są objęte `.gitignore`. W repozytorium powinny pozostać tylko pliki przykładowe, skrypty, konfiguracja bez sekretów i dokumentacja.

## 3. Pierwszy start WSL i kontenerów

W PowerShell otwórz WSL:

    wsl

W WSL sprawdź:

    uname -a
    docker version
    docker compose version

Przejdź do projektu:

    cd "/mnt/c/Dysk D/stuff/Bank-demo"

Uruchom bazy i Redis:

    docker compose --env-file .novabank/env/project.env up -d postgres shop-postgres redis

Sprawdź:

    docker compose ps
    docker ps
    docker exec bank-postgres pg_isready -U bankuser -d bankdb
    docker exec shop-postgres pg_isready -U shopuser -d shopdb
    docker exec bank-redis redis-cli ping

Oczekiwane odpowiedzi to accepting connections, accepting connections i PONG.

Porty w WSL:

    ss -ltnp | grep -E ':5300|:5400|:6379'

Porty w PowerShell:

    Get-NetTCPConnection -LocalPort 5300,5400,6379 -State Listen

Zatrzymanie bez kasowania danych:

    docker compose stop

Usunięcie kontenerów z pozostawieniem wolumenów:

    docker compose down

Nie używaj docker compose down -v, jeśli nie chcesz usunąć danych.

## 4. Konta i role

### 4.1. Konto bankowe użytkownika

Rejestracja:

    http://localhost:8080/register

Logowanie:

    http://localhost:8080/login?logout

Utwórz konto z e-mailem, hasłem i wymaganymi danymi. W dev e-maile są domyślnie logowane. Rachunek używany do przelewów i płatności musi mieć status ACTIVE. BLOCKED blokuje logowanie i operacje. CLOSED zamyka rachunek, ale dane pozostają zachowane.

### 4.2. Administrator banku

Rejestracja tworzy USER. Rola ADMIN jest nadawana przez AdminRoleInitializer na podstawie dokładnego adresu e-mail ustawionego jako ADMIN_EMAIL.

Kolejność w dev:

1. Wejdź na /register i załóż konto administratora.
2. Ustaw ADMIN_EMAIL na dokładnie ten sam adres w konfiguracji banku.
3. Zrestartuj bank.
4. Wyloguj się i zaloguj ponownie.
5. Otwórz panel:

    http://localhost:8080/admin

W prod ADMIN_EMAIL musi pochodzić z Vault. Konto powinno istnieć przed uruchomieniem inicjalizatora albo trzeba uruchomić bank ponownie po utworzeniu konta.

Panel administratora służy do wyszukiwania użytkowników, blokowania/aktywacji/zamykania rachunków, wylogowania sesji użytkownika, kursów walut, audytu i retencji.

### 4.3. Konto sklepu

Konto sklepu nie jest tym samym kontem co konto bankowe. Rejestracja:

    http://localhost:8081/shop-register.html

Logowanie:

    http://localhost:8081/shop-login.html

Rejestracja wymaga e-maila, hasła i powtórzenia hasła. Numer rachunku bankowego jest potrzebny dopiero przy checkout.

### 4.4. Administrator sklepu

Shop ma role CUSTOMER i ADMIN. Nowe konto otrzymuje CUSTOMER. W dev nadaj administratora SQL-em:

    docker exec -it shop-postgres psql -U shopuser -d shopdb

W psql:

    UPDATE shop_users SET role = 'ADMIN'
    WHERE email = LOWER(BTRIM('admin@example.test'));

    SELECT email, role, active FROM shop_users
    WHERE email = LOWER(BTRIM('admin@example.test'));

Wyjdź przez \q, wyloguj się i zaloguj ponownie. Używaj shopuser, nie zakładaj obecności roli postgres.

### 4.5. Rachunek sklepu

Dla płatności bank-demo potrzebny jest dedykowany rachunek NovaBank:

- rachunek musi istnieć w banku;
- status musi być ACTIVE;
- najlepiej przeznaczyć go wyłącznie dla handlowca;
- numer musi odpowiadać SHOP_MERCHANT_ACCOUNT_NUMBER.

Nie używaj prywatnego rachunku klienta jako rachunku sklepu.

## 5. Profil dev

Profil dev używa lokalnych baz WSL, HTTP, Redis i pliku .novabank/env/dev.env. Nie wpisuj spring.profiles.active do application.yaml.

### 5.1. Generowanie sekretów

PowerShell:

    Set-Location "C:\Dysk D\stuff\Bank-demo"
    .\scripts\generate-dev-env.ps1

Rotacja lokalnego klucza:

    .\scripts\generate-dev-env.ps1 -Force

Skrypt podmienia lokalny klucz w .novabank/env/dev.env. Po zmianie zrestartuj bank i sklep. Dla trybu dev korzystającego z pojedynczego BANK_API_KEY nie dopisuj wpisów po średniku do BANK_API_KEYS.

### 5.2. Bank w IntelliJ

W Run | Edit Configurations | BankDemoApplication ustaw:

1. klasę org.demo.bankdemo.BankDemoApplication;
2. aktywny profil Spring dev;
3. Enable EnvFile;
4. plik .novabank/env/dev.env;
5. katalog roboczy projektu;
6. port 8080.

### 5.3. Shop w IntelliJ

W ShopAppApplication ustaw:

1. klasę org.demo.shopapp.ShopAppApplication;
2. aktywny profil Spring dev;
3. Enable EnvFile;
4. ten sam plik .novabank/env/dev.env;
5. katalog roboczy projektu;
6. port 8081.

Najpierw uruchom bank, następnie shop-app. Można utworzyć Compound configuration.

Alternatywnie w PowerShell:

    $env:SPRING_PROFILES_ACTIVE = "dev"

Gotowy skrypt:

    Set-Location "C:\Dysk D\stuff\Bank-demo"
    .\scripts\start-dev.ps1

Zatrzymywanie procesów uruchomionych skryptem:

    .\scripts\stop-dev.ps1

### 5.4. Linki dev

Bank:

    http://localhost:8080/login?logout
    http://localhost:8080/register
    http://localhost:8080/dashboard
    http://localhost:8080/profile
    http://localhost:8080/profile/limits
    http://localhost:8080/profile/2fa
    http://localhost:8080/profile/password
    http://localhost:8080/admin
    http://localhost:8080/admin/data-retention

Sklep:

    http://localhost:8081/novatech_shop.html
    http://localhost:8081/shop-login.html
    http://localhost:8081/shop-register.html
    http://localhost:8081/product.html?id=<ID_PRODUKTU>

Swagger/OpenAPI:

    http://localhost:8080/swagger-ui/index.html
    http://localhost:8080/v3/api-docs
    http://localhost:8081/swagger-ui/index.html
    http://localhost:8081/v3/api-docs

Actuator banku:

    http://localhost:8082/actuator/health
    http://localhost:8082/actuator/health/liveness
    http://localhost:8082/actuator/health/readiness
    http://localhost:8082/actuator/info
    http://localhost:8082/actuator/metrics
    http://localhost:8082/actuator/prometheus

Actuator sklepu:

    http://localhost:8083/actuator/health
    http://localhost:8083/actuator/health/liveness
    http://localhost:8083/actuator/health/readiness
    http://localhost:8083/actuator/info
    http://localhost:8083/actuator/metrics
    http://localhost:8083/actuator/prometheus

W PowerShell używaj curl.exe, ponieważ curl jest aliasem Invoke-WebRequest:

    curl.exe http://localhost:8082/actuator/health
    curl.exe http://localhost:8081/novatech_shop.html
    Invoke-RestMethod -UseBasicParsing http://localhost:8082/actuator/health

### 5.5. Funkcje banku

Typowa ścieżka:

1. Zaloguj użytkownika.
2. Sprawdź saldo i rachunek.
3. Dodaj odbiorcę lub wybierz istniejącego.
4. Wykonaj przelew.
5. Poniżej progu użyj PIN-u transakcyjnego, jeśli jest skonfigurowany.
6. Dla nowego odbiorcy, nowego urządzenia lub kwoty ponad próg użyj kodu Google Authenticator.
7. Sprawdź historię i pobierz CSV/PDF.

Maksymalny konfigurowalny próg TOTP aplikacji to 5000.00 PLN. Zwykła nowa sesja na rozpoznanym urządzeniu nie powinna sama wymuszać TOTP. Zmiana hasła, blokada rachunku lub zamknięcie rachunku unieważnia sesje.

### 5.6. Funkcje sklepu

Typowa ścieżka:

1. Wejdź na novatech_shop.html.
2. Zaloguj się do konta sklepu.
3. Otwórz produkt i dodaj go do koszyka.
4. Przejdź do checkout.
5. Wybierz NovaBank albo płatność kartą.
6. Dla NovaBank zaloguj się do banku i potwierdź.
7. Dla bank-demo wybierz walutę i użyj wewnętrznego formularza karty.
8. Dla Stripe przejdź do hostowanego checkoutu i użyj karty testowej.
9. Po sukcesie zamówienie otrzymuje PAID, a koszyk jest opróżniany.

### 5.7. Co włącza i wyłącza każdy profil

| Obszar | dev | test | prod |
|---|---|---|---|
| Źródło sekretów | .novabank/env/dev.env i EnvFile IntelliJ | wartości testowe/Testcontainers | Vault przez AppRole |
| Vault | wyłączony | wyłączony | wymagany i odpieczętowany |
| Bazy | bankdb/shopdb z WSL | tymczasowe bazy testowe | bazy prod z Vault |
| Redis | wymagany dla sesji/rate limitingu | kontener Testcontainers lub testowy Redis | wymagany |
| HTTP/TLS | HTTP, Secure cookie wyłączone | HTTP, porty losowe | HTTPS, Secure cookie włączone |
| Stripe | opcjonalny | zwykle wyłączony | opcjonalny, sekret z Vault |
| bank-demo | domyślny provider | domyślny provider testowy | wybór zapisany w Vault |
| rate limiting | włączony | ograniczenia podniesione albo wyłączone w testach | włączony |
| retencja | wyłączona, dry-run | wyłączona | włączać kontrolowanie |
| monitoring | opcjonalny | zwykle niepotrzebny | zalecany, z ograniczonym Actuatorem |
| Swagger | dostępny dla pracy lokalnej | dostępny dla testów | tylko administrator |
| sesje cookie | HttpOnly, SameSite, bez Secure | HttpOnly, SameSite, bez Secure | HttpOnly, SameSite i Secure |

W dev włącz plik `.novabank/env/dev.env`. W test nie włączaj pliku dev, bo test może dostać przypadkowy dostęp do lokalnej bazy. W prod włącz odpowiedni plik bootstrap `.novabank/env/prod.bank.env` albo `.novabank/env/prod.shop.env`; sekrety biznesowe są pobierane z Vault. Nie uruchamiaj jednocześnie dwóch profili Spring w jednym procesie.

## 6. Profil test


Profil test służy do automatyzacji. Nie używaj go do codziennej pracy na bazie dev. Używa portów losowych, testowych baz, wyłączonego lub podniesionego rate limitingu i Testcontainers.

Testy jednostkowe:

    .\mvnw.cmd -Punit-tests test
    .\mvnw.cmd -f shop-app/pom.xml -Punit-tests test

Testy integracyjne:

    .\mvnw.cmd -Pintegration-tests verify
    .\mvnw.cmd -f shop-app/pom.xml -Pintegration-tests verify

Docker Engine w WSL musi działać. Testcontainers pobiera obrazy PostgreSQL i Redis oraz tworzy izolowane środowisko. Nie kieruj testów na bankdb ani shopdb używane przez dev.

Skrypt E2E znajduje się w scripts/e2e-smoke.sh. Czeka na healthchecki, rejestruje konto sklepu, dodaje produkt, tworzy zamówienie i sprawdza HTTP. Nie używa produkcyjnych sekretów i nie wykonuje prawdziwego przelewu.

Profil Maven prod służy do budowania artefaktu. Nie uruchamia sam profilu Spring prod.

## 7. Monitoring dev

Automatyczna ścieżka konfiguracji jest opisana w rozdziale `7` tej instrukcji. Najkrótsza kolejność dla profilu dev:

    bash scripts/monitoring/monitoring.sh setup
    # uruchom ponownie bank i shop-app w IntelliJ z EnvFile .novabank/env/dev.env
    bash scripts/monitoring/monitoring.sh start
    bash scripts/monitoring/monitoring.sh status

Skrypt generuje brakujace lokalne sekrety, synchronizuje konfiguracje aplikacji i Prometheusa oraz sprawdza, czy oba targety maja stan UP. Polecenie `setup --force` wykonuje rotacje hasel dev; po nim trzeba ponownie uruchomic obie aplikacje. Profil prod jest celowo pozostawiony dla pozniejszej integracji Vault Agent i nie zapisuje sekretow produkcyjnych lokalnie.

Automatyzacja dotyczy obecnie wyłącznie profilu `dev`. W IntelliJ bank i sklep muszą korzystać z tego samego lokalnego EnvFile `.novabank/env/dev.env`; skrypt sam umieszcza w nim `MANAGEMENT_SERVER_ADDRESS`, `METRICS_USERNAME` i `METRICS_PASSWORD`.

Obsługa z PowerShell jest równoważna poleceniom WSL:

    .\scripts\monitoring\monitoring.ps1 setup
    .\scripts\monitoring\monitoring.ps1 start
    .\scripts\monitoring\monitoring.ps1 status
    .\scripts\monitoring\monitoring.ps1 logs -Service prometheus
    .\scripts\monitoring\monitoring.ps1 stop

Panele:

    Prometheus: http://localhost:9090
    Grafana: http://localhost:3000
    Alertmanager: http://localhost:9093

W Grafanie loguj się jako admin hasłem GRAFANA_ADMIN_PASSWORD. W Prometheus otwórz Status | Target health. Bank i sklep powinny mieć UP.

Jeśli cel jest DOWN, sprawdź: działanie aplikacji, porty 8082/8083, MANAGEMENT_SERVER_ADDRESS, dostęp WSL do Windows oraz zgodność hasła metrics.

Aktualne alerty obejmują BankOrShopDown, HighHttp5xxRate, HighJvmMemoryUsage, PrometheusTargetErrors i RetentionJobFailed.

Zatrzymanie monitoringu:

    bash scripts/monitoring/monitoring.sh stop

Monitoring nie uruchamia się magicznie po restarcie komputera. Kontenery muszą zostać uruchomione ponownie albo mieć skonfigurowany automatyczny start.

## 8. Płatności: bank-demo i Stripe

SHOP_PAYMENT_PROVIDER=bank-demo oznacza wewnętrzny demonstracyjny operator. Bank sprawdza status karty, limit, subkonto walutowe i saldo, a potem aktualizuje saldo kupującego i rachunek sklepu. Ten wariant pokazuje komunikację sklep-bank.

SHOP_PAYMENT_PROVIDER=stripe oznacza zewnętrznego testowego operatora. Stripe obsługuje hostowaną sesję i webhook. Nie korzysta z sald ani kart NovaBank, więc nie przenosi środków między rachunkami banku. Aktualizuje status zamówienia w shopdb po zweryfikowanym webhooku.

Stripe CLI jest potrzebny tylko przy providerze stripe. Nie jest potrzebny przy bank-demo.

### 8.1. Instalacja Stripe CLI w WSL

    sudo apt update
    sudo apt install -y nodejs npm
    sudo npm install -g @stripe/cli
    stripe --version

Logowanie do testowego konta, zwykle jednorazowe:

    stripe login

Zaakceptuj dostęp tylko wtedy, gdy kod w przeglądarce zgadza się z kodem terminala.

### 8.2. Listener webhooków

Uruchom shop-app i pozostaw terminal Stripe:

    stripe listen --forward-to http://localhost:8081/api/payments/stripe/webhook

Wariant z filtrem zdarzeń:

    stripe listen --events checkout.session.completed,checkout.session.async_payment_succeeded --forward-to http://localhost:8081/api/payments/stripe/webhook

CLI pokaże nowy whsec_.... Wpisz go do .novabank/env/dev.env:

    SHOP_PAYMENT_PROVIDER=stripe
    SHOP_PAYMENT_API_KEY=sk_test_<PRAWIDLOWY_KLUCZ>
    SHOP_PAYMENT_WEBHOOK_SECRET=whsec_<SEKRET_LISTENERA>

sk_test_... i whsec_... są różnymi sekretami. Po zmianie zrestartuj shop-app. Terminal listenera musi działać przez cały test. Ctrl+C kończy nasłuchiwanie.

### 8.3. WSL i aplikacja Windows

Najpierw spróbuj:

    curl -sS -o /dev/null -w "%{http_code}\n" http://localhost:8081/novatech_shop.html

Jeśli localhost nie działa:

    WIN_HOST=$(ip route show default | awk '{print $3}')
    echo "$WIN_HOST"
    curl "http://$WIN_HOST:8081/novatech_shop.html"
    stripe listen --forward-to "http://$WIN_HOST:8081/api/payments/stripe/webhook"

PowerShell:

    Get-NetTCPConnection -LocalPort 8081 -State Listen | Select-Object LocalAddress,LocalPort

Jeśli .wslconfig zawiera nieobsługiwaną opcję wsl2.hostAddressLoopback, usuń ją i wykonaj:

    wsl --shutdown

### 8.4. Test Stripe

W formularzu Stripe użyj testowej karty 4242 4242 4242 4242, przyszłej daty i testowego CVC.

Udany log powinien zawierać:

    --> checkout.session.completed [evt_...]
    <-- [200] POST http://localhost:8081/api/payments/stripe/webhook [evt_...]

Jeśli jest 400, sprawdź log shop-app, whsec, identyfikator zamówienia i aktualność listenera. Diagnostyka:

    stripe events list --limit 10
    stripe events retrieve evt_<ID>
    stripe events resend evt_<ID>

stripe trigger payment_intent.succeeded testuje transport, ale nie tworzy poprawnego zamówienia sklepu.


## 9. Profil prod i HashiCorp Vault

Profil prod wymaga działającego, odpieczętowanego Vault. Sama zmiana profilu w IntelliJ nie wystarczy: trzeba przygotować Vault, AppRole, truststore, certyfikaty TLS i sekrety.

### 9.1. Instalacja i start Vault w WSL

Jeśli Vault nie jest zainstalowany:

    wget -O - https://apt.releases.hashicorp.com/gpg | sudo gpg --dearmor -o /usr/share/keyrings/hashicorp-archive-keyring.gpg
    echo "deb [signed-by=/usr/share/keyrings/hashicorp-archive-keyring.gpg] https://apt.releases.hashicorp.com $(lsb_release -cs) main" | sudo tee /etc/apt/sources.list.d/hashicorp.list
    sudo apt update
    sudo apt install -y vault
    vault version

Po konfiguracji /etc/vault.d/vault.hcl:

    sudo chown root:vault /etc/vault.d/vault.hcl
    sudo chmod 0640 /etc/vault.d/vault.hcl
    sudo systemctl enable vault
    sudo systemctl restart vault
    sudo systemctl status vault
    sudo journalctl -u vault -n 100 --no-pager

Ustaw adres i certyfikat:

    export VAULT_ADDR="https://127.0.0.1:8200"
    export VAULT_CACERT="/opt/vault/tls/tls.crt"

Jeśli usługa Vault ma status active (running), ale polecenie vault status zwraca błąd:

    failed to read environment: Error loading CA File: open /opt/vault/tls/tls.crt: permission denied

oznacza to problem z uprawnieniami użytkownika WSL do certyfikatu, a nie awarię Vault. Nie zmieniaj szeroko uprawnień prywatnego klucza TLS. Użyj kopii publicznego certyfikatu w projekcie:

    unset VAULT_CACERT
    export VAULT_ADDR="https://127.0.0.1:8200"
    export VAULT_CACERT="/mnt/c/Dysk D/stuff/Bank-demo/.novabank/tls/vault/vault.crt"
    vault status

Jeśli kopia nie istnieje, skopiuj aktualny certyfikat z uprawnieniami administratora:

    sudo cp /opt/vault/tls/tls.crt "/mnt/c/Dysk D/stuff/Bank-demo/.novabank/tls/vault/vault.crt"
    sudo chmod 644 "/mnt/c/Dysk D/stuff/Bank-demo/.novabank/tls/vault/vault.crt"

Po tej zmianie ponownie wykonaj vault status. Jeśli wynik pokaże Initialized true i Sealed true, Vault działa, ale trzeba go odpieczętować trzema różnymi kluczami. Komunikaty TLS handshake error w logu zwykle oznaczają, że klient nie ufał certyfikatowi; nie muszą oznaczać awarii usługi.

### 9.2. Inicjalizacja i pięć kluczy

Inicjalizację wykonuje się tylko raz dla danego magazynu. Przygotuj zaszyfrowany katalog poza repozytorium:

    export RECOVERY_DIR="/mnt/e/Vault-Recovery"
    mkdir -p "$RECOVERY_DIR"
    chmod 700 "$RECOVERY_DIR"
    umask 077

Utwórz plik tymczasowy:

    vault operator init -key-shares=5 -key-threshold=3 -format=json > /tmp/vault-init.json

Vault zwróci 5 wartości unseal_keys_b64 oraz root_token. Zaszyfruj plik:

    gpg --pinentry-mode loopback --symmetric --cipher-algo AES256 --output "$RECOVERY_DIR/vault-init.json.gpg" /tmp/vault-init.json

GPG poprosi o hasło. Ustal silne hasło odzyskiwania i zapisz je poza komputerem projektowym. Sprawdź plik:

    gpg --quiet --pinentry-mode loopback --decrypt "$RECOVERY_DIR/vault-init.json.gpg" | jq -e '(.unseal_keys_b64 | length) == 5 and (.root_token | length) > 0'

### Dostęp do pliku odzyskiwania

Plik `vault-init.json.gpg` jest zaszyfrowany i nie należy otwierać go przez `cat`. Najpierw ustaw ścieżkę i sprawdź, czy plik istnieje:

    export RECOVERY_DIR="/mnt/e/Vault-Recovery"
    ls -lh "$RECOVERY_DIR/vault-init.json.gpg"

Następnie wykonaj kontrolę zawartości:

    gpg --quiet \
      --pinentry-mode loopback \
      --decrypt "$RECOVERY_DIR/vault-init.json.gpg" |
      jq -e '(.unseal_keys_b64 | length) == 5 and (.root_token | length) > 0'

GPG poprosi o hasło. Należy użyć dokładnie tego samego hasła, które zostało utworzone podczas wykonywania polecenia `gpg --symmetric` i szyfrowania pliku. To hasło można potocznie nazywać hasłem odzyskiwania Vault, ale nie jest ono hasłem usługi Vault.

Nie należy w tym miejscu podawać:

- root tokena Vault;
- klucza unseal;
- hasła użytkownika `sudo`;
- hasła truststore Vault.

Po poprawnym odszyfrowaniu można odczytać klucze unseal:

    gpg --quiet \
      --pinentry-mode loopback \
      --decrypt "$RECOVERY_DIR/vault-init.json.gpg" |
      jq -r '.unseal_keys_b64[]'

Albo odczytać root token:

    gpg --quiet \
      --pinentry-mode loopback \
      --decrypt "$RECOVERY_DIR/vault-init.json.gpg" |
      jq -r '.root_token'

Klucze i token należy wyświetlać tylko lokalnie, nie zapisywać do zwykłego pliku i nie umieszczać na zrzutach ekranu.

Po weryfikacji usuń niezaszyfrowany plik:

    rm -f /tmp/vault-init.json

Do odpieczętowania użyj trzech różnych kluczy:

    vault operator unseal
    vault operator unseal
    vault operator unseal

Po każdym poleceniu wklej inny klucz. Nie wpisuj tego samego klucza trzy razy. Sprawdź:

    vault status

Oczekiwane wartości to Initialized true i Sealed false.

### 9.3. Root token i zwykły start

Root token nie jest generowany przy każdym starcie komputera. Jest wydany podczas jednorazowej inicjalizacji. Służy do administracji Vault, nie do uruchamiania aplikacji.

Odczyt tokena z zaszyfrowanego pliku:

    gpg --quiet --pinentry-mode loopback --decrypt "$RECOVERY_DIR/vault-init.json.gpg" | jq -r '.root_token'

Logowanie administracyjne:

    vault login

Wklej token dopiero po uruchomieniu vault login. Do zwykłego uruchomienia banku i sklepu nie trzeba wykonywać vault login. Aplikacje używają AppRole.

Kontrole:

    vault operator raft list-peers
    vault audit list
    vault secrets list
    vault auth list

### 9.4. AppRole banku i sklepu

Skrypt znajduje się w scripts/vault/setup-prod-approles.sh, a polityki w:

    infra/vault/policies/bank-prod.hcl
    infra/vault/policies/shop-prod.hcl

Po skonfigurowaniu AppRole pobierz osobno:

    vault read -field=role_id auth/approle/role/bank-prod/role-id
    vault write -f -field=secret_id auth/approle/role/bank-prod/secret-id
    vault read -field=role_id auth/approle/role/shop-prod/role-id
    vault write -f -field=secret_id auth/approle/role/shop-prod/secret-id

Zapisz cztery wartości: BANK_ROLE_ID, BANK_SECRET_ID, SHOP_ROLE_ID i SHOP_SECRET_ID. Bank i sklep mają osobne role. Nie zamieniaj wartości między aplikacjami.

### 9.5. Dane w Vault

Ścieżki:

    secret/Bank-demo/prod
    secret/shop-app/prod

Przykład banku z placeholderami:

    vault kv put secret/Bank-demo/prod POSTGRES_HOST=localhost POSTGRES_PORT=5300 POSTGRES_DB=bankdb POSTGRES_USER=bankuser POSTGRES_PASSWORD='<HASLO_BAZY_BANKU>' REDIS_HOST=localhost REDIS_PORT=6379 BANK_API_URL=https://localhost:8080 SHOP_PAYMENT_CALLBACK_URL=https://localhost:8081/payment/callback BANK_API_KEYS='<ID>|<TAJNY_KLUCZ>|payments:write,payments:authorize|<DATA_WYGASNIECIA>' TOTP_ENCRYPTION_KEY='<KLUCZ_BASE64>' TLS_TERMINATION=app SERVER_SSL_ENABLED=true SERVER_SSL_KEY_STORE='file:C:/Dysk D/stuff/Bank-demo/.novabank/tls/prod/bank-server.p12' SERVER_SSL_KEY_STORE_PASSWORD='<HASLO_KEYSTORE_BANKU>' SERVER_SSL_KEY_STORE_TYPE=PKCS12 SERVER_SSL_KEY_ALIAS=bank-local ADMIN_EMAIL='admin@example.test'

Przykład sklepu:

    vault kv put secret/shop-app/prod SHOP_POSTGRES_HOST=localhost SHOP_POSTGRES_PORT=5400 SHOP_POSTGRES_DB=shopdb SHOP_POSTGRES_USER=shopuser SHOP_POSTGRES_PASSWORD='<HASLO_BAZY_SKLEPU>' BANK_API_URL=https://localhost:8080 BANK_API_KEY='<TAJNY_KLUCZ_UZYWANY_PRZEZ_SKLEP>' BANK_API_KEY_ID='<ID_KLUCZA>' SHOP_MERCHANT_ACCOUNT_NUMBER='<26_CYFR_RACHUNKU_SKLEPU>' SHOP_PAYMENT_PROVIDER=bank-demo SERVER_SSL_ENABLED=true SERVER_SSL_KEY_STORE='file:C:/Dysk D/stuff/Bank-demo/.novabank/tls/prod/shop-server.p12' SERVER_SSL_KEY_STORE_PASSWORD='<HASLO_KEYSTORE_SKLEPU>' SERVER_SSL_KEY_STORE_TYPE=PKCS12 SERVER_SSL_KEY_ALIAS=shop-local TLS_TERMINATION=app

Jeśli prod ma używać Stripe, w ścieżce sklepu zapisz także SHOP_PAYMENT_PROVIDER=stripe, SHOP_PAYMENT_API_KEY, SHOP_PAYMENT_WEBHOOK_SECRET, SHOP_PAYMENT_SUCCESS_URL i SHOP_PAYMENT_CANCEL_URL.

vault kv put zastępuje cały rekord. Do pojedynczej zmiany użyj vault kv patch. Nazwy pól bez wyświetlania sekretów:

    vault kv get -format=json secret/Bank-demo/prod | jq -r '.data.data | keys[]'
    vault kv get -format=json secret/shop-app/prod | jq -r '.data.data | keys[]'

### 9.6. VAULT_SSL_TRUST_STORE_PASSWORD

Aplikacja musi ufać certyfikatowi HTTPS Vault. Truststore Vault jest czymś innym niż keystore HTTPS banku i sklepu.

Jeśli keytool nie jest w PATH:

    $keytool = "C:\Program Files\Java\jdk-25.0.2\bin\keytool.exe"

Utworzenie truststore:

    & $keytool -importcert -noprompt -alias local-vault -file "C:\Dysk D\stuff\Bank-demo\infra\vault\tls\vault.crt" -keystore "C:\Dysk D\stuff\Bank-demo\infra\vault\tls\vault-truststore.p12" -storetype PKCS12

Uruchomienie powyższej komendy tworzy plik truststore `vault-truststore.p12`. Podczas pierwszego tworzenia tego pliku `keytool` poprosi o podanie hasła do nowego truststore, a następnie o jego powtórzenie. W tym momencie samodzielnie tworzysz hasło chroniące truststore. Nie jest ono generowane automatycznie przez Vault ani przez aplikację.

Hasło utworzone w tym kroku należy zapisać jako wartość zmiennej `VAULT_SSL_TRUST_STORE_PASSWORD`. To samo hasło musi być następnie wpisane w plikach `.novabank/env/prod.bank.env` i `.novabank/env/prod.shop.env`, ponieważ bank i sklep otwierają ten sam plik `vault-truststore.p12` podczas łączenia z Vault:

    VAULT_SSL_TRUST_STORE=file:C:/Dysk D/stuff/Bank-demo/.novabank/tls/vault/vault-truststore.p12
    VAULT_SSL_TRUST_STORE_PASSWORD=<TO_SAMO_HASLO_TRUSTSTORE>
    VAULT_SSL_TRUST_STORE_TYPE=PKCS12

Kontrola:

    & $keytool -list -v -keystore "C:\Dysk D\stuff\Bank-demo\infra\vault\tls\vault-truststore.p12" -storetype PKCS12

Certyfikat Vault służy do połączenia aplikacji z Vault. Nie jest certyfikatem strony banku.

### 9.7. Keystores HTTPS aplikacji

PowerShell:

    Set-Location "C:\Dysk D\stuff\Bank-demo"
    .\scripts\generate-prod-tls-keystores.ps1

Skrypt prosi o osobne hasło dla bank-server.p12 i shop-server.p12. Hasła wpisz do odpowiednich sekretów Vault jako SERVER_SSL_KEY_STORE_PASSWORD.

Świadoma rotacja:

    .\scripts\generate-prod-tls-keystores.ps1 -Force

## 10. IntelliJ i start prod

### 10.1. Pliki bootstrap

.novabank/env/prod.bank.env zawiera dane potrzebne do znalezienia Vault i AppRole banku. .novabank/env/prod.shop.env zawiera analogiczne dane dla sklepu.

Bank:

    VAULT_ADDR=https://127.0.0.1:8200
    VAULT_ROLE=bank-prod
    VAULT_ROLE_ID=<BANK_ROLE_ID>
    VAULT_SECRET_ID=<BANK_SECRET_ID>
    VAULT_SSL_TRUST_STORE=file:C:/Dysk D/stuff/Bank-demo/.novabank/tls/vault/vault-truststore.p12
    VAULT_SSL_TRUST_STORE_PASSWORD=<HASLO_TRUSTSTORE>
    VAULT_SSL_TRUST_STORE_TYPE=PKCS12
    VAULT_APPROLE_PATH=approle

Dla sklepu zmień VAULT_ROLE na shop-prod i użyj SHOP_ROLE_ID oraz SHOP_SECRET_ID.

### 10.2. IntelliJ Run/Debug

BankDemoApplication: profil Spring prod, Enable EnvFile, .novabank/env/prod.bank.env, katalog projektu i port 8080.

ShopAppApplication: profil Spring prod, Enable EnvFile, .novabank/env/prod.shop.env, katalog projektu i port 8081.

Nie włączaj jednocześnie `.novabank/env/dev.env` i plików `.novabank/env/prod.*.env`. Nie wpisuj sekretów prod do YAML.

### 10.3. Kolejność startu

1. Uruchom PostgreSQL i Redis.
2. Uruchom Vault.
3. Sprawdź vault status.
4. Jeśli Sealed true, wpisz trzy różne klucze unseal.
5. Sprawdź AppRole, truststore i keystory.
6. Uruchom bank z profilem prod.
7. Uruchom shop-app z profilem prod.
8. Sprawdź logi Vault, Flyway, TLS i bazy.
9. Wykonaj test logowania i kontrolny zakup.

## 11. HTTPS prod i certyfikat Windows 11

### 11.1. TLS aplikacji

Wariant lokalny:

    TLS_TERMINATION=app
    SERVER_SSL_ENABLED=true

Linki:

    https://localhost:8080/login?logout
    https://localhost:8081/novatech_shop.html

Wariant reverse proxy:

    TLS_TERMINATION=proxy
    SERVER_SSL_ENABLED=false

Proxy przyjmuje HTTPS, posiada certyfikat, przekazuje do aplikacji HTTP i ustawia X-Forwarded-Proto=https. Porty aplikacji należy ograniczyć do sieci wewnętrznej. W tym projekcie lokalnie najprościej używać TLS_TERMINATION=app.

### 11.2. Certyfikat w Windows 11

Przeglądarka nie ufa automatycznie self-signed certyfikatowi. Importuj certyfikat aplikacji, nie vault.crt.

Eksport banku:

    & $keytool -exportcert -rfc -alias bank-local -keystore "C:\Dysk D\stuff\Bank-demo\infra\prod\tls\bank-server.p12" -storetype PKCS12 -file "C:\Dysk D\stuff\Bank-demo\infra\prod\tls\bank-local.crt"

Eksport sklepu wykonaj analogicznie z aliasem shop-local i plikiem shop-server.p12.

Import:

1. Uruchom certmgr.msc.
2. Otwórz Zaufane główne urzędy certyfikacji.
3. Wejdź do Certyfikaty.
4. Wybierz Akcja | Wszystkie zadania | Importuj.
5. Wskaż bank-local.crt oraz shop-local.crt.
6. Umieść certyfikaty w magazynie bieżącego użytkownika.
7. Uruchom ponownie przeglądarkę.

Certyfikat powinien zawierać localhost w SAN. Jeśli HTTPS jest przekreślone, sprawdź alias, keystore, datę ważności i magazyn Windows.

Linki prod:

    https://localhost:8080/login?logout
    https://localhost:8081/novatech_shop.html
    https://localhost:8082/actuator/prometheus
    https://localhost:8083/actuator/prometheus

Jeśli Actuator pozostaje na HTTP na osobnym porcie, użyj http://localhost:8082 i http://localhost:8083. Nie wystawiaj tych portów publicznie.

## 12. Rotacja kluczy

### 12.1. BANK_API_KEYS

Format:

    id|secret|scope1,scope2|expiresAt;id-next|secret-next|scope1,scope2|expiresAt

Procedura:

1. Wygeneruj nowy sekret przez openssl rand -hex 32.
2. Dodaj nowy wpis po średniku obok starego.
3. Zaktualizuj BANK_API_KEY i BANK_API_KEY_ID sklepu.
4. Zapisz dane w Vault dla prod albo .novabank/env/dev.env dla dev.
5. Zrestartuj bank i sklep.
6. Wykonaj test płatności.
7. Po teście usuń stary wpis.

Dla prod można przyjąć rotację co 90 dni oraz krótki okres nakładania kluczy. Po podejrzeniu wycieku klucz unieważnij natychmiast.

### 12.2. AppRole i TLS

Rotację AppRole wykonuj dwuetapowo z WSL:

    bash scripts/vault/approle-secret-id.sh rotate all
    # zrestartuj bank i sklep w profilu prod oraz sprawdź ich start
    bash scripts/vault/approle-secret-id.sh finalize all

Pierwsze polecenie generuje i testuje nowe Secret ID oraz atomowo aktualizuje lokalne pliki bootstrap bez wyświetlania sekretów. Drugie unieważnia poprzednie identyfikatory przez zapisane accessors. Test logowania zużywa jedno z pięciu użyć nowego Secret ID.

#### 12.2.1. Jak skrypt współpracuje z Vault

Skrypt nie zastępuje Vault. Jest jego klientem administracyjnym i automatyzuje bezpieczną wymianę danych AppRole.

Podział odpowiedzialności:

- Vault generuje Secret ID, przechowuje sekrety aplikacji i wydaje krótkotrwałe tokeny.
- Skrypt prosi Vault o nowy Secret ID, testuje go i aktualizuje lokalny plik bootstrap.
- Bank i sklep używają Role ID oraz Secret ID, aby zalogować się przez AppRole i pobrać własne sekrety z Vault.
- Pliki `.novabank/env/prod.bank.env` i `.novabank/env/prod.shop.env` zawierają dane bootstrap potrzebne do połączenia z Vault. Nie powinny zawierać sekretów biznesowych przechowywanych w KV Vault i nie mogą trafić do Git.

Pełna procedura po uruchomieniu komputera:

1. Sprawdź usługę Vault w WSL:

       sudo systemctl status vault --no-pager

2. Ustaw adres i certyfikat CA:

       export VAULT_ADDR="https://127.0.0.1:8200"
       export VAULT_CACERT="/mnt/c/Dysk D/stuff/Bank-demo/.novabank/tls/vault/vault.crt"

3. Sprawdź stan magazynu:

       vault status

4. Jeżeli Vault zwraca `Sealed: true`, odpieczętuj go trzema różnymi udziałami unseal:

       vault operator unseal
       vault operator unseal
       vault operator unseal

5. Zaloguj operatora uprawnionego do zarządzania AppRole:

       vault login

   Do środowiska docelowego przygotuj ograniczoną politykę operatora rotacji. Root token traktuj wyłącznie jako poświadczenie awaryjne i bootstrapowe.

6. Przejdź do projektu i wykonaj pierwszy etap rotacji:

       cd "/mnt/c/Dysk D/stuff/Bank-demo"
       bash scripts/vault/approle-secret-id.sh rotate all

   Polecenie generuje nowe Secret ID w Vault, wykonuje próbne logowanie AppRole, sprawdza odczyt odpowiedniej ścieżki KV, unieważnia token testowy i aktualizuje pliki bootstrap. Stary Secret ID pozostaje chwilowo aktywny jako możliwość powrotu.

7. Zrestartuj obie aplikacje w IntelliJ z profilem `prod` i właściwymi plikami EnvFile:

       Bank:  .novabank/env/prod.bank.env
       Sklep: .novabank/env/prod.shop.env

8. Sprawdź logi startowe, healthchecki, dostęp do Vault i podstawową operację banku oraz sklepu.

9. Po poprawnym teście unieważnij poprzednie Secret ID:

       bash scripts/vault/approle-secret-id.sh finalize all

Role można obracać oddzielnie:

    bash scripts/vault/approle-secret-id.sh rotate bank
    bash scripts/vault/approle-secret-id.sh finalize bank

    bash scripts/vault/approle-secret-id.sh rotate shop
    bash scripts/vault/approle-secret-id.sh finalize shop

Skrypt odmówi rotacji, gdy Vault jest wyłączony, zapieczętowany, certyfikat `VAULT_CACERT` jest niedostępny, operator nie wykonał `vault login`, token nie ma odpowiednich uprawnień albo poprzednia rotacja tej roli nie została zakończona przez `finalize`.

Nie uruchamiaj tej rotacji bezpośrednio z harmonogramu wykorzystującego zapisany root token. Pełna automatyzacja produkcyjna powinna później korzystać z Vault Agent, response wrapping albo dedykowanej tożsamości rotacyjnej z minimalną polityką.

Przy rotacji keystore:

1. Uruchom generate-prod-tls-keystores.ps1 -Force.
2. Ustal nowe hasła bank-server.p12 i shop-server.p12.
3. Zaktualizuj Vault.
4. Wyeksportuj nowe CRT.
5. Ponownie zaimportuj je w Windows.
6. Zrestartuj aplikacje.

Przy rotacji certyfikatu Vault utwórz nowy truststore, ustal nowe VAULT_SSL_TRUST_STORE_PASSWORD, zaktualizuj oba pliki bootstrap i zrestartuj aplikacje.

TOTP_ENCRYPTION_KEY nie rotuj przy zwykłej rotacji. Zmiana może uniemożliwić odszyfrowanie istniejących sekretów 2FA. Root token nie jest kluczem runtime.

## 13. Retencja, audyt i sesje

Panel:

    http://localhost:8080/admin/data-retention

Retencja dotyczy danych technicznych i audytowych: historii logowań, powiadomień, audytów operacji i administracyjnych. Nie usuwa rachunków, sald, transakcji, zamówień, refundacji ani danych kartowych modelu.

Konfiguracja:

    RETENTION_ENABLED=false
    RETENTION_DRY_RUN=true
    RETENTION_SCHEDULE=0 0 3 * * *
    RETENTION_BATCH_SIZE=500
    RETENTION_GRACE_PERIOD_DAYS=7
    RETENTION_LOGIN_HISTORY_DAYS=30
    RETENTION_NOTIFICATION_DAYS=30
    RETENTION_OPERATION_AUDIT_DAYS=90
    RETENTION_ADMIN_AUDIT_DAYS=365
    RETENTION_RUN_DAYS=365

Najpierw użyj Uruchom podgląd. Podgląd nie kasuje danych. Dopiero po kontroli wyniku włącz retencję i uruchom czyszczenie. Wyniki są widoczne w panelu oraz metrykach bank_retention_deleted_total i bank_retention_errors_total.

Blokada, zamknięcie rachunku lub zmiana hasła unieważnia sesje. Użytkownik musi zalogować się ponownie.

Diagnostyka Redis dev:

    docker exec -it bank-redis redis-cli

W redis-cli:

    SCAN 0 MATCH spring:session:* COUNT 100

Nie używaj KEYS * na działającym środowisku. Po kontroli konkretnego klucza można wykonać DEL. FLUSHDB jest dopuszczalne tylko w dev, bo wyloguje wszystkich i usunie dane Redis. Nigdy nie używaj go w prod.

## 14. Backup PostgreSQL i restore

Backup obejmuje bankdb i shopdb. Redis nie jest zamiennikiem backupu PostgreSQL.

Mechanizm obsluguje Windows 11 z WSL 2, natywny Linux oraz macOS. Katalog projektu jest wykrywany automatycznie, a nazwy kontenerow mozna ustawic przez `BANK_POSTGRES_CONTAINER` i `SHOP_POSTGRES_CONTAINER`.

Zalecane uruchomienie z PowerShell otwiera osobne okno WSL:

    cd "C:\Dysk D\stuff\Bank-demo"
    .\scripts\backup\start.ps1

W nowym oknie wybierz katalog zapisu. Kazde uruchomienie tworzy osobny katalog z czasem UTC i plikiem `backup-report.log`. Po przeczytaniu podsumowania wpisz `exit`.

Na Linuksie lub macOS uruchom:

    bash ./scripts/backup/backup.sh

Launcher otworzy osobny terminal, jezeli srodowisko graficzne na to pozwala. W przeciwnym razie wykona procedure w aktualnym terminalu.

Ścieżkę podawaj w formacie WSL, np. `/mnt/e/Bank-demo-backups/postgres`, a nie `E:\Bank-demo-backups\postgres`. Wskazuj katalog bazowy bez nazwy pliku i bez daty. Przy wpisywaniu odpowiedzi w interaktywnym formularzu nie dodawaj cudzysłowów. Pełne zasady i przykłady znajdują się w tym rozdziale.

Na Linuksie przykladowa sciezka to `/srv/backups/Bank-demo/postgres`, a na macOS `/Volumes/Backup/Bank-demo/postgres`. Zawsze podawaj sciezke absolutna w formacie systemu, w ktorym dziala Bash.

Uruchomienie bezposrednie w WSL:

    cd "/mnt/c/Dysk D/stuff/Bank-demo"
    docker compose up -d postgres shop-postgres redis
    bash ./scripts/backup/backup.sh create

Skrypt tworzy dumpy custom, pliki SHA256, sprawdza pg_restore --list i wykonuje testowe odtworzenie obu baz.

Opcjonalne czyszczenie: (Starsze katalogi są usuwane wyłącznie po świadomym użyciu opcji --prune.)

    BACKUP_DIR=/mnt/e/Bank-demo-backups/postgres BACKUP_RETENTION_DAYS=30 bash ./scripts/backup/backup.sh prune

Test odtwarzania:

    BACKUP_DIR=/mnt/e/Bank-demo-backups/postgres bash ./scripts/backup/backup.sh restore-test

Restore test używa tymczasowych baz i po sprawdzeniu je usuwa. Kopie przechowuj poza repozytorium w zaszyfrowanym, odseparowanym miejscu. Regularnie testuj odtwarzanie.

### Automatyczna kopia cykliczna bez Harmonogramu zadan Windows

Skrypt potrafi sam przygotowac harmonogram systemowy: timer `systemd` w WSL/Linux albo agent `launchd` na macOS. Najpierw wykonaj podglad, ktory niczego nie instaluje:

    cd "/mnt/c/Dysk D/stuff/Bank-demo"
    bash ./scripts/backup/backup.sh schedule-install \
      --daily-at 02:00 \
      --backup-dir /mnt/e/Bank-demo-backups/postgres \
      --dry-run

Nastepnie zainstaluj harmonogram:

    bash ./scripts/backup/backup.sh schedule-install \
      --daily-at 02:00 \
      --backup-dir /mnt/e/Bank-demo-backups/postgres

W WSL wykonaj jednorazowo, aby timer uzytkownika dzialal po zamknieciu terminala:

    sudo loginctl enable-linger "$USER"
    loginctl show-user "$USER" -p Linger

Oczekiwany wynik to `Linger=yes`. Timer ma `Persistent=true`, wiec zadanie pominiete podczas wylaczenia calej dystrybucji WSL wykona sie po jej ponownym uruchomieniu. Docker i kontenery PostgreSQL musza byc wtedy dostepne.

Obsluga harmonogramu:

    bash ./scripts/backup/backup.sh schedule-status
    bash ./scripts/backup/backup.sh schedule-run-now
    bash ./scripts/backup/backup.sh schedule-logs
    bash ./scripts/backup/backup.sh schedule-remove

Zaplanowana procedura tworzy obie kopie, sprawdza SHA-256, testowo je odtwarza i dopiero po sukcesie usuwa kopie starsze niż `BACKUP_RETENTION_DAYS`. Każde wykonanie zapisuje `backup-report.log` w nowym katalogu czasu UTC. Ponowne `schedule-install` zmienia godzinę lub miejsce zapisu. Szczegółowy opis znajduje się w tym rozdziale.

### Cotygodniowy pelny test awaryjny

Codzienny test odtwarza dane do baz tymczasowych. Raz w tygodniu uruchamiaj mocniejszy `dr-test`, ktory tworzy calkowicie osobne PostgreSQL, Redis i siec Docker, buduje obie aplikacje, uruchamia migracje Flyway oraz testy HTTP.

Pierwsza proba reczna:

    bash ./scripts/backup/backup.sh dr-test \
      --backup-dir /mnt/e/Bank-demo-backups/postgres

Instalacje wykonuj dopiero po recznym wyniku `SUKCES`:

    bash ./scripts/backup/backup.sh dr-schedule-install \
      --weekday SUN \
      --at 04:00 \
      --backup-dir /mnt/e/Bank-demo-backups/postgres \
      --dry-run

    bash ./scripts/backup/backup.sh dr-schedule-install \
      --weekday SUN \
      --at 04:00 \
      --backup-dir /mnt/e/Bank-demo-backups/postgres

Obsluga:

    bash ./scripts/backup/backup.sh dr-schedule-status
    bash ./scripts/backup/backup.sh dr-schedule-run-now
    bash ./scripts/backup/backup.sh dr-schedule-logs
    bash ./scripts/backup/backup.sh dr-schedule-remove

Raport oraz logi są zapisywane w `BACKUP_DIR/disaster-recovery/<czas-UTC>/`. Test nie modyfikuje baz dev/prod i po zakończeniu usuwa izolowane kontenery. Pierwsze wykonanie może trwać kilka minut, kolejne korzystają z cache obrazów. Pełny opis, wymagania i interpretacja raportu znajdują się w tym rozdziale.

## 15. CI/CD, OpenAPI, Swagger i Postman

CI/CD po pushu lub pull requeście:

1. pobiera kod;
2. buduje bank i sklep;
3. uruchamia testy jednostkowe;
4. uruchamia Testcontainers PostgreSQL i Redis;
5. sprawdza komunikację HTTP i smoke E2E;
6. raportuje wynik;
7. nie powinien otrzymywać lokalnych sekretów prod.

Pipeline nie zastępuje ręcznego testu płatności, Vault, certyfikatów i restore.

Swagger:

    http://localhost:8080/swagger-ui/index.html
    http://localhost:8081/swagger-ui/index.html

OpenAPI JSON:

    http://localhost:8080/v3/api-docs
    http://localhost:8081/v3/api-docs

Swagger pokazuje kontrakt i pozwala wywołać endpoint. Postman służy do kolekcji, zmiennych, CSRF, cookie i regresji. Swagger sam nie wersjonuje aplikacji; wersjonowanie kontraktu i kodu odbywa się przez OpenAPI, Git i ustaloną ścieżkę API.

W prod Swagger i OpenAPI wymagają roli administratora.

## 16. Checklista po przerwie

### Dev

1. Otwórz WSL i przejdź do projektu.
2. Uruchom PostgreSQL i Redis.
3. Sprawdź docker compose ps, pg_isready i redis-cli ping.
4. Sprawdź .novabank/env/dev.env.
5. Jeśli trzeba, wykonaj generate-dev-env.ps1 -Force.
6. W IntelliJ ustaw profil dev i EnvFile.
7. Uruchom bank, następnie sklep.
8. Sprawdź health, bank i sklep.
9. Opcjonalnie uruchom monitoring.

### Test

1. Uruchom Docker Engine w WSL.
2. Nie używaj danych dev.
3. Wykonaj .\mvnw.cmd -Punit-tests test.
4. Wykonaj .\mvnw.cmd -Pintegration-tests verify.
5. Sprawdź Testcontainers i Flyway.

### Prod

1. Uruchom PostgreSQL i Redis.
2. Uruchom Vault.
3. Sprawdź vault status.
4. Odpieczętuj trzema różnymi kluczami.
5. Nie generuj nowego root tokena przy każdym starcie.
6. Sprawdź AppRole i truststore.
7. Sprawdź keystory i hasła w Vault.
8. W IntelliJ ustaw prod oraz `.novabank/env/prod.bank.env` lub `.novabank/env/prod.shop.env`.
9. Uruchom bank i sklep.
10. Sprawdź HTTPS, Flyway, Vault, health i komunikację bank-sklep.
11. Monitoring prod uruchom z ograniczonym dostępem do Actuatora.

## 17. Najczęstsze problemy

package org.springframework... does not exist: odśwież projekt Maven, sprawdź moduł i JDK. Uruchamiaj mvnw.cmd z katalogu właściwego pom.xml.

connection refused na 8080/8081: proces nie działa, port jest zajęty albo wybrano zły profil.

Prometheus DOWN: sprawdź aplikacje, porty 8082/8083, MANAGEMENT_SERVER_ADDRESS=0.0.0.0, hasło metryk i dostęp WSL do Windows.

Vault Sealed true: wpisz trzy różne klucze unseal. Jeśli brakuje kluczy, użyj zaszyfrowanego pliku GPG.

Prod nie startuje przez Vault: sprawdź VAULT_ADDR, VAULT_ROLE, VAULT_ROLE_ID, VAULT_SECRET_ID, truststore, typ PKCS12, hasło truststore, status Vault i uprawnienia AppRole.

HTTPS przekreślone: importuj bank-local.crt i shop-local.crt do Zaufanych głównych urzędów certyfikacji Windows. Import vault.crt służy tylko do zaufania aplikacji do Vault.

Stripe 400 albo PENDING: sprawdź stripe listen, port 8081, aktualny whsec i log sklepu. checkout.session.completed musi dostać 200.

FATAL role postgres does not exist: użyj:

    docker exec -it bank-postgres psql -U bankuser -d bankdb
    docker exec -it shop-postgres psql -U shopuser -d shopdb

Koszyk pusty po powrocie z płatności: sprawdź status zamówienia w shopdb, log płatności i idempotency key. Nie ponawiaj płatności bez kontroli poprzedniej próby.

## 18. Minimalna kolejność całego startu

    0. Interaktywny przewodnik: bash scripts/control-center/interactive-guide.sh
    1. NovaBank Control Center i pozycja Pierwsze uruchomienie
    2. WSL 2
    3. Docker Engine
    4. PostgreSQL i Redis z docker-compose.yaml
    5. Vault, tylko dla prod
    6. Profil i EnvFile w IntelliJ
    7. Bank
    8. Shop-app
    9. Stripe CLI, tylko dla provider=stripe
    10. Monitoring, jeśli potrzebny
    11. Test health, stron, płatności, Swaggera i logów

## 19. NovaBank Control Center

Wspólnym punktem uruchamiania skryptów administracyjnych jest:

```powershell
.\scripts\control-center\start.ps1
```

albo w WSL, Linuxie i macOS:

```bash
bash scripts/control-center/control-center.sh
```

Control Center przygotowuje pliki lokalne, kontenery i narzędzia pomocnicze. Aplikacje uruchamia się następnie w IntelliJ gotową konfiguracją `NovaBank DEV` albo `NovaBank PROD`. Pełna instrukcja menu, konfiguracji `.run`, potwierdzeń i zasad bezpieczeństwa znajduje się w sekcjach `19. NovaBank Control Center` i `20. Instalacja i wymagania przez NovaBank Control Center`.

Centrala nie wymaga instalacji projektu w konkretnej lokalizacji. Uruchamiaj ją z repozytorium, a ścieżkę projektu wyznaczy względem skryptu. Przy zmianie komputera sprawdź jednak ścieżki zapisane ręcznie w IntelliJ, `prometheus.yml`, plikach EnvFile oraz konfiguracji Vault.

Najważniejsza zasada: najpierw zależności, potem profil, następnie bank i sklep, a na końcu narzędzia pomocnicze. W prod Vault musi być dostępny i odpieczętowany przed uruchomieniem obu aplikacji.
### 19.1. Pierwsze uruchomienie

Po pobraniu projektu uruchom `NovaBank Control Center` i wybierz pozycje `Pierwsze uruchomienie`. Jest to glowna sciezka przygotowania nowej instalacji.

Kolejno:

1. Sprawdz wymagane komponenty albo zainstaluj brakujace elementy w sekcji `Instalacja i wymagania`.
2. Utworz lub uzupelnij `.novabank/env/project.env` na podstawie `.env.example`. Control Center automatycznie wygeneruje lokalne hasla baz, jesli nie ma poprawnych wartosci.
3. Uruchom PostgreSQL `postgres:18` i Redis `redis:7.4-alpine` przez Compose.
4. Dla DEV utworz `.novabank/env/dev.env`, a dla PROD utworz `.novabank/env/prod.bank.env` i `.novabank/env/prod.shop.env`.
5. Dla PROD wczesniej skonfiguruj Vault, certyfikat CA, truststore i keystory TLS. Pliki bootstrap PROD wymagaja Role ID, Secret ID oraz hasla truststore.
6. Przygotuj monitoring DEV tylko wtedy, gdy chcesz uruchomic Prometheus, Grafane i Alertmanager.
7. Uruchom odpowiednia konfiguracje IntelliJ: `NovaBank DEV` albo `NovaBank PROD`.

Pliki `.novabank/env/*.env` i `.novabank/monitoring/.env.monitoring` sa lokalne i nie powinny trafic do repozytorium. Control Center nie generuje root tokena Vault ani kluczy unseal; te dane pochodza z konkretnej instalacji Vault i nalezy je przechowywac poza repozytorium.

Szczegółowa procedura pierwszego uruchomienia znajduje się w tym rozdziale. Pełny opis menu, potwierdzeń i zasad bezpieczeństwa znajduje się w rozdziałach `19` i `20`.

## 20. Instalacja i wymagania przez NovaBank Control Center

Control Center zawiera sekcje `Instalacja i wymagania`. Można z niej sprawdzić środowisko oraz uruchomić wybrane instalacje krok po kroku. Każda operacja wymagająca pobierania lub `sudo` wymaga potwierdzenia `t`.

Menu zawiera:

1. Sprawdzenie kompletności środowiska.
2. Instalację Vault w WSL.
3. Instalację narzędzi WSL: `jq`, OpenSSL, GPG, `curl` i `wget`.
4. Sprawdzenie Docker Engine i Compose.
5. Sprawdzenie JDK oraz `keytool`.
6. Opcjonalną instalację Stripe CLI przez npm.
7. Uruchomienie baz przez Docker Compose.
8. Ponowną kontrolę zależności.

### PostgreSQL i Redis

Projekt korzysta z kontenerów:

```text
postgres:18       -> bank-postgres oraz shop-postgres
redis:7.4-alpine  -> bank-redis
```

Control Center nie instaluje PostgreSQL ani Redis bezpośrednio w WSL. Opcja uruchomienia baz wykonuje:

```bash
docker compose --project-directory "/mnt/c/Dysk D/stuff/Bank-demo" \
  -f "/mnt/c/Dysk D/stuff/Bank-demo/docker-compose.yaml" \
  up -d postgres shop-postgres redis
```

Przed uruchomieniem wymagany jest projektowy plik `.novabank/env/project.env` z hasłami baz.

### Utrzymanie aplikacji

W menu głównym `Utrzymanie aplikacji` dostępne są także:

1. status, start, restart, stop, logi i walidacja `docker-compose.yaml`;
2. diagnostyka tabeli `flyway_schema_history` dla banku i sklepu;
3. status, logowanie i listener webhooków Stripe CLI;
4. lokalny build banku, sklepu, obu aplikacji oraz artefaktów PROD;
5. bezpieczne sprawdzenie i unieważnienie sesji `BANK_SESSION` w Redis.

Unieważnienie sesji dotyczy tylko kluczy `spring:session:*` i wymaga potwierdzenia. Nie wykonuje `FLUSHALL`. Sklep korzysta z sesji JDBC `SHOP_SESSION`, więc jego sesje nie są usuwane przez operację Redis.

### Po zakończeniu operacji

Po każdej pozycji Control Center pokazuje raport. Enter wraca do menu, a wpisanie `exit` zamyka Centralę. Anulowanie przez Enter przy pytaniu `Pobierz i zainstaluj? [t/N]` nie wykonuje żadnej zmiany.

Instalacje można również wykonać ręcznie zgodnie z odpowiednimi rozdziałami tej instrukcji. Control Center jest skrótem operacyjnym, nie zastępuje konfiguracji Vault, utworzenia certyfikatów, plików EnvFile ani konfiguracji IntelliJ.

Nie instaluj Docker Desktop jako warunku projektu. W tej konfiguracji używany jest Docker Engine z WSL 2.

## Procedury rozszerzone i własna ścieżka projektu

Ten rozdział uzupełnia instrukcję główną o komendy operacyjne z dokumentów
production-security.md, postgres-backup-restore.md, monitoring-preparation.md
oraz ci-cd.md. Pozostałe pliki .md pozostają w repozytorium jako dokumentacja
szczegółowa.

### Zastąp przykładową ścieżkę własną

W całej dokumentacji używana jest przykładowa ścieżka:

    C:\Dysk D\stuff\Bank-demo
    /mnt/c/Dysk D/stuff/Bank-demo

To jest ścieżka środowiska autora, a nie stały wymóg aplikacji. Przed
wykonaniem każdej komendy zastąp ją lokalizacją własnego projektu.

Przykład dla projektu w C:\Projekty\Bank-demo:

    PowerShell:
    $ProjectWin = 'C:\Projekty\Bank-demo'
    $ProjectWsl = '/mnt/c/Projekty/Bank-demo'
    Set-Location $ProjectWin

    WSL:
    PROJECT_WSL='/mnt/c/Projekty/Bank-demo'
    cd "$PROJECT_WSL"

Jeżeli projekt znajduje się na dysku D:, użyj /mnt/d/..., a dla dysku E:
/mnt/e/.... Spacje zawsze obejmuj cudzysłowem:

    cd "/mnt/d/Moje projekty/Bank-demo"

Do konwersji ścieżek użyj:

    wslpath -u 'C:\Projekty\Bank-demo'
    wslpath -w '/mnt/c/Projekty/Bank-demo'

W IntelliJ ustaw ten sam katalog projektu jako Working directory i wskazuj
pliki EnvFile przez własną lokalizację. Nie kopiuj bez zmian ścieżek autora.
NovaBank Control Center wykrywa katalog projektu, ale ręczne wpisy w IntelliJ
oraz ręcznie wykonywane komendy nadal muszą wskazywać właściwy katalog.

### Bezpieczeństwo profilu PROD

Profil prod wymaga zewnętrznych sekretów i nie powinien uruchamiać się na
wartościach przykładowych. Wymagane są między innymi hasła PostgreSQL, dane
Redis, klucze API banku, dane AppRole Vault, klucz szyfrowania TOTP oraz
certyfikaty TLS.

#### TLS: aplikacja albo reverse proxy

Wybierz jeden wariant.

    TLS_TERMINATION=proxy
    SERVER_SSL_ENABLED=false

W wariancie proxy reverse proxy kończy połączenie HTTPS, a aplikacja odbiera
ruch wewnętrznym HTTP. Certyfikat, przekierowanie HTTP do HTTPS oraz nagłówki
TLS należą wtedy do reverse proxy.

Drugi wariant:

    TLS_TERMINATION=app
    SERVER_SSL_ENABLED=true
    SERVER_SSL_KEY_STORE=<ścieżka do bank-server.p12 lub shop-server.p12>
    SERVER_SSL_KEY_STORE_PASSWORD=<hasło keystore>
    SERVER_SSL_KEY_STORE_TYPE=PKCS12
    SERVER_SSL_KEY_ALIAS=bank-local

W wariancie app każda aplikacja sama obsługuje HTTPS. Dla sklepu użyj
keystore sklepu, właściwego aliasu i portu sklepu. Wartości prod trzymaj
w Vault, a lokalnie tylko w ignorowanych plikach bootstrap, jeżeli Vault nie
jest jeszcze podłączony.

#### Secure cookies i wymagane sekrety

W prod włącz Secure cookies. Sesja nie może być przesyłana przez HTTP.
Cookies pozostają HttpOnly i używają SameSite=Lax, o ile architektura proxy
nie wymaga innej wartości. W dev i test Secure=false jest dopuszczalne tylko
dla lokalnego HTTP.

Brak wymaganych sekretów powinien zatrzymać start aplikacji. Nie wpisuj ich
do application-prod.yaml, kodu, historii IntelliJ ani repozytorium. Wartości
prod umieść w:

    <PROJECT_WSL>/.novabank/env/prod.bank.env
    <PROJECT_WSL>/.novabank/env/prod.shop.env

albo w Vault, zgodnie z konfiguracją profilu.

#### Format i rotacja BANK_API_KEYS

W prod bank akceptuje klucze w formacie:

    id|secret|scope1,scope2|expiresAt;id-next|secret-next|scope1,scope2|expiresAt

Rotacja przebiega następująco:

1. Wygeneruj nowy długi sekret i dodaj nowy wpis obok starego.
2. Przełącz shop-app na nowy BANK_API_KEY oraz BANK_API_KEY_ID.
3. Wykonaj test komunikacji i test płatności.
4. Dopiero po potwierdzeniu usuń wygasły wpis.

Okres 90 dni jest praktyką szkoleniową, ale termin może wynikać z polityki
zespołu. Przy wycieku wykonaj natychmiastową rotację i unieważnij stary klucz.

### Vault, AppRole i certyfikaty

Vault musi działać przed uruchomieniem aplikacji w prod. W WSL sprawdź usługę:

    sudo systemctl status vault --no-pager
    export VAULT_ADDR='https://127.0.0.1:8200'
    export VAULT_CACERT='<PROJECT_WSL>/infra/vault/tls/vault.crt'
    vault status

Jeżeli aplikacja zgłasza błąd certyfikatu, nie używaj VAULT_SKIP_VERIFY=true.
Porównaj odciski:

    echo | openssl s_client -connect 127.0.0.1:8200 -servername localhost 2>/dev/null | openssl x509 -noout -fingerprint -sha256 -subject
    sudo openssl x509 -in /opt/vault/tls/tls.crt -noout -fingerprint -sha256 -subject
    sudo openssl x509 -in /opt/vault/tls/vault.crt -noout -fingerprint -sha256 -subject

VAULT_CACERT musi wskazywać certyfikat o takim samym odcisku jak aktywny
listener Vault. Jeżeli użytkownik WSL nie może odczytać certyfikatu, napraw
uprawnienia i skopiuj właściwy certyfikat do ignorowanego katalogu projektu:

    sudo chmod 0755 /opt/vault/tls
    sudo chmod 0644 /opt/vault/tls/tls.crt
    sudo cp /opt/vault/tls/tls.crt "<PROJECT_WSL>/infra/vault/tls/vault.crt"
    sudo chown "$USER:$USER" "<PROJECT_WSL>/infra/vault/tls/vault.crt"
    export VAULT_CACERT="<PROJECT_WSL>/infra/vault/tls/vault.crt"

Po restarcie komputera Vault może działać, ale nadal być zapieczętowany.
Sealed=true oznacza, że aplikacja nie może pobierać sekretów. Do
odpieczętowania potrzebne są trzy z pięciu kluczy unseal:

    vault operator unseal
    vault operator unseal
    vault operator unseal
    vault status

Nie podawaj root tokena jako klucza unseal. Root token służy do administracji,
polityk i AppRole, nie do codziennego odpieczętowania.

Jeżeli recovery zapisano jako /mnt/e/Vault-Recovery/vault-init.json.gpg:

    export RECOVERY_DIR='/mnt/e/Vault-Recovery'
    gpg --quiet --pinentry-mode loopback --decrypt "$RECOVERY_DIR/vault-init.json.gpg" | jq -e '(.unseal_keys_b64 | length) == 5 and (.root_token | length) > 0'

Hasło GPG nie musi być takie samo jak hasło Vault. Musi być tym samym hasłem,
które zostało użyte do zaszyfrowania vault-init.json.gpg. Root token odczytaj
tylko przy administracji Vault:

    gpg --quiet --pinentry-mode loopback --decrypt "$RECOVERY_DIR/vault-init.json.gpg" | jq -r '.root_token'
    vault login

Role ID i Secret ID są osobnymi danymi AppRole. Secret ID może mieć
ograniczony czas życia i liczbę użyć, dlatego rotuj go bez generowania nowego
root tokena. Po rotacji wpisz nowe wartości do:

    <PROJECT_WSL>/.novabank/env/prod.bank.env
    <PROJECT_WSL>/.novabank/env/prod.shop.env

### Monitoring DEV: pełna konfiguracja

Monitoring działa w osobnym Compose. Bank i sklep uruchom z profilem dev
w IntelliJ, a Prometheus, Grafana i Alertmanager uruchom w WSL.

W EnvFile obu aplikacji ustaw wspólne konto techniczne:

    MANAGEMENT_SERVER_ADDRESS=0.0.0.0
    METRICS_USERNAME=metrics-reader
    METRICS_PASSWORD=<jedno długie hasło>
    BANK_MANAGEMENT_PORT=8082
    SHOP_MANAGEMENT_PORT=8083

Następnie w WSL, z katalogu własnego projektu:

    cd "<PROJECT_WSL>"
    bash scripts/monitoring/monitoring.sh setup
    bash scripts/monitoring/monitoring.sh start --profile dev
    bash scripts/monitoring/monitoring.sh status --profile dev

Skrypt tworzy lub aktualizuje lokalne pliki:

    <PROJECT_WSL>/.novabank/monitoring/.env.monitoring
    <PROJECT_WSL>/.novabank/monitoring/prometheus.yml

Nie commituj tych plików. Przy ręcznej konfiguracji Prometheus musi mieć
endpoint, Basic Auth i stałe etykiety:

    global:
      scrape_interval: 15s

    scrape_configs:
      - job_name: bank-demo
        metrics_path: /actuator/prometheus
        basic_auth:
          username: metrics-reader
          password: <METRICS_PASSWORD>
        static_configs:
          - targets: ['localhost:8082']
            labels:
              service: bank
              environment: dev
      - job_name: shop-app
        metrics_path: /actuator/prometheus
        basic_auth:
          username: metrics-reader
          password: <METRICS_PASSWORD>
        static_configs:
          - targets: ['localhost:8083']
            labels:
              service: shop
              environment: dev

Nie wpisuj dosłownie <METRICS_PASSWORD>; zastąp go tym samym hasłem co
w obu aplikacjach i .novabank/monitoring/.env.monitoring. W WSL 2 z
networkingMode=mirrored i network_mode: host adresy localhost:8082 i
localhost:8083 są prawidłowe. W wariancie Docker bridge użyj
host.docker.internal i nie mieszaj obu sposobów.

Sprawdź stack:

    Prometheus:  http://localhost:9090
    Grafana:     http://localhost:3000
    Alertmanager: http://localhost:9093
    Bank metrics: http://localhost:8082/actuator/prometheus
    Shop metrics: http://localhost:8083/actuator/prometheus

Targety Prometheusa muszą mieć stan UP. 401 oznacza brak lub błędne
basic_auth, connection refused oznacza zatrzymaną aplikację albo błędny
adres, a context deadline exceeded oznacza problem z dostępem WSL do
Windows. Ręczny test w PowerShell:

    $pair = "metrics-reader:<METRICS_PASSWORD>"
    $basic = [Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes($pair))
    Invoke-WebRequest -UseBasicParsing -Headers @{ Authorization = "Basic $basic" } http://localhost:8082/actuator/prometheus

Dla sklepu zmień port na 8083. Odpowiedź 200 i serie Prometheus oznaczają
poprawną konfigurację. W prod endpoint wymaga HTTPS, właściwego CA oraz
konta technicznego z sekretami pobranymi z Vault.

### Backup PostgreSQL: ręczny, cykliczny i awaryjny

Backup tworzy nowy katalog z datą UTC dla każdego uruchomienia. Nie nadpisuje
poprzednich kopii, a najnowszy katalog nie jest automatycznie używany do
odtworzenia bez wyraźnego uruchomienia procedury restore.

W WSL ustaw własny katalog poza repozytorium:

    export BACKUP_DIR=/mnt/e/Bank-demo-backups/postgres
    mkdir -p "$BACKUP_DIR"

To tylko przykład. Zamień /mnt/e/Bank-demo-backups/postgres na własny
absolutny katalog. Windows E:\Bank-demo-backups\postgres odpowiada zwykle
/mnt/e/Bank-demo-backups/postgres. Nie zapisuj backupu w repozytorium,
katalogu tymczasowym ani w katalogu kontenera.

Uruchom bazy i wykonaj kopię:

    PROJECT_WSL='/mnt/c/Projekty/Bank-demo'
    cd "$PROJECT_WSL"
    docker compose --env-file "$PROJECT_WSL/.novabank/env/project.env" up -d postgres shop-postgres redis
    bash "$PROJECT_WSL/scripts/backup/backup.sh" create --backup-dir "$BACKUP_DIR"

Można też użyć launchera Windows:

    Set-Location 'C:\Projekty\Bank-demo'
    .\scripts\backup\start.ps1

Każda kopia otrzymuje katalog na przykład:

    <BACKUP_DIR>/20260810T123750Z/
      bank_20260810T123750Z.dump
      bank_20260810T123750Z.dump.sha256
      shop_20260810T123750Z.dump
      shop_20260810T123750Z.dump.sha256
      backup-report.log

Raport powinien potwierdzać Backup PostgreSQL: OK, Sumy SHA-256: OK,
Test odtwarzania: OK i pokazywać pełną lokalizację obu plików.

Test integralności i retencję uruchom:

    BACKUP_DIR="$BACKUP_DIR" bash "$PROJECT_WSL/scripts/backup/backup.sh" restore-test
    BACKUP_DIR="$BACKUP_DIR" BACKUP_RETENTION_DAYS=30 bash "$PROJECT_WSL/scripts/backup/backup.sh" prune

Test odtwarzania używa tymczasowych baz bank_restore_* i shop_restore_*,
sprawdza migracje Flyway, a następnie usuwa bazy testowe. Nie modyfikuje
produkcyjnych baz.

Harmonogram WSL/Linux działa przez systemd user:

    bash "$PROJECT_WSL/scripts/backup/backup.sh" schedule-install --daily-at 02:00 --backup-dir "$BACKUP_DIR" --dry-run
    bash "$PROJECT_WSL/scripts/backup/backup.sh" schedule-install --daily-at 02:00 --backup-dir "$BACKUP_DIR"
    sudo loginctl enable-linger "$USER"
    loginctl show-user "$USER" -p Linger
    bash "$PROJECT_WSL/scripts/backup/backup.sh" schedule-status
    bash "$PROJECT_WSL/scripts/backup/backup.sh" schedule-run-now
    bash "$PROJECT_WSL/scripts/backup/backup.sh" schedule-logs
    bash "$PROJECT_WSL/scripts/backup/backup.sh" schedule-remove

Raz w tygodniu uruchom odseparowany test DR:

    bash "$PROJECT_WSL/scripts/backup/backup.sh" dr-test --backup-dir "$BACKUP_DIR"
    bash "$PROJECT_WSL/scripts/backup/backup.sh" dr-report --backup-dir "$BACKUP_DIR"

Test DR tworzy osobną sieć i tymczasowe kontenery, odtwarza najnowszą kopię,
uruchamia migracje oraz testy HTTP, po czym zapisuje raport. Harmonogram
tygodniowy używa dr-schedule-install --weekday SUN --at 04:00. Najpierw
wykonaj wariant --dry-run.

### CI/CD, Maven i pełny test E2E

Workflow .github/workflows/ci.yml buduje bank i shop-app, uruchamia testy
oraz kontroluje podstawową jakość zmian. Lokalnie:

    Set-Location 'C:\Projekty\Bank-demo'
    .\mvnw.cmd -Punit-tests test
    .\mvnw.cmd -Pintegration-tests verify
    .\mvnw.cmd -f shop-app\pom.xml -Punit-tests test
    .\mvnw.cmd -f shop-app\pom.xml -Pintegration-tests verify

Skrypt scripts/e2e-smoke.sh:

1. czeka na healthcheck banku i shop-app;
2. otwiera stronę logowania banku;
3. pobiera produkt z publicznego katalogu sklepu;
4. pobiera token CSRF i rejestruje testowego użytkownika sklepu;
5. loguje użytkownika przez HTTP;
6. dodaje produkt do koszyka i tworzy zamówienie;
7. wywołuje płatność sklepu;
8. ponawia tę samą płatność z identycznym Idempotency-Key i sprawdza,
   że autoryzacja bankowa pozostaje ta sama;
9. sprawdza, że sklep utworzył autoryzację w rzeczywiście uruchomionym banku;
10. odczytuje stan autoryzacji jako PENDING.

Każde uruchomienie generuje własny klucz idempotencji płatności. Dzięki temu
test można powtarzać na trwałej bazie DEV bez kolizji z poprzednim zamówieniem.
Klucz można nadpisać zmienną `E2E_PAYMENT_IDEMPOTENCY_KEY`, jeżeli potrzebny
jest kontrolowany scenariusz powtórzenia.

Uruchomienie:

    cd "<PROJECT_WSL>"
    bash scripts/e2e-smoke.sh

Jeżeli test zatrzymuje się na healthcheck, sprawdź porty, aktywny profil,
EnvFile oraz czy bank i sklep faktycznie działają. Jeżeli Maven zgłasza brak
JAVA_HOME, ustaw JDK w WSL albo uruchom test przez IntelliJ na Windows.

### Kolejność po przeniesieniu projektu

1. Ustaw ProjectWin i ProjectWsl zgodnie z własnym katalogiem.
2. Uruchom Control Center z katalogu projektu, nie z C:\Windows\System32.
3. Wybierz Pierwsze uruchomienie i utwórz lokalne pliki .novabank/env.
4. Sprawdź Docker Engine, PostgreSQL, Redis, JDK i narzędzia WSL.
5. W IntelliJ ustaw Working directory, profil i EnvFile.
6. Dla DEV uruchom bank i sklep, a następnie monitoring.
7. Dla TEST uruchom testy i Testcontainers.
8. Dla PROD uruchom Vault, odpieczętuj go trzema kluczami, ustaw CA i AppRole,
   a dopiero potem uruchom aplikacje.
9. Ścieżki backupu i raportów ustaw poza repozytorium.
10. Sprawdź, czy lokalne pliki sekretów są ignorowane przez Git.

Jeżeli ścieżka projektu zawiera spacje, używaj cudzysłowów. Jeżeli Control
Center pokazuje ścieżkę autora, nie kopiuj jej bez zmian: podmień ją na ścieżkę
wykrytą przez własne środowisko WSL lub IntelliJ.

## Interaktywne wprowadzenie do całego projektu

Po pobraniu repozytorium uruchom:

```bash
bash scripts/control-center/interactive-guide.sh
```

W WSL przewodnik otworzy się w przeglądarce Windows. Z poziomu NovaBank Control Center wybierz `Instrukcje -> Interaktywne wprowadzenie do projektu`. Przewodnik pokazuje kolejność startu, profile DEV/TEST/PROD, funkcje banku i sklepu, monitoring, Vault, backupy oraz najważniejsze adresy. Zrzut QR i sekret TOTP nie są do niego dołączone.

## Uzupełnienia końcowe

Ten rozdział scala najważniejsze informacje z `first-run.md`, `runtime-layout.md` oraz `data-retention.md`. Oryginalne pliki pozostają dokumentacją szczegółową. W razie rozbieżności najpierw sprawdź faktyczną konfigurację projektu, pliki `.novabank`, aktywny profil i komunikat NovaBank Control Center.

### Pierwsze uruchomienie od zera

Przed pierwszym uruchomieniem ustal ścieżkę projektu w obu środowiskach. Poniższe wartości są przykładem dla bieżącego komputera. Przy innej lokalizacji podmień je na własne ścieżki.

W PowerShell:

```powershell
$PROJECT_WIN = 'C:\Dysk D\stuff\Bank-demo'
Set-Location $PROJECT_WIN
```

W WSL:

```bash
export PROJECT_WSL='/mnt/c/Dysk D/stuff/Bank-demo'
cd "$PROJECT_WSL"
```

Projekt korzysta z Docker Engine i Docker Compose uruchomionych w WSL 2. Nie trzeba instalować Docker Desktop. Wymagane lub opcjonalne składniki to:

- Bash, `curl`, `jq`, OpenSSL i GPG;
- Docker Engine i Compose;
- PostgreSQL `postgres:18` oraz Redis `redis:7.4-alpine`, uruchamiane przez Compose;
- JDK 25, `keytool` i Maven Wrapper;
- Vault dla profilu `prod`;
- Prometheus, Grafana i Alertmanager dla monitoringu;
- Stripe CLI tylko przy `SHOP_PAYMENT_PROVIDER=stripe`.

Uruchom Centralę z katalogu projektu:

```bash
cd "$PROJECT_WSL"
bash scripts/control-center/control-center.sh
```

albo z PowerShell:

```powershell
Set-Location $PROJECT_WIN
.\scripts\control-center\start.ps1
```

Wybierz `Pierwsze uruchomienie -> Sprawdź kompletność pierwszego uruchomienia`. Centrala sprawdzi komponenty, wskaże braki i odeśle do właściwej opcji instalacji. Instalacje wymagające `sudo` zawsze wymagają potwierdzenia użytkownika. Nie instaluj automatycznie Docker Desktop.

#### Lokalny katalog `.novabank`

Utwórz jeden ignorowany przez Git katalog na pliki generowane i sekrety:

```bash
cd "$PROJECT_WSL"
mkdir -p .novabank/env .novabank/monitoring .novabank/tls/vault .novabank/tls/prod
cp -n .env.example .novabank/env/project.env
chmod 600 .novabank/env/project.env
```

Można zamiast tego wybrać odpowiednią pozycję w `Pierwsze uruchomienie`; Centrala utworzy katalog i pliki, których jeszcze nie ma. Uzupełnij `project.env` wartościami lokalnymi. Nie zastępuj haseł istniejących kontenerów PostgreSQL nowymi wartościami bez planu migracji danych.

Uruchom bazy i Redis:

```bash
docker compose --env-file "$PROJECT_WSL/.novabank/env/project.env" up -d postgres shop-postgres redis
docker ps
docker inspect --format '{{.Name}} {{.State.Status}} {{if .State.Health}}{{.State.Health.Status}}{{end}}' \
  bank-postgres shop-postgres bank-redis
```

Oczekiwany stan baz to `healthy`, a Redis powinien mieć status `running`.

#### Profil DEV

Wybierz `Pierwsze uruchomienie -> Utwórz .novabank/env/dev.env` albo `Środowiska i aplikacje -> Przygotuj DEV`. Centrala wygeneruje lokalny `BANK_API_KEY` i `TOTP_ENCRYPTION_KEY`, jeśli nie istnieją. Nie zapisuj ich do repozytorium.

W IntelliJ dla `Bank DEV` i `Shop DEV` ustaw:

1. aktywny profil `dev`;
2. `Enable EnvFile`;
3. plik `$PROJECT_WIN\.novabank\env\dev.env`;
4. katalog roboczy `$PROJECT_WIN`;
5. JDK 25.

Uruchom oba serwisy jednocześnie. Bank działa na `8080`, sklep na `8081`, a serwery zarządzania metrykami na `8082` i `8083`.

#### Monitoring DEV

Wybierz `Monitoring -> Przygotuj konfigurację monitoringu DEV` lub uruchom skrypt:

```bash
bash scripts/monitoring/monitoring.sh prepare --profile dev
```

Konfiguracja jest zapisywana w `.novabank/monitoring/.env.monitoring` i `.novabank/monitoring/prometheus.yml`. Hasło `METRICS_PASSWORD` musi być takie samo w plikach aplikacji i konfiguracji Prometheusa. Po jego zmianie zrestartuj bank i sklep.

Uruchom monitoring:

```bash
bash scripts/monitoring/monitoring.sh start --profile dev
```

Sprawdź:

- Prometheus: `http://localhost:9090`;
- Grafana: `http://localhost:3000`;
- Alertmanager: `http://localhost:9093`;
- metryki banku: `http://localhost:8082/actuator/prometheus`;
- metryki sklepu: `http://localhost:8083/actuator/prometheus`.

Targety `bank-demo` i `shop-app` muszą mieć stan `UP`. `401` oznacza brak lub błędne Basic Auth, a `connection refused` zwykle oznacza zatrzymaną aplikację albo nieprawidłowy port zarządzania.

#### Profil TEST

Profil `test` jest przeznaczony do testów automatycznych. Docker musi działać, ponieważ Testcontainers uruchamia tymczasowe PostgreSQL i Redis. Nie trzeba uruchamiać banku i sklepu ręcznie, jeśli test uruchamia własne kontenery.

```bash
./mvnw -Punit-tests test
./mvnw -Pintegration-tests verify
./mvnw -f shop-app/pom.xml -Punit-tests test
./mvnw -f shop-app/pom.xml -Pintegration-tests verify
```

#### Profil PROD i Vault

Profil `prod` wymaga działającego Vault, poprawnego certyfikatu CA, AppRole oraz plików bootstrap. Najpierw uruchom usługę Vault w WSL, ustaw adres i certyfikat aktywnego listenera:

```bash
export VAULT_ADDR='https://127.0.0.1:8200'
export VAULT_CACERT="$PROJECT_WSL/.novabank/tls/vault/vault.crt"
unset VAULT_CAPATH
unset VAULT_SKIP_VERIFY
vault status
```

Jeżeli status zgłasza błąd certyfikatu, skopiuj do `.novabank/tls/vault/vault.crt` certyfikat używany przez `tls_cert_file` w `/etc/vault.d/vault.hcl` i porównaj odciski SHA-256. Nie włączaj `VAULT_SKIP_VERIFY`. Jeżeli Vault jest zapieczętowany, wprowadź trzy z pięciu kluczy unseal. Root token służy do administracji, nie do codziennego uruchamiania aplikacji.

Wybierz `Vault i AppRole -> Skonfiguruj polityki i AppRole PROD`, a następnie utwórz lub uzupełnij:

```text
.novabank/env/prod.bank.env
.novabank/env/prod.shop.env
```

Do tych plików wpisuje się między innymi `VAULT_ROLE`, `VAULT_ROLE_ID`, `VAULT_SECRET_ID`, `VAULT_ADDR`, `VAULT_CACERT`, `VAULT_SSL_TRUST_STORE`, `VAULT_SSL_TRUST_STORE_PASSWORD` i typ truststore. Role ID i Secret ID pochodzą z konkretnego Vault. Trzy klucze unseal, root token i hasło GPG do pliku recovery przechowuj poza repozytorium, najlepiej w zaszyfrowanym katalogu recovery.

W IntelliJ wybierz konfigurację `Bank PROD` albo `Shop PROD`, ustaw profil `prod`, właściwy EnvFile i katalog roboczy. Aplikacje powinny wystartować dopiero po sprawdzeniu `vault status`, odpieczętowaniu i poprawnym teście AppRole.

### Ujednolicony katalog plików lokalnych

Docelowa struktura plików generowanych lokalnie wygląda tak:

```text
.novabank/
  env/
    project.env
    dev.env
    prod.bank.env
    prod.shop.env
  monitoring/
    .env.monitoring
    prometheus.yml
  tls/
    vault/
      vault.crt
      vault-truststore.p12
    prod/
      bank-server.p12
      shop-server.p12
  control-center/
    activity.log
    artifacts-archive/
  pdf-venv/
```

| Plik lub katalog | Przeznaczenie |
|---|---|
| `.novabank/env/project.env` | zmienne Compose i hasła lokalnych baz |
| `.novabank/env/dev.env` | profil DEV, klucze lokalne i ustawienia IntelliJ |
| `.novabank/env/prod.*.env` | bootstrap połączenia aplikacji z Vault |
| `.novabank/monitoring/` | hasła monitoringu i wygenerowany scrape config |
| `.novabank/tls/` | CA Vault, truststore i keystory HTTPS |
| `.novabank/control-center/` | dziennik Centrali i archiwum artefaktów |
| `.novabank/pdf-venv/` | lokalne zależności ReportLab do generowania PDF |

W repozytorium pozostają pliki wymagane przez build i aplikację: `pom.xml`, `shop-app/pom.xml`, Maven Wrapper, kod, testy, migracje Flyway, `application*.yaml`, pliki Compose, polityki Vault, szablony `.example`, dokumentacja i skrypty. Nie przenoś plików Spring/Maven wymaganych podczas budowania do `.novabank`.

Po przeniesieniu repozytorium do innej lokalizacji:

1. ustaw `PROJECT_WIN` i `PROJECT_WSL`;
2. uruchamiaj Control Center z nowego katalogu projektu;
3. wybierz `Pierwsze uruchomienie` i sprawdź `.novabank`;
4. zaktualizuj Working directory oraz EnvFile w IntelliJ;
5. używaj `docker compose --env-file .novabank/env/project.env`;
6. sprawdź, czy lokalne pliki są ignorowane przez Git.

Nie kopiuj bez zmian ścieżek `C:\Dysk D\stuff\Bank-demo` ani `/mnt/c/Dysk D/stuff/Bank-demo` do nowej instalacji. W WSL ścieżka musi odpowiadać rzeczywistemu montowaniu dysku, a w Windows ścieżka musi być zgodna z katalogiem projektu.

### Retencja danych technicznych i audyt

`RetentionJob` usuwa wyłącznie techniczne dane pomocnicze i audytowe po upływie okresów konfiguracji. Nie usuwa ani nie modyfikuje rachunków, sald, transakcji, kart, historii kart, zamówień, płatności, refundacji ani danych potrzebnych do historii finansowej. Pełny numer karty, CVV i data ważności nie mogą trafiać do logów, audytu administratora, metryk ani alertów; model płatności kartą pozostaje bez zmian.

Przykładowe ustawienia:

```text
RETENTION_ENABLED=false
RETENTION_DRY_RUN=true
RETENTION_SCHEDULE=0 0 3 * * *
RETENTION_BATCH_SIZE=500
RETENTION_GRACE_PERIOD_DAYS=7
RETENTION_LOGIN_HISTORY_DAYS=30
RETENTION_NOTIFICATION_DAYS=30
RETENTION_OPERATION_AUDIT_DAYS=90
RETENTION_ADMIN_AUDIT_DAYS=365
RETENTION_RUN_DAYS=365
```

Znaczenie trybów:

- `RETENTION_ENABLED=false` wyłącza zadanie i nie usuwa danych;
- `RETENTION_ENABLED=true` oraz `RETENTION_DRY_RUN=true` tylko zlicza rekordy kwalifikujące się do usunięcia;
- `RETENTION_ENABLED=true` oraz `RETENTION_DRY_RUN=false` wykonuje usuwanie zgodnie z zatwierdzonymi okresami.

Po zmianie zmiennych zrestartuj bank. W profilu `dev` zacznij od dry-run. Panel administratora jest dostępny pod:

```text
http://localhost:8080/admin/data-retention
```

Panel wymaga zalogowanego administratora, poprawnego CSRF i włączenia retencji. Pokazuje okresy, aktywność harmonogramu i ostatnie uruchomienia. `Dry-run` nie modyfikuje danych. Rzeczywiste czyszczenie powinno być poprzedzone kopią zapasową i testem odtworzenia. Równoległe uruchomienia są blokowane przez PostgreSQL, a wynik jest zapisywany w `retention_runs` i audycie administratora.

Bezpieczna kolejność dla PROD:

1. zatwierdź okresy retencji;
2. wykonaj backup obu baz;
3. wykonaj test odtworzenia kopii;
4. uruchom dry-run i sprawdź liczniki;
5. przejrzyj audyt i raport;
6. ustaw `RETENTION_DRY_RUN=false`;
7. monitoruj `bank_retention_deleted_total`, `bank_retention_errors_total` oraz alert `RetentionJobFailed`.

### Operacyjna ściąga

**Codzienny DEV:** uruchom WSL/Docker Engine, Control Center, bazy, oba serwisy w IntelliJ i opcjonalnie monitoring. Sprawdź logi oraz targety Prometheusa.

**TEST:** uruchom testy Maven i Testcontainers. Przy błędzie `JAVA_HOME` sprawdź JDK w WSL albo uruchom test z IntelliJ.

**PROD:** uruchom Vault, ustaw `VAULT_ADDR` i `VAULT_CACERT`, sprawdź status, odpieczętuj trzema kluczami, sprawdź AppRole, a dopiero potem uruchom konfiguracje PROD banku i sklepu. Nie używaj root tokena jako sekretu aplikacji.

Szczegółowe źródła pozostają dostępne:

- `docs/first-run.md`;
- `docs/runtime-layout.md`;
- `docs/data-retention.md`;
- `docs/VAULT-PROD-INSTRUKCJA.md`;
- `docs/production-security.md`;
- `docs/monitoring-preparation.md`;
- `docs/postgres-backup-restore.md`;
- `docs/ci-cd.md`;
- `docs/control-center.md`.

Jeżeli przykład w tej instrukcji różni się od ustawień lokalnych, kieruj się faktycznym profilem, `.novabank`, konfiguracją Compose, konfiguracją IntelliJ i komunikatem Centrali. Przykładowe hasła, klucze, Role ID, Secret ID, tokeny i ścieżki nie są wartościami do kopiowania do środowiska.
