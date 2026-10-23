# Audyt NovaBank, NovaTech Shop i Control Center — 16.10.2026

Aktualizacja 17.10.2026: poniżej zachowano **ustalenia wyjściowe sprzed napraw**. Po wyborze wariantu A rozpoczęto implementację. Aktualny zakres, wyniki testów oraz nadal otwarte kontrole/migracje zawiera [raport wdrożenia](wdrozenie-audytu-2026-10-16.md). Historyczne sformułowania „niezaimplementowane” i numery linii w tym audycie nie są bieżącą oceną zmienionych źródeł. Diagramów nie aktualizowano, zgodnie ze zleceniem.

Ocena dotyczy lokalnego drzewa roboczego `Nova-bank-part3`, łącznie ze zmianami niezatwierdzonymi. Raport nie jest certyfikacją bezpieczeństwa ani testem penetracyjnym. Źródłem prawdy jest kod, nie wcześniejsze opisy ani zrzuty panelu.

## Postęp i zakres

- [x] Inwentaryzacja i stan testów
- [x] Logowanie, uprawnienia, ochrona danych
- [x] Przelewy, płatności, wymiana walut i współbieżność
- [x] Sklep, integracje i schedulery
- [x] Control Center, sekrety i operacje utrzymaniowe
- [x] Struktura klas, pliki i dokumentacja Mermaid
- [x] Weryfikacja ustaleń i plan działań

Zmiany implementacji, usuwanie plików, uruchamianie płatności i operacji administracyjnych nie należą do tego audytu. Sekrety nie są cytowane. Ścieżki i numery linii wskazują kod w momencie analizy.

## 1. Podsumowanie dla właściciela projektu

**Projekt ma działającą bazę i istotne zabezpieczenia, ale nie należy uznawać go za gotowy do obsługi rzeczywistych pieniędzy/danych produkcyjnych.** Najważniejsze problemy są na granicach: formularz–inny sposób uwierzytelnienia, transakcja–obsługa wyjątku oraz bank–sklep. Nie ma podstaw do masowego usuwania klas lub przepisywania aplikacji.

Zapisano **13 ustaleń i ryzyk**: 4 o priorytecie P1, 8 P2 i 1 P3. Część to potwierdzone luki w logice, część ryzyka warunkowe/hardening — nie są to „13 potwierdzonych włamań” ani 13 zweryfikowanych CVE.

| Priorytet | ID | Najważniejszy temat | Zalecenie |
|---|---|---|---|
| P1 | A01 | HTTP Basic poza formularzem nie wymaga 2FA | Oddzielić uwierzytelnianie metryk od kont bankowych |
| P1 | A02 | Finalizacja 2FA nie sprawdza flag disabled | Sprawdzić aktualny stan konta przed instalacją kontekstu |
| P1 | A03 | Odmowa płatności może zniknąć przez rollback-only | Poprawić granice transakcji approve i zapisu błędu |
| P1 | A13 | Nierozstrzygnięta próba może zostać zastąpiona nową | Trwałe próby płatnicze i stan UNKNOWN, zakaz przedwczesnej nowej próby |
| P2 | A04–A09, A11–A12 | Reconciler Stripe, atomowość tokenów/Redis, blokady, timeouty, logi, Docker i CI | Naprawy celowane z testem regresji |
| P3 | A10 | Powiadomienia bez retencji | Jawna retencja kosza/archiwum, nie usuwanie ręczne SQLite |

**Testy wykonane w tym audycie:** bank 120/120, sklep 27/27, 5/5 integracyjnych testów współbieżności, Centrala Python 229 (217 wykonanych, 12 pominiętych), 8/8 zestawów JS — bez błędów. To potwierdza istniejące scenariusze, nie obala brakujących scenariuszy z sekcji 3.

**Stan bieżących usług:** ping Centrali na 18110 odpowiedział HTTP 200. Pod domyślnymi adresami banku/sklepu 8080/8081 i healthcheckami 8082/8083 nie uzyskano odpowiedzi HTTP w krótkich próbach z Windows; healthchecki sprawdzono także z WSL. Nie rozstrzyga to, czy aplikacje są wyłączone, pracują na innych portach czy mają problem z dostępnością. Nie uruchamiano ich w DEV ani nie zmieniano konfiguracji. To obserwacja runtime z chwili audytu, nie dowód błędu implementacji.

**Porządki:** są kandydaci do archiwizacji, w tym pusty `docs/Bank-app2.md`, historyczne podglądy i logi JVM. Niczego nie usunięto. **Diagramy:** nowe można przygotować wiernie, ale część lokalnych starych diagramów już nie odpowiada kodowi; potrzebna jest mapa komunikat → metoda → źródło, nie samo przerysowanie starego PNG.

## 2. Metoda, zakres i ograniczenia

Odczyt kodu i konfiguracji deklaratywnej, analiza wywołań i granic transakcji, porównanie z testami, bezpieczne testy izolowane. Rozróżnienia: **potwierdzone** — wynika z kodu; **ryzyko warunkowe** — wymaga wskazanego scenariusza; **niezweryfikowane** — brak wykonania lub dowodu runtime. Priorytety P1/P2/P3 oznaczają kolejność działań, nie wynik CVSS.

Inwentaryzacja: **180 produkcyjnych plików Java banku i 80 sklepu**, 25 migracji banku i 12 sklepu, frontend statyczny/Thymeleaf, skrypty Bash oraz webowa Centrala Python/JS. Deklarowane wersje: Java 25 i Spring Boot 4.1.0 (`pom.xml:8,30`, `shop-app/pom.xml:8,30`). To audyt przekrojowy: szczegółowo prześledzono krytyczne ścieżki nazwane w ustaleniach, **nie wykonano dowodu poprawności każdej metody wszystkich 260 klas**.

Repozytorium zawiera wiele wcześniejszych zmian i plików nieśledzonych. W tej sesji nie porządkowano ich ani nie zmieniano gałęzi. `git status --short` wskazuje m.in. nieśledzone źródła Centrali oraz migracje V20–V25 banku i V12 sklepu. Stan działającego lokalnego checkoutu nie jest tym samym co stan gotowy do odtworzenia przez CI/nowego programistę. **Przed wydaniem wymagany jest kontrolowany przegląd i zatwierdzenie kompletu źródeł, migracji i testów — nigdy automatyczne dodanie całego `.novabank`/`tmp`.**

Wartości runtime sekretów w `.novabank`, AppRole/Vault, dane użytkowników i zawartość baz DEV nie były przedmiotem odczytu. Inwentaryzacja sekretów w Git była sprawdzeniem nazw śledzonych plików, nie skanem całej historii ani potwierdzeniem braku kiedykolwiek ujawnionych kluczy. Testy tworzyły zwykłe artefakty w `target` i własne tymczasowe środowiska. Poza raportem nie edytowano implementacji.

Dla skrócenia dalszych tabel: **B** = `src/main/java/org/demo/bankdemo/`, **S** = `shop-app/src/main/java/org/demo/shopapp/`, **CC** = `scripts/control-center/web/`. Każde wskazanie jest ścieżką względem katalogu projektu `C:\Dysk D\stuff\Bank-demo`. Numer linii dotyczy tego snapshotu i zmieni się po refaktoryzacji.

## 3. Ustalenia bezpieczeństwa i poprawności

### A01 — P1: alternatywne uwierzytelnienie HTTP Basic omija ścieżkę 2FA

**Potwierdzone w konfiguracji; nie wykonywano próby na rzeczywistym koncie.** `SecurityConfig.java:92–98` włącza równolegle formularz i globalne HTTP Basic; reguła `authenticated()` (`:90`) nie wymaga dodatkowego dowodu MFA. Sprawdzenie `isEnabled` i rozpoczęcie wyzwania występują dopiero w `LoginSuccessHandler.java:64–73`. `DatabaseUserDetailsService.java:36–53` zwraca zwykły `UserDetails` bez stanu MFA. Pełne ścieżki: `src/main/java/org/demo/bankdemo/config/SecurityConfig.java`, `src/main/java/org/demo/bankdemo/auth/LoginSuccessHandler.java`, `src/main/java/org/demo/bankdemo/auth/DatabaseUserDetailsService.java`.

**Skutek:** poprawne hasło przesłane przez Basic może uwierzytelnić żądanie do chronionego zasobu bez wykonania formularzowego success handlera. Dotyczy też kont administracyjnych. Nie oznacza to automatycznego ominięcia CSRF na formularzach; odczyt chronionych danych już jest problemem. Dodatkowo Basic nie przechodzi przez licznik błędów tego handlera i limit dedykowany POST `/login` (`config/BankRateLimitFilter.java:45–51,103–110`).

**Naprawa:** rozdzielić łańcuch aplikacji użytkownika od wąskiego łańcucha metryk/Actuator; nie udostępniać Basic dla kont bankowych na zwykłych endpointach. Metryki mają już własny `MetricsBasicAuthenticationFilter.java:23,36–54`. Uwierzytelnienie oparte na 2FA powinno być wymuszane polityką dostępu, nie tylko przekierowaniem. **Test:** konto z 2FA, Basic na chronionym GET musi zostać odrzucone; zwykły login wymaga kodu; metryki nadal działają właściwymi danymi.

Mechanizm frameworka potwierdza [Spring Security — Basic Authentication](https://docs.spring.io/spring-security/reference/servlet/authentication/passwords/basic.html): filtr uwierzytelnia i kontynuuje łańcuch, niezależnie od success handlera formularza.

### A02 — P1: zakończenie 2FA nie respektuje ponownej blokady użytkownika

**Potwierdzone w kodzie; scenariusz czasowy wymaga testu integracyjnego.** `auth/TwoFactorLoginService.java:109–119` ponownie ładuje `UserDetails`, lecz od razu tworzy token `authenticated(...)`. `auth/DatabaseUserDetailsService.java:47–52` może zwrócić `disabled=true`; nie rzuca wtedy wyjątku. W `complete()` nie ma sprawdzenia `isEnabled()`, `isAccountNonLocked()` i pozostałych flag.

**Scenariusz:** hasło zaakceptowano, trwa 5-minutowe wyzwanie, operator blokuje użytkownika/rachunek, użytkownik podaje poprawny kod. Sam ponowny odczyt nie wystarcza: ręczne utworzenie uwierzytelnionego tokenu omija weryfikację flag przez provider. **Naprawa:** jawnie zweryfikować aktualny status przed instalacją kontekstu, a przy odmowie wyczyścić wyzwanie. **Test:** blokada i cofnięcie weryfikacji email między etapami logowania nie pozwalają zakończyć 2FA. Podane ścieżki względne są pod `src/main/java/org/demo/bankdemo/`.

### A03 — P1: obsługa odmowy płatności we wspólnej transakcji może sama zostać wycofana

**Potwierdzona struktura transakcji; wyjątku końcowego nie odtwarzano na żywej bazie.** `src/main/java/org/demo/bankdemo/payment/authorization/PaymentAuthorizationService.java:90–123` wykonuje `approve()` w transakcji, wywołuje osobny bean `PaymentService.execute()` (`payment/PaymentService.java:59–61`) i łapie m.in. `PaymentException`. Wyjątek dziedziczy po `RuntimeException`. Wewnętrzna metoda korzysta z domyślnego `REQUIRED`; po wyjątku jej proxy może oznaczyć wspólną transakcję jako rollback-only. Zapis `authorization.fail(...)` i zwrot callbacku nie naprawiają tego stanu; zakończenie zewnętrznej metody może dać `UnexpectedRollbackException`, a oznaczenie odmowy nie utrwali się.

**Naprawa:** orkiestracja poza transakcją + osobne, jawne transakcje próby i zapisu wyniku błędu, z ponowną kontrolą statusu autoryzacji; nie stosować bezrefleksyjnego `noRollbackFor` do operacji finansowych. Zachować atomowość sukcesu przelewu i autoryzacji oraz odporność na równoległe approve/reject. **Test:** brak środków, zablokowany rachunek i limit powinny dać trwałą, kontrolowaną odmowę bez 500 oraz bez obciążenia.

**Doprecyzowanie przed implementacją (16.10.2026):** ponowny odczyt wykazał dodatkowy powiązany scenariusz: `PaymentAuthorizationService.java:93–99` sprawdza wygaśnięcie przed stanem końcowym, a `PaymentAuthorization.java:137–139` bezwarunkowo ustawia FAILED. Ponowne approve po terminie może więc zdegradować wcześniejsze COMPLETED/REDEEMED, bez cofnięcia zatwierdzonego obciążenia. `reject():136–143` generuje także `cancelled` dla stanu już zakończonego. To analiza kodu, nie nowo wykonany test. Ochronę stanów końcowych i testy wyścigów dodano do [planu kontroli, sekcje 5–7](<C:/Dysk D/stuff/Bank-demo/docs/plan-kontroli-przed-wdrozeniem-2026-10-16.md>). Samo rozdzielenie transakcji nie zamyka tych przypadków.

Zasady frameworka: [propagacja transakcji](https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/tx-propagation.html), [domyślne ustawienia `@Transactional`](https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/annotations.html).

### A04 — P2: Stripe trafia do schedulera kart bank-demo przez prefiks klucza

**Potwierdzona niespójność zwykłej ścieżki UI.** `shop-app/src/main/resources/static/checkout.html:206–210` dla zewnętrznego operatora wysyła klucz `card-<orderNumber>`. `shop-app/src/main/java/org/demo/shopapp/order/ExternalCardPaymentService.java:32–47` zapisuje ten klucz i operatora Stripe. Tymczasem `OrderRepository.java:119–133` wybiera każde PENDING z kluczem `card-%`, a `PaymentReconciliationJob.java:63–67,85–87` pyta bank-demo o kartę bez sprawdzenia `paymentProvider`.

**Skutek:** oczekujące Stripe może otrzymać trzy błędy od niewłaściwego bankowego endpointu i zostać zawieszone z mylącym alertem (`PaymentReconciliationJob.java:172–179`). Poprawny webhook wciąż może ustawić PAID — nie jest to dowód podwójnego pobrania środków. **Naprawa:** trwały typ próby płatniczej/provider, filtrowanie SQL i dispatch według niego, osobne uzgadnianie Stripe. Prefiks klucza nie jest typem operacji. **Test:** PENDING Stripe nigdy nie uruchamia `bankClient.cardStatus`, niezależnie od klucza.

### A05 — P2: token resetowania hasła nie jest atomowo jednorazowy

**Potwierdzony brak blokady/warunkowego zużycia; wyścigu nie uruchamiano.** `src/main/java/org/demo/bankdemo/auth/EmailTokenService.java:120–131` odczytuje token, sprawdza `isUsable`, zmienia hasło i dopiero oznacza użycie. `PasswordResetTokenRepository.java:7–11` nie stosuje blokady, a `PasswordResetToken.java:16–38,58–63` nie ma wersji. Encja użytkownika także nie deklaruje `@Version`.

**Skutek:** dwa równoległe żądania z tym samym poprawnym linkiem mogą przejść wstępne sprawdzenie; ostatni zapis może ustalić hasło. To wymaga posiadania tokenu, nie umożliwia odgadywania go. **Naprawa:** `SELECT FOR UPDATE` tokenu i sprawdzenie po blokadzie albo atomowy UPDATE z `used_at IS NULL` w tej samej transakcji; ustalić również politykę wielu równoległych żądań wydania tokenów. **Test:** dwa równoległe resety — dokładnie jeden sukces. Podobny schemat ma weryfikacja email (`EmailTokenService.java:85–95`); skutek resetu hasła jest poważniejszy.

### A06 — P2: niespójna obsługa blokad i powtórzeń między przelewem a płatnością

**Potwierdzone wzorce kodu; skutki wyścigów wymagają testów PostgreSQL.** `payment/PaymentService.java:73–81` ładuje zarządzane encje rachunków przed blokadą, a `payment/authorization/PaymentAuthorizationService.java:105–108` może załadować źródło jeszcze wcześniej we wspólnej transakcji. `card/CardService.java:264–280` robi podobnie dla płatności online. Dla porównania `transfer/TransferService.java:96–109` celowo pobiera tylko ID przed blokadą, aby nie zachować starego snapshotu.

`AccountRepository.java:61–69` blokuje zapytaniem, bez jawnego `refresh`; `Account.java:47–49` ma `@Version`. Dlatego **nie stwierdzam utraty salda**: konflikt może być wykryty i wycofany. Ryzykiem są niepotrzebne odmowy/wyjątki wersji i niejednolite zachowanie przy oczekiwaniu na blokadę; mechanizmy persistence context i optimistic locking opisuje [Hibernate User Guide](https://docs.jboss.org/hibernate/orm/current/userguide/html_single/Hibernate_User_Guide.html). Dodatkowo `CardService.pay()` (`:160–175`) i `payOnline()` (`:237–280`) nie sprawdzają ponownie klucza po uzyskaniu blokad, w odróżnieniu od `TransferService.java:114–117` i `ExchangeService.java:165–169`. Unikalność w bazie chroni przed zatwierdzeniem duplikatu, lecz równoległy retry może otrzymać błąd zamiast poprzedniego wyniku.

**Naprawa:** wspólna zasada: scalar ID → uporządkowane blokady → świeży stan → ponowny odczyt klucza → walidacja → zapis. Uwzględnić cały łańcuch wywołań, nie tylko wnętrze serwisu; samo poprawienie `PaymentService` nie usuwa wcześniejszego odczytu w `approve()`. Dodać kontrolowaną odpowiedź retry dla API kart/płatności; obecny `web/BankOperationExceptionHandler.java:12–16` dotyczy tylko trzech kontrolerów formularzowych. **Test:** równoległa płatność i przelew z tego samego rachunku oraz równoległe identyczne żądania karty. Ścieżki w tym punkcie zaczynają się od `src/main/java/org/demo/bankdemo/`.

### A07 — P2: brak jawnych timeoutów HTTP i zbyt długa transakcja zwrotu

**Potwierdzone w kodzie.** `shop-app/src/main/java/org/demo/shopapp/config/ShopBankConfiguration.java:25–32` buduje `RestClient` bez jawnej fabryki/timeouts; podobnie `payment/StripeCheckoutClient.java:22–24`. `order/RefundService.java:28–38,66–84` utrzymuje transakcję i blokadę zamówienia podczas zewnętrznego HTTP.

**Skutek warunkowy:** wolna/niedostępna integracja może długo zajmować wątek, połączenie DB i blokadę. Nie ustalono efektywnego timeoutu wybranej w runtime implementacji klienta, więc raport **nie przypisuje mu arbitralnie nieskończonej wartości**. **Naprawa:** jawne connect/read/request timeouts, budżet czasowy operacji, klasyfikacja błędów i retry wyłącznie z tym samym kluczem. Dla zwrotu: utrwalić próbę w krótkiej transakcji, wykonać HTTP poza nią, zatwierdzić wynik osobno, z kontrolą konkurencji. Przerwanie procesu po sukcesie banku musi zostawić próbę możliwą do uzgodnienia, nie tylko rollback lokalnego wpisu. Nie dodawać retry generującego nowe klucze.

### A08 — P2: profil prod nie wymusza SMTP, możliwe tokeny resetu w logach

**Potwierdzone ustawienia domyślne; nie sprawdzano wartości środowiska użytkownika.** `src/main/resources/application.yaml:38–39` ma domyślny tryb email `log`. `auth/EmailDeliveryService.java:26–48` buduje link weryfikacji/resetu i dla dowolnego trybu innego niż SMTP wypisuje całą wiadomość na stdout, razem z tokenem i adresem. `config/ProductionConfigurationValidator.java:19–43` sprawdza Vault/TLS/cookie, ale nie wymusza trybu SMTP. `application-prod.yaml` nie nadpisuje trybu email.

**Skutek:** jeśli uruchomiono prod bez poprawnego `EMAIL_MODE`, tokeny umożliwiające reset trafiają do logów, zamiast wyłącznie do odbiorcy. Redakcja w Centrali nie usuwa pierwotnej linii z logu aplikacji. **Naprawa:** fail-fast w prod dla trybu innego niż SMTP; jawny enum trybu zamiast traktowania literówki jak `log`; nie logować ciała wiadomości poza świadomie izolowanym DEV. Logi DEV z takimi linkami też traktować jako poufne. **Test:** prod + `log`/literówka nie uruchamia aplikacji, a wysyłka nie ujawnia linku w dzienniku. Ścieżki Java względem `src/main/java/org/demo/bankdemo/`.

### A09 — P2: operacje Redis ustawiają wartość i TTL osobnymi poleceniami

**Potwierdzone; ryzyko awarii między poleceniami.** `src/main/java/org/demo/bankdemo/config/DistributedRateLimitService.java:40–44`, `auth/LoginAttemptService.java:48–50`, `transfer/TransferRateLimitService.java:27–29`, analogicznie `shop-app/src/main/java/org/demo/shopapp/config/ShopDistributedRateLimitService.java:41–43`: INCR i dopiero potem EXPIRE. `transfer/TransferTotpChallengeService.java:56–57` zapisuje hash i TTL osobno.

**Skutek:** przerwanie po zapisie, przed TTL, może pozostawić klucz bez wygaśnięcia. Szczególnie groźny operacyjnie jest stały klucz błędów logowania: po przekroczeniu progu blokada może nie wygasnąć zgodnie z założeniem. Liczniki okien zawierające numer okna raczej kumulują śmieci niż blokują nowe okno na zawsze. Hash challenge przechowuje też dane przelewu. **Naprawa:** atomowy skrypt Redis dla increment+TTL i zapisania challenge; monitor kluczy bez TTL tylko w dozwolonych prefiksach. Nie usuwać wszystkich kluczy Redis. **Test:** przerwanie/failure injection po pierwszej komendzie nie zostawia nieograniczonej blokady.

### A10 — P3: kosz powiadomień nie ogranicza rozmiaru historii SQLite

**Potwierdzone.** `scripts/control-center/web/notifications.py:26–31,73–95` utrwala wpisy; stany archived/deleted są logiczne (`:40–43`). Stronicowanie (`:282`) ogranicza odpowiedź, nie liczbę rekordów. Przeszukanie tego modułu nie wykazało retencji usuwającej stare powiadomienia; jedyny DELETE dotyczy rezerwacji próby naprawy. `maintenance_operations.py:73` jawnie wyłącza historię panelu z porządkowania.

**Skutek:** długotrwały strumień różnych WARN/ERROR powiększa bazę i kosz bez limitu. To nie jest dowód obecnego wycieku pamięci ani uszkodzenia danych. **Naprawa:** jawna polityka retencji archiwum/kosza, eksport przed purge, limit wieku/rozmiaru i metryka; zachować aktywne/incydentowe wpisy oraz historię wymaganych operacji.

### A11 — P2, hardening: sekrety nie są wyłączone z kontekstu builda Docker

**Potwierdzony brak wykluczenia; nie potwierdzono ujawnienia w obrazie.** `.dockerignore:1–17` nie zawiera `.novabank/` ani katalogów danych Vault/TLS. `scripts/backup/backup.sh:758–766` przekazuje całe repo jako kontekst DR. Aktualne `infra/disaster-recovery/Dockerfile.bank:4–6` i `Dockerfile.shop:4–6` kopiują tylko POM i źródła, więc **nie ma podstaw twierdzić, że obraz zawiera sekrety**; BuildKit może też ograniczyć rzeczywisty transfer do używanych plików.

**Naprawa:** wykluczyć `.novabank`, lokalne dane/recovery/TLS Vault i inne materiały prywatne z kontekstu niezależnie od `.gitignore`; dopuścić wyłącznie potrzebne źródła. Zabezpiecza to przyszłe COPY, zmianę buildera i użycie zdalnego daemonu. **Test:** izolowany kontekst z plikiem-canary zamiast rzeczywistych sekretów; nie wolno go skopiować do etapu builda.

### A12 — P2: CI nie uruchamia testów webowego Control Center

**Potwierdzone w repo.** `.github/workflows/ci.yml:19–72` uruchamia testy Java, kolejne zadania budują i sprawdzają HTTP E2E bank/sklep. W całym workflow brak uruchomienia `unittest` oraz ośmiu zestawów `test_*.js` Centrali. To istotna luka regresji dla edytora sekretów, działań PROD, importu timerów i kont.

**Naprawa:** dodać obowiązkowy job Python+Node z izolowanymi testami; live SQL/systemd tylko w osobnym, kontrolowanym jobie. Dodać walidację diagramów, wykrywanie sekretów w zmianach i analizę zależności. Nie oznaczać jako potwierdzonego CVE bez osobnego SCA. Bieżące źródła Centrali są też nieśledzone w Git — czysty checkout CI nie zawiera lokalnych nowości (patrz sekcja 2).

### A13 — P1, ryzyko warunkowe: nowa próba płatności może utracić powiązanie z nierozstrzygniętą poprzednią

**Potwierdzone przejścia stanów; scenariusz podwójnego obciążenia nie był wykonywany.** `shop-app/src/main/java/org/demo/shopapp/order/OrderPaymentService.java:32–48` oznacza FAILED po każdym RuntimeException, także niejednoznacznym błędzie HTTP/zapisu po stronie sklepu. `web/ShopPaymentCallbackController.java:47–75` może oznaczyć FAILED po problemie z redeem, mimo że zapłata mogła już nastąpić w bankowym approve. `OrderRepository.java:156–169` blokuje nowy klucz tylko w PENDING, nie w FAILED; `:181–188` nadpisuje identyfikator autoryzacji, bez osobnej historii prób. `PaymentService` deduplikuje po kluczu (`src/main/java/org/demo/bankdemo/payment/PaymentService.java:66–70`), a nie po samym zamówieniu.

Możliwy łańcuch:

1. Bank zatwierdza próbę A, ale callback/redeem/zapis wyniku w sklepie nie daje potwierdzonego sukcesu.
2. Sklep zapisuje FAILED, zamiast UNKNOWN/PENDING wymagającego uzgodnienia.
3. Klient API ponawia to samo zamówienie nowym kluczem B (zwykły frontend może powtórzyć ten sam klucz — ryzyko dotyczy klienta używającego innego).
4. Sklep dopuszcza B i zastępuje powiązanie autoryzacji A; reconciler widzi już bieżące powiązanie.
5. B może zostać obciążone jako odrębna operacja. Unikalny klucz A nie chroni przed B.

**Naprawa:** osobna, trwała encja/tabela `PaymentAttempt`, jedno aktywne rozstrzygnięcie finansowe na zamówienie, osobny stan UNKNOWN, zakaz nowej próby do czasu potwierdzonej odmowy/wygaśnięcia poprzedniej. Callback przeglądarki nie jest autorytatywnym dowodem odrzucenia. `markPaid` powinno sprawdzać oczekiwaną próbę/referencję (`OrderRepository.java:250–265` obecnie aktualizuje po samym ID). **Test:** bank wykonuje płatność, odpowiedź jest tracona, ponowienie z innym kluczem nie tworzy drugiego obciążenia, a A nadal podlega uzgodnieniu.

**Warunki zamknięcia:** [plan kontroli, sekcje 6–7](<C:/Dysk D/stuff/Bank-demo/docs/plan-kontroli-przed-wdrozeniem-2026-10-16.md>) rozszerza to zalecenie o zapis próby przed HTTP, ograniczenia DB, blokadę zmiany providera przy UNKNOWN, zachowanie referencji i ochronę biznesowej tożsamości zakupu. Wygaśnięcie musi być potwierdzonym stanem końcowym wykluczającym późniejsze obciążenie — nie samym upływem czasu formularza. Trzy błędy schedulera oznaczają zawieszenie uzgadniania, nie pozwolenie na nową płatność. A03 i A13 pozostają niezaimplementowane.

## 4. Architektura i propozycje podziału klas

### 4.1. Gdzie podział rzeczywiście pomoże

| Obecny obszar | Dowód wielu odpowiedzialności | Proponowany kierunek | Granica, której nie wolno przypadkiem zmienić |
|---|---|---|---|
| `B/card/CardService.java` (594 linie) | Widok/lifecycle `:73–137`, lokalna płatność `:148–218`, online `:221–366`, odczyt statusu `:369–393` | `CardLifecycleService`, `CardPaymentService`, `OnlineCardPaymentService`, `CardPaymentQueryService`; wspólna polityka wyceny i limitów | Salda, karta i księga muszą pozostać spójne w jednej lokalnej transakcji; samo przeniesienie metody nie rozwiązuje A06 |
| `B/payment/PaymentService.java` i `B/transfer/TransferService.java` | Duplikują uporządkowane blokowanie kont i podobne limity (`PaymentService:73–98,196–238`; `TransferService:96–140,200–223`) | Mały `AccountLockCoordinator` i wspólne funkcje limitów, dopiero po testach A06 | Osobne autoryzacje płatności i przelewu; nie tworzyć „uniwersalnego execute” obchodzącego PIN/TOTP albo refund linkage |
| `B/web/TransferController.java` | Kontroler orkiestruje PIN, ryzyko, challenge, audyt i wywołanie księgowania `:172–219,223–245` | `TransferConfirmationWorkflow`; kontroler pozostawia binding, model i redirect | Nie przesunąć wyjątków do transakcji księgowania; nie zmienić powiązania challenge z konkretnymi danymi przelewu |
| `B/web/ProfileController.java` | Profil, limity, 2FA, hasło i PIN mają osobne endpointy `:67–296` | Wydzielić kontrolery profilu, bezpieczeństwa i limitów; wspólny builder modelu | Testować ten sam zestaw reguł dostępu i CSRF po przeniesieniu |
| `S/order/OrderRepository.java` (446 linii) | Dane zamówień, pojedynczy slot płatności, reconciliation i cleanup `:139–202,250–342` | `OrderRepository` + `PaymentAttemptRepository` + osobne zapytania reconciliation | Najpierw migracja i model stanów A13; nie tylko kosmetyczne rozcięcie pliku |
| `CC/server.py` (1179 linii) | `Panel.submit:299`, `command:374`, procesy `:395,451,524`, wykonanie `:534`, HTTP `:813,1003` | `JobService/JobStore`, `ProcessSupervisor`, endpointy tematyczne, centralny dispatcher | Jedna bramka autoryzacji, allowlista operacji, blokada pojedynczego zadania, maskowanie i odmowa operacji PROD bez potwierdzeń muszą być wspólne |
| `CC/app.js` (551 linii) | Lista/logi `:187–234`, inne moduły widoków i operacji w tym samym pliku | `jobs-ui`, `notifications-ui`, `cash-ui`; stopniowe ograniczanie globalnego stanu | Ochrona przed starymi odpowiedziami, zmiana profilu i zachowanie potwierdzeń; bez wymuszonej wymiany całego frontendu |
| `B/history/HistoryExportService.java` (413 linii) | CSV `:41–55`, PDF i rysowanie `:57–90` | Oddzielny renderer CSV i PDF, serwis zapytań już istnieje | Zachować weryfikację właściciela oraz ochronę formuł w CSV (`:137–144`) |

Nazwy klas docelowych powyżej to **propozycje**, nie nazwy istniejących implementacji. Nie potrzeba od razu mikroserwisów, nowych frameworków ani wspólnego modułu dla wszystkiego. Przy obecnej wielkości korzystniejszy jest mały podział według odpowiedzialności i jedna zmiana granicy transakcji naraz.

### 4.2. Konstruktor z zależnościami `null` to dług testowy, nie oddzielny tryb bezpieczeństwa

`B/web/TransferController.java:45–77` ma konstruktor runtime z `@Autowired` oraz krótszy delegujący `null`; `:174,225` dopuszcza wtedy pominięcie części ochrony. Analogiczny wzorzec ma `B/profile/ProfileService.java:34–65` oraz inne klasy kompatybilności testowej. **Nie stwierdzono, że Spring w produkcji wybiera krótszy konstruktor** — adnotowany wskazuje właściwy. Problemem jest możliwość budowania w testach obiektu bez realnych zależności bezpieczeństwa i uzyskania pozornie zielonych testów.

Zalecenie: jeden konstruktor produkcyjny ze wszystkimi wymaganymi zależnościami; fixture/fabryka w testach tworząca jawne mocki. Usunąć `null`-guardy bezpieczeństwa dopiero po przeniesieniu wszystkich wywołań i testów. Nie usuwać przeciążeń wyłącznie dlatego, że wyglądają na duplikaty.

### 4.3. Co pozostawić rozdzielone

Oddzielny `ScheduledTransferExecutionService` jest celowy: `ScheduledTransferService.java:136–155` utrzymuje pętlę poza transakcją, a `ScheduledTransferExecutionService.java:28–57` obejmuje transakcją pojedyncze wykonanie i oddzielny zapis błędu. Połączenie ich w jedną klasę z wywołaniem `this.execute()` może wyłączyć działanie proxy i przywrócić wcześniejszy problem wspólnego rollbacku. Podobnie nie łączyć `OrderStateService` z klientem HTTP tylko dla skrócenia ścieżki wywołań.

## 5. Pliki potencjalnie zbędne i zasady porządkowania

**Żadnego pliku nie usunięto.** Zbędność potwierdzamy użyciem i wartością archiwalną, nie nazwą `tmp`, `old` czy `CLI`.

| Kandydat | Ustalenie | Rekomendacja / warunek |
|---|---|---|
| `docs/Bank-app2.md` | Plik pusty, 0 bajtów; nie jest kopią treści rootowego `Bank-app2.md` (41 862 bajty) | Można rozważyć usunięcie pustego szkicu po sprawdzeniu linków w Obsidian; lokalne szukanie nazwy w dokumentacji/kodzie nie znalazło referencji |
| `replay_pid18424.log`, `replay_pid31300.log` | Wygenerowane logi JVM, łącznie ok. 1,65 MB; sprawdzono nazwy/rozmiary, nie zawartość | Zachować do zakończenia diagnostyki JVM, potem archiwizować/usunąć; traktować jako potencjalnie poufne; są ignorowane przez Git |
| `target/`, `shop-app/target/` | Wyniki kompilacji/testów, odtwarzalne | Czyścić po zabezpieczeniu raportów i bez trwającego buildu; usunięcie JAR blokuje start z Centrali do następnego builda |
| Historyczne XML w `target/surefire-reports/` | Pozostał m.in. raport `BankOperationConcurrencyIT` z 11.10 z 6 errors, obok bieżących wyników unit; nowy wynik 5/5 IT z 16.10 jest w `target/failsafe-reports/` | Nie sumować bez rozróżnienia starych/nowych przebiegów. Zarchiwizować dowody i przygotować czyste katalogi raportów dla kolejnych uruchomień |
| `scripts/control-center/web/__pycache__/` | Cache Pythona, ignorowany | Odtwarzalny; zbędny do repo, ale nie wymaga pilnej ręcznej ingerencji |
| `CC/preview_local.py`, `preview_accounts.py`, `preview_support.py` | Helpery podglądu; nie znaleziono referencji do ich nazw w sprawdzonych źródłach i dokumentacji | Raczej przenieść do jawnego katalogu narzędzi QA albo udokumentować; brak referencji nie dowodzi, że nie są uruchamiane ręcznie |
| `docs/control-center-*preview*` | 23 pliki, ok. 2,09 MB; część to historyczne proof-of-work | Utrzymać aktualne podglądy i linki, historyczne przenieść do archiwum i poprawić odwołania; nie kasować wszystkich wildcardem |
| `docs/control-center-concept-01/02/03-*.png` | Dokumentacja nadal je wymienia (`docs/control-center-web.md`, sekcja koncepcji) | Nie usuwać bez aktualizacji dokumentacji; to projekty, nie dowód aktualnego runtime |
| `tmp/vault-hsin/prepared/*.png` | Trzy pliki graficzne, ok. 30,06 MB; to nie pliki sekretów Vault według samych metadanych | Autorskie materiały graficzne, nie śmieci runtime. Przenieść do docelowych assets/archiwum dopiero po decyzji właściciela; nie odczytywano jako sekretów |
| `Prompt 2.md`, rootowy `Bank-app2.md`, `docs/Bank-app.md` | Specyfikacja i dokumentacja, nie część wykonywalna | Uporządkować pod `docs/` z jednoznaczną wersją kanoniczną i aktualnymi linkami; zachować treść oraz historię |

**Nie usuwać jako „stare”:** migracji Flyway (również V1…V19), `.novabank/env`, lokalnej historii/SQLite Centrali, kopii baz i materiałów odzyskiwania, skryptów zaplecza używanych przez panel WWW, testów SQL/systemd tylko dlatego, że są domyślnie pomijane. Dezaktywacja menu terminalowego nie oznacza nieużywania `scripts/backup/backup.sh` czy `scripts/control-center/web-operations.sh` (dispatcher `CC/server.py:374–393`).

Zależności POM zawierają miejsca do przeglądu redundancji: starter sesji Redis plus bezpośrednia zależność session-data-redis w banku (`pom.xml:59–70`), startery JDBC i Flyway oraz ich składniki w sklepie (`shop-app/pom.xml:41–50,90–116`). To **kandydaci do `dependency:tree/analyze`**, nie potwierdzone zbędne biblioteki ani konflikt wersji. Oba moduły kompilują się i przechodzą wykonane testy; nie jest to dowód pełnego clean builda każdego profilu. Nie usuwać zależności bez odtworzenia clean builda i testów obu profili.

Domyślna polityka porządkowania powinna być: lista kandydatów → kopia/archiwum → przegląd linków/użycia → osobna zatwierdzona operacja. Istniejący `CC/maintenance_operations.py:14–24,26–73` ogranicza ścieżki i chroni m.in. pliki prywatne; nie rozszerzać go na całe repo.

## 6. Przepływy i zgodność przyszłych diagramów Mermaid

### 6.1. Czy można stworzyć wierne diagramy?

**Tak, ale trzeba oddzielić „jak kod działa obecnie” od „jak ma działać po poprawkach”.** Mermaid sam nie weryfikuje implementacji. Poprawny składniowo diagram z pominiętym Basic, rollbackiem czy reconcilerem nadal jest merytorycznie niepełny. Dla czytelności lepiej utrzymywać kilka diagramów po 15–30 komunikatów i wspólną mapę metod niż jedną gigantyczną sekwencję.

W tej sesji porównano dokumenty **w repozytorium**, nie wszystkie notatki na dysku `E:` ani wszystkie wcześniej wygenerowane PNG. Nie ma podstaw automatycznie uznać wszystkich notatek Obsidian za błędne lub aktualne.

| Lokalny diagram | Rozbieżność potwierdzona z kodem | Źródło prawdy |
|---|---|---|
| `docs/Bank-app.md:1224–1250` — logowanie | Umieszcza `LoginSuccessHandler` dopiero po kodzie; w kodzie handler wybiera ścieżkę 2FA już po haśle, a kończy ją kontroler/`TwoFactorLoginService`. Przypisuje handlerowi HMSET/SADD i cookie, pomija osobne GET dashboard oraz alternatywne Basic | `B/auth/LoginSuccessHandler.java:51–79`; `B/auth/TwoFactorLoginService.java:104–124`; `B/config/SecurityConfig.java:98,178–182`; `B/web/FrontendController.java:55–76` |
| `docs/Bank-app.md:1267–1281` — przelew | Pokazuje execute po POST `/transfer/confirm`; rzeczywiście confirm przygotowuje preview/draft/challenge, a księguje POST `/transfer/execute`. Weryfikacja TOTP jest przed serwisem księgowania, nie w jego wnętrzu | `B/web/TransferController.java:100–127,153–195` |
| `docs/Bank-app.md:1307–1322` — scheduler | Pomija bean pojedynczego wykonania, blokadę/recheck occurrence, stabilny klucz i oddzielny zapis błędu oraz retry błędu przejściowego | `B/transfer/ScheduledTransferService.java:136–155`; `ScheduledTransferExecutionService.java:28–64` |
| `docs/Bank-app.md:1338–1352` — kantor | Sugeruje przekazywanie walut/kwoty bezpośrednio do execute i wycenę w tym momencie. Kod przyjmuje klucz przygotowanej wyceny DB, sprawdza właściciela, replay i ważność | `B/web/ExchangeController.java:45–89`; `B/currency/ExchangeService.java:115–146,156–212` |

W rootowym `Bank-app2.md` oryginalny uproszczony diagram jest zachowany jako materiał wejściowy (`:36–60`), a dalszy tekst już wskazuje m.in. błędne przypisanie poleceń Redis (`:201,216,235`). Nie należy traktować zachowanego oryginału jako automatycznie zaakceptowanego modelu. Potrzebny jest widoczny status **oryginał / aktualny / projekt po naprawie**.

### 6.2. Minimalny podział nowych diagramów i mapowanie na metody

1. **Login formularzem, 2FA off/on, błędy i wejście na panel.** POST `/login` → Security + CSRF/rate limit → `DatabaseUserDetailsService.loadUserByUsername` → BCrypt → `LoginSuccessHandler.onAuthenticationSuccess`. Bez 2FA: historia i redirect. Z 2FA: `TwoFactorLoginService.start` → pusty kontekst → POST `/login/2fa` → `AuthController.verifyTwoFactor` → `TwoFactorService.verifyLoginCode` → `TwoFactorLoginService.complete`. Redirect 302 → **nowy GET `/dashboard` lub zapisany cel** → odtworzenie kontekstu → `FrontendController.dashboard` → serwisy danych → `dashboard/index`. Pochodzenie: B/auth, `SecurityConfig.java:92–111`, `FrontendController.java:55–76`. Osobny diagram alternatywnego Basic z ostrzeżeniem A01 do czasu naprawy.
2. **Przelew natychmiastowy i utworzenie zlecenia stałego.** Osobno preview/stan sesji/PIN/ryzyko/challenge, osobno `TransferService.execute`, blokady, limity i idempotencja. Utworzenie harmonogramu nie jest jeszcze transferem salda (`ScheduledTransferService.create:44–88`). Wykonanie cykliczne zasługuje na własny diagram transakcji (`executeDue` → `executionService.execute`, rollback → `recordFailure`).
3. **Płatność sklep → bank przez autoryzację.** `S/order/OrderPaymentService.java:22–48` (`pay`) → API key/scopes/rate limiter → `B/payment/authorization/PaymentAuthorizationService.java:46–71` (`create`) → URL `/shop/authorize/{id}` → ewentualny login → `display/approve` → `PaymentService.execute` → commit i callback code → `S/web/ShopPaymentCallbackController.java:31–91` (`callback`) → serwerowy redeem → `OrderStateService.markPaid`. Rozróżnić moment obciążenia (approve) od potwierdzenia w sklepie (redeem/markPaid). Dodać A03/A13 jako obecne ścieżki błędów, a po poprawce nowe ścieżki UNKNOWN.
4. **Karta bank-demo kontra Stripe.** Nie łączyć obu w „płatność kartą” z jednym bankowym endpointem. bank-demo: sesja karty i `CardOrderPaymentService` → `CardService.payOnline` → status/reconciler. Stripe: `ExternalCardPaymentService` → Stripe Checkout → podpisany webhook → `markPaidExternal`; redirect sukces/cancel nie dowodzi zapłaty ani jej braku (`S/web/StripeWebhookController.java:112–132`). Dodać obecny błędny dispatch A04 albo wyraźnie oznaczyć diagram docelowy.
5. **Kantor.** `ExchangeController.quote` → `ExchangeService.prepareQuote` → kursy z DB i trwały snapshot z kluczem; osobny POST execute → `executePrepared` → replay/właściciel/TTL 10 minut → blokada rachunku i ponowny odczyt klucza → debit/credit subkonta → `ExchangeTransaction` → commit → 302 i GET `/exchange`. Cleanup starych wycen to osobny scheduler (`ExchangeService.java:149–153`), nie część każdego żądania.

**Sesja a Redis:** brak 2FA nie oznacza braku sesji. Spring Session obsługuje `HttpSession` niezależnie od challenge; namespace sesji, liczniki limitów i hash challenge to różne rodzaje danych. Nie wolno rysować bezpośredniego zapisu sesji przez `LoginSuccessHandler` ani obiecywać, że każda nieudana próba wykonuje ten sam zestaw zapisów Redis. Żądanie może zakończyć się na CSRF, rate limiterze lub przed utworzeniem sesji. Cookie wysyła warstwa HTTP, nie Redis. Parametry sesji włącza `src/main/resources/application.yaml:21–25,104–110`; kontekst repozytorium `B/config/SecurityConfig.java:178–182`.

**TOTP:** `B/profile/TwoFactorService.java:166–174` używa okresu 30 sekund i dopuszcza odchylenie 1 okresu. Diagram z samym „oknem 30s” nie opisuje pełnej tolerancji weryfikatora. Nie dopisywać konkretnego HMSET, SADD ani serializerów bez sprawdzenia użytej biblioteki lub obserwacji izolowanego runtime.

### 6.3. Jak utrzymać zgodność

Każdy diagram powinien mieć: commit/snapshot, datę, listę komunikatów `K*`, tabelę metoda–plik:linia, jawną granicę commit/rollback i scenariusze błędów. W CI walidować składnię Mermaid; w testach sprawdzać kontrakt zachowania (nie porównywać samego obrazka). PNG jest pochodną Markdown: zapisać źródłową sekcję, wersję renderera i hash treści, aby wykryć nieaktualny obraz. Po zmianie kodu testy/diagramy aktualizować w tym samym przeglądzie. W audycie **nie podmieniano dotychczasowych notatek ani PNG**.

## 7. Testy i luki w weryfikacji

### 7.1. Wykonane w tej sesji

| Sprawdzenie | Wynik | Ograniczenie |
|---|---|---|
| WSL, `bash ./mvnw -o -Punit-tests test` — bank | 120 testów, 0 błędów/odrzuceń | Większość to mocki/unit lub WebMvc, nie dowód współbieżności wszystkich serwisów |
| WSL, analogiczne polecenie w `shop-app` | 27 testów, 0 błędów/odrzuceń | Nie odtwarza wyścigu dwóch różnych prób A/B i całego Stripe end-to-end |
| WSL, `bash ./mvnw -o -Pintegration-tests -Dit.test=BankOperationConcurrencyIT verify` | 5 testów, 0 błędów/odrzuceń; 25 migracji banku na świeżej bazie | Własny PostgreSQL 16 + Redis 6.2.6, nie bazy DEV; to nie pełny zestaw IT |
| WSL, `python3 -B -m unittest discover -s scripts/control-center/web -p 'test_*.py'` z wyłączonymi flagami live | 229 testów, OK; 12 pominiętych | 217 wykonanych; nie uruchamiano prób SQL/systemd wymagających opt-in |
| Node, wszystkie `scripts/control-center/web/test_*.js` | 8 zestawów OK | Fixture DOM, nie pełna regresja przeglądarkowa wszystkich widoków |
| Odczyt ping/health/publicznych stron | Centrala 200; domyślne porty aplikacji bez odpowiedzi | Brak prawa do wniosku „błąd kodu”; nie uruchamiano zatrzymanego DEV |

Testy współbieżności (`src/test/java/org/demo/bankdemo/infrastructure/BankOperationConcurrencyIT.java:81–171`) potwierdziły: jeden debit przy retry tego samego przelewu, przeciwne kierunki blokujące w tej samej kolejności, pojedyncze utworzenie harmonogramu, jeden debit/credit przy powtórzeniu wymiany, timeout blokady bez debit i udane ponowienie tego samego klucza. **Nie obejmują PaymentService/CardService ani A01–A05/A13.** Ostrzeżenie PostgreSQL o lock timeout w tym przebiegu było oczekiwanym elementem testu. Reconnect Redis przy zamykaniu kontenera testowego nie dowodzi awarii Redis użytkownika.

Weryfikacja raportów wykazała **historyczne XML z innych uruchomień**, m.in. 6 errors testu IT z 11.10 w katalogu Surefire. Nie są wynikiem bieżącego audytu: dzisiejszy Maven zgłosił 120/120 unit oraz 5/5 w Failsafe, a daty plików to potwierdzają. Nie usuwano starych raportów, aby nie zatrzeć dowodów. W automatycznych podsumowaniach stosować osobny katalog/run-id albo czysty katalog raportów, nie sumę wszystkich pozostawionych XML.

DEV Compose deklaruje PostgreSQL 18 i Redis 7.4 (`docker-compose.yaml:5,24,43`). IT używa starszych wersji (`BankOperationConcurrencyIT.java:41–43`). Dodać drugi wariant zgodny z DEV/PROD-like zamiast wyciągać wniosek, że jeden wynik gwarantuje identyczne zachowanie każdego środowiska.

Nie uruchamiano pełnego zestawu testów integracyjnych sklepu, zewnętrznych płatności Stripe, backupu/DR, rotacji Vault, penetracji, skanera CVE/SAST/SCA, stress testu ani skanowania całej historii Git pod kątem sekretów. Dlatego nie deklaruje się „braku podatności” ani certyfikacji gotowości PROD. Ostrzeżenie Mockito o dynamicznym agencie JDK jest długiem konfiguracji testów, nie wykrytą luką aplikacji.

### 7.2. Najważniejsze nowe testy regresji

W pierwszej kolejności: Basic kontra MFA; blokada pomiędzy hasłem a kodem; odmowa approve bez rollback-only; utrata odpowiedzi banku i nowy klucz; Stripe pominięty przez bankowy reconciler; równoległe zużycie tokenu hasła. Następnie: płatność konkurująca z przelewem/wymianą, równoległy retry kart, brak odpowiedzi HTTP i wznowienie zwrotu, awaria między zapisem Redis a TTL, nowy proces ze spójnymi migracjami i konfiguracją.

### 7.3. Dodatkowe kontrole przed większym wdrożeniem

**Szczegółowy plan przed implementacją:** [kontrole K1–K7, podział agent/operator oraz warunki rozwiązania A03/A13](<C:/Dysk D/stuff/Bank-demo/docs/plan-kontroli-przed-wdrozeniem-2026-10-16.md>). Plan określa procedury, dowody i kryteria zaliczenia; nie oznacza wykonania tych nowych kontroli ani wdrożenia poprawek.

- **Klucz TOTP to nie zwykłe hasło konfiguracyjne.** `CC/configuration_files.py:59–64,178–181` pozwala zapisać nowy klucz po potwierdzeniu ryzyka; `B/security/TotpSecretCipher.java:24–26,44–63` odszyfrowuje istniejące dane jednym aktualnym kluczem. Ostrzeżenie w UI istnieje, ale nie zastępuje migracji szyfrogramów. Docelowo osobna operacja rotacji z key-id, kopią i kontrolą odszyfrowania.
- **Sesje współdzielone to nie lokalny monitor Java.** `B/transfer/TransferDraftStore.java:17–36` synchronizuje obiekt `HttpSession` i zapisuje jedną mapę; `B/auth/TwoFactorLoginService.java:90–101` wykonuje read-modify-write licznika. Przed uruchomieniem wielu instancji dodać atomowość w centralnym magazynie i test równoległych żądań. Dziś nie udowodniono tu utraty danych ani obejścia MFA.
- **Powtórne użycie TOTP:** `B/profile/TwoFactorService.java:119–145,166–174` weryfikuje kod bez trwałego rejestru zużytego kroku czasowego; recovery codes mają osobną blokadę i mark-used. Ustalić politykę replay TOTP i przetestować ją; kod jednorazowy z aplikacji nie jest automatycznie atomowym, jednorazowym tokenem biznesowym.
- **Reguły bezpieczne dla przyszłych endpointów.** `S/config/ShopSecurityConfig.java:72` kończy `permitAll`, a bank wyłącza CSRF dla całego `/api/**` (`B/config/SecurityConfig.java:105`). Obecne krytyczne ścieżki mają dodatkowe reguły/API-key, lecz nowe endpointy powinny domyślnie wymagać jawnej polityki. Nie zgłoszono na tej podstawie konkretnego działającego CSRF na obecnym API płatności.
- **Proxy/Forwarded headers:** oba profile prod włączają obsługę nagłówków frameworka (`application-prod.yaml:29` bank, `:25` sklep). Zweryfikować odcinanie bezpośredniego dostępu do backendu i nadpisywanie nagłówków przez zaufane proxy. Nie badano sieci wdrożeniowej ani nie potwierdzono exploita przekierowania.
- **Granica zaufania Centrali:** `CC/server.py:786–803,1100–1101` poprawnie ogranicza host/origin i bind do loopback, ale token z bootstrap (`:822–825`) nie jest logowaniem operatora. Lokalny proces/użytkownik z dostępem do tego portu należy do domeny zaufania. Nie wystawiać panelu reverse proxy do sieci jako gotowego panelu wieloużytkownikowego. Dla takiego zastosowania potrzebne osobne uwierzytelnienie/RBAC/audyt.
- **Wydajność po wzroście danych:** `B/transfer/ScheduledTransferRepository.java:17–19` pobiera wszystkie due; `B/history/HistoryService.java:140–145` wszystkie pasujące rekordy eksportu. Rozważyć batch/cursor i limity eksportu. Nie zastępować tego arbitralnym wycinaniem historii klienta.

## 8. Zalecana kolejność prac

1. **Przed udostępnianiem poza zaufany lokalny DEV:** A01 i A02, z testami negatywnymi faktycznego uwierzytelnienia. Zabezpieczyć A08 i kontekst Docker A11. Nie testować naprawy prawdziwymi danymi klientów.
2. **Przed dalszym rozwijaniem płatności:** A03 i A13. Najpierw ustalić trwały model próby i status UNKNOWN; dopiero potem rozdzielać klasy. Zablokować nowy klucz przy nieznanym wyniku poprzedniej operacji.
3. **Ujednolicić integracje:** A04, A06, A07; bezpieczny retry, provider dispatch, świeży stan po blokadzie i obsługa spóźnionych callbacków. Dołączyć testy PostgreSQL oraz próbę utraty odpowiedzi/commit.
4. **Atomowość i regresja utrzymaniowa:** A05, A09, A12; dodać testy do CI i uwzględnić obecnie nieśledzone pliki. Osobno uzgodnić retencję A10, uprawnienia do plików WSL/Windows i zakres rotacji klucza TOTP.
5. **Refaktoryzacja dla czytelności:** zacząć od modelu prób płatności, `CardService` i `server.py`, nie od masowego dzielenia każdej małej klasy. Każdy krok powinien zachować routing, granice transakcji i istniejące zachowanie lub jawnie je naprawić.
6. **Dokumentacja i porządki:** nowe kanoniczne diagramy według sekcji 6, a następnie PNG z jednoznacznym źródłem. Archiwizacja kandydatów z sekcji 5 dopiero po sprawdzeniu odwołań także w Obsidian. Nie usuwać migracji ani skryptów wspólnego zaplecza.

**Kryterium zamknięcia audytu naprawczego:** testy regresji dla P1 i A04/A06/A07 przechodzą na środowisku izolowanym; świeży checkout buduje oba moduły i Centralę; migracje działają na nowej bazie i kopii starej; awaria/timeout nie daje drugiego obciążenia; kod MFA nie ma alternatywnej ścieżki hasłowej; dokumentacja odróżnia faktyczny commit od redirectu i renderowania widoku.

## 9. Co już działa dobrze i powinno zostać zachowane

- Przelewy mają uporządkowane blokowanie pary rachunków, sprawdzenie idempotencji po blokadzie i księgowanie w jednej transakcji (`B/transfer/TransferService.java:76–161`); pięć testów IT potwierdziło wskazane scenariusze przelewów/wymiany.
- Wymiana nie ufa ponownie kwocie z formularza execute: trwała wycena ma właściciela, klucz i czas ważności, a replay nie księguje ponownie (`B/currency/ExchangeService.java:115–146,165–212`; migracja `src/main/resources/db/migration/V24__persist_exchange_quotes.sql:1–15`).
- Scheduler rozdziela pojedyncze transakcje i utrzymuje stabilną tożsamość occurrence (`B/transfer/ScheduledTransferExecutionService.java:28–57`). Nie wracać do jednej transakcji obejmującej całą pętlę.
- Zwroty bankowe są powiązane z oryginałem i mają unikalny indeks tego powiązania (`B/payment/PaymentService.java:137–165`; `src/main/resources/db/migration/V20__link_payment_refunds.sql:27–29`).
- Hasła/PIN są hashowane, tokeny email przechowywane jako SHA-256, sekret TOTP szyfrowany AES-GCM z losowym IV (`B/config/SecurityConfig.java:167–169`; `B/profile/TransactionPinService.java:34–41`; `B/auth/EmailTokenService.java:148–165`; `B/security/TotpSecretCipher.java:29–40`). Problem A05 dotyczy atomowości zużycia tokenu, nie jego odgadywalności.
- API bankowe ma kontrolę klucza, scope, ważności i limitów (`B/config/PaymentApiKeyFilter.java:65–95`). Status karty weryfikuje dane zamówienia/merchant, nie tylko klucz (`B/card/CardService.java:369–393`).
- Centrala ma zamknięty zestaw operacji, kontrole Origin/Host, brak cache odpowiedzi, maskowanie, stałe ścieżki plików, ochronę rewizji i jawne potwierdzenia (`CC/server.py:299–372,786–803,1003–1005`; `CC/configuration_files.py:140–181`). Nie wyłączać tych mechanizmów w imię upraszczania kodu.
- PostgreSQL i Redis w lokalnym Compose są publikowane na loopback; nie uruchamiano ani nie zmieniano wolumenów DEV w trakcie audytu (`docker-compose.yaml:12,31,46`).

Wniosek: **najpierw poprawić spójność bezpieczeństwa i stanów płatności, potem upraszczać klasy oraz regenerować diagramy.** Same porządki w plikach ani zielony build nie rozwiązują problemów na granicach systemów.

## 10. Weryfikacja raportu i pozostawiony stan

Sprawdzono istnienie wskazanych źródeł i zakresów numerów linii, liczbę ustaleń, wyniki uruchomionych testów oraz brak w raporcie literalnych kluczy/tokenów/prywatnych numerów rachunków. Odwołania nie są dowodem odtworzenia exploita — przy każdym scenariuszu zaznaczono poziom potwierdzenia. Nie wykonywano poprawek kodu, usuwania plików, zmiany timerów, restartu DEV ani operacji finansowych. Pozostał raport i standardowe artefakty testów/builda. Docelowe usuwanie plików, refaktoryzacja i naprawy wymagają osobnego wdrożenia.
