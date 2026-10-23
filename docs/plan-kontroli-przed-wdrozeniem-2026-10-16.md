# Plan kontroli przed większym wdrożeniem i doprecyzowanie A03/A13

Aktualizacja 17.10.2026: użytkownik wybrał wariant A (trwały merchantId) i zlecił implementację z atomowym UPDATE w A05 oraz bez zmiany diagramów. Ten dokument zachowuje plan i opis stanu sprzed napraw. Rzeczywisty zakres, wyniki regresji i niewykonane bramki operatora opisuje [raport wdrożenia](wdrozenie-audytu-2026-10-16.md); nie należy wywodzić aktualnego wyniku testu z historycznego checkboxa poniżej.

Data: 16.10.2026. Repozytorium: `C:\Dysk D\stuff\Bank-demo`, bieżące drzewo robocze `Nova-bank-part3`.

Dokument rozwija sekcję 7.3 [audytu aplikacji](<C:/Dysk D/stuff/Bank-demo/docs/audyt-aplikacji-2026-10-16.md>) i zalecenie dotyczące A03/A13. **To plan przed implementacją, nie protokół pozytywnie zakończonych kontroli.** W ramach jego przygotowania ponownie odczytano wskazane źródła; nie zmieniano implementacji, sekretów, danych, usług ani harmonogramów. Nie uruchamiano nowych testów ani operacji finansowych. Wyniki testów z wcześniejszego audytu nie stanowią zaliczenia nowych scenariuszy opisanych poniżej.

Oznaczenia: **[KOD]** — ustalenie ze źródeł; **[PLAN]** — proponowane zachowanie/test; **[DECYZJA]** — wymaga uzgodnienia z właścicielem/operatorem; **[DO SPRAWDZENIA]** — niepotwierdzone w docelowym środowisku. Nazwy nowych klas, tabel i stanów są propozycjami, a nie opisem istniejącej implementacji.

Skróty ścieżek, względem repozytorium powyżej: `B/` = `src/main/java/org/demo/bankdemo/`, `S/` = `shop-app/src/main/java/org/demo/shopapp/`, `CC/` = `scripts/control-center/web/`. Numery linii dotyczą kodu z chwili analizy.

## 1. Odpowiedź: czy A03 i A13 rozwiązują problem?

**Tak — jako kierunek naprawy przyczyn, ale nie jako samo dodanie `UNKNOWN`, nowej klasy lub `REQUIRES_NEW`.** A03 ma zapewnić poprawne lokalne transakcje banku. A13 ma zachować tożsamość i historię próby pomiędzy sklepem, bankiem i operatorem płatności. Potrzebne są obie naprawy, egzekwowane w bazie i sprawdzone testami awarii/współbieżności.

Minimalny kompletny zakres:

1. Sukces: salda, zapis księgowy i zakończenie autoryzacji bankowej zatwierdzają się razem.
2. Odmowa: zapis wyniku następuje dopiero po zakończeniu wycofywanej transakcji, bez nadpisania równoległego sukcesu.
3. Brak pewności: timeout, utrata odpowiedzi lub błąd potwierdzenia oznaczają wynik do uzgodnienia, nie dowód braku obciążenia.
4. To samo zamówienie nie otrzymuje nowej niezależnej próby/klucza/operatora, gdy poprzednia może jeszcze zakończyć się obciążeniem.
5. Stare próby i ich referencje nie są zastępowane nową zawartością pojedynczego pola zamówienia.
6. Potwierdzony sukces nie przechodzi w odmowę wskutek spóźnionego callbacku, anulowania albo upływu czasu autoryzacji.
7. Uzgadnianie odtwarza wynik po restarcie, błędzie zapisu sklepu i utracie callbacku; zawieszenie schedulera nie odblokowuje nowej płatności.

**Nie jest to obietnica globalnego „exactly once” przez HTTP i dwie niezależne bazy.** Celem jest najwyżej jedno skuteczne obciążenie danej operacji biznesowej, trwałe śledzenie nierozstrzygnięć i bezpieczne odtworzenie informacji. Przy braku dostępnego, autorytatywnego wyniku bezpiecznym zachowaniem bywa blokada nowej próby i interwencja operatora, a nie automatyczne ponowne pobranie pieniędzy.

## 2. Podział odpowiedzialności dla siedmiu kontroli z 7.3

„Agent sam” oznacza możliwość wykonania technicznego w ramach osobno zleconej kontroli/implementacji, nie zgodę na obecną zmianę działającego DEV/PROD. Testy mają korzystać z danych syntetycznych i osobnych instancji PostgreSQL/Redis. Test wymagający brakującego mechanizmu będzie najpierw testem odtwarzającym problem, a nie zaliczoną kontrolą.

| ID | Kontrola | Co mogę wykonać sam na kodzie / w izolacji | Co musi zapewnić lub zatwierdzić użytkownik/operator | Obecny status |
|---|---|---|---|---|
| K1 | Rotacja klucza szyfrującego TOTP | Inwentaryzacja miejsc użycia; projekt migracji; test stary/nowy klucz na syntetycznej bazie i sesjach; próba przerwania i wznowienia | Dostęp do rzeczywistego magazynu kluczy, bezpieczna kopia i procedura odtworzenia; okno zmiany i akceptacja rotacji | Analiza kodu; test migracji i rotacja niewykonane |
| K2 | Sesja i równoległe żądania | Dwa procesy aplikacji ze wspólnym testowym Redis; rzeczywiste HTTP z tym samym cookie; testy draftów i wyzwania MFA | Docelowa liczba instancji, zachowanie load balancera oraz akceptacja scenariuszy wielokartowych | Ryzyko zidentyfikowane; testy niewykonane |
| K3 | Jednorazowość TOTP | Testy powtórzenia sekwencyjnego i równoległego; granice okna; atomowe zużycie kroku; regresja recovery codes | Akceptacja UX: komunikat o zużytym kodzie i oczekiwanie na nowy; zasady ponownego powiązania tokena | Brak rejestru zużycia w przejrzanej metodzie; brak testu replay |
| K4 | Domyślne reguły dostępu | Macierz endpointów/identyfikacji/CSRF; automatyczne testy odmowy, ról, właściciela, kluczy i zakresów | Lista świadomie publicznych funkcji/integracji i wymaganych ról; akceptacja ewentualnej niezgodności API | Reguły kodu przejrzane; pełna macierz do wykonania |
| K5 | Proxy i nagłówki Forwarded | Testowe proxy, fałszywe nagłówki, adresy przekierowań i cookies w izolacji | Rzeczywista topologia, konfiguracja proxy/firewalla/DNS/TLS i możliwość sprawdzenia z zewnętrznej sieci | Włączenie obsługi nagłówków potwierdzone; granica sieci niezweryfikowana |
| K6 | Granica zaufania Control Center | Testy Host/Origin/tokena/bind, odmowy operacji i maskowania na sztucznych danych | Wskazanie, kto ma dostęp do hosta/WSL; decyzja lokalny panel czy odrębny projekt zdalnego RBAC | Zabezpieczenia lokalne widoczne w kodzie; rzeczywiste udostępnienie niezweryfikowane |
| K7 | Wydajność i wzrost danych | Syntetyczne zbiory, pomiary pamięci/czasu/zapytań, plany zapytań w testowej bazie; regresja batchowania | Docelowe wolumeny, limity zasobów i dopuszczalne opóźnienia; zgoda na kontrolowany test staging | Wzorce pobrania danych potwierdzone; pomiar niewykonany |

Użytkownik nie musi osobiście uruchamiać wszystkich poleceń administracyjnych. Mogę wykonać część operacyjną po otrzymaniu odpowiedniego dostępu i wyraźnego zlecenia. Nie mogę natomiast samodzielnie ustalić realnej topologii, zaakceptować ryzyka biznesowego ani potwierdzić działania z sieci, do której nie mam dostępu. Sekretów nie należy wklejać do czatu/raportu.

## 3. Wspólny protokół kontroli

1. Utrwalić identyfikację badanej wersji: commit oraz zakres lokalnych zmian; nie zatwierdzać automatycznie całego brudnego drzewa. Powtórzyć test na wersji przewidzianej do wydania.
2. Stworzyć odrębne środowisko testowe i potwierdzić jego adresy/namespace. Zabronione: testowe migracje, generowanie obciążenia i fault injection na istniejących bazach DEV/PROD.
3. Dla każdego testu zapisać: ID, wersję, datę, konfigurację bez wartości wrażliwych, oczekiwanie, rzeczywisty wynik, artefakty i osobę wykonującą.
4. Rozróżniać `PASS`, `FAIL`, `BLOCKED` i `NIE WYKONANO`. Pominięty test albo poprawne HTTP 302 nie dowodzą zatwierdzenia transakcji ani poprawnego stanu docelowego.
5. Dowód finansowy: liczba i referencje operacji, stan autoryzacji/próby/zamówienia oraz zmiany sald. Dowód MFA: wynik chronionego żądania i stan kontekstu, a nie tylko komunikat formularza.
6. Logi i raporty maskują tokeny, cookie, kody autoryzacyjne, sekrety TOTP i dane identyfikujące klientów. Klucze idempotencji/correlation ID powinny być techniczne, bez danych osobowych.
7. Po testach posprzątać wyłącznie własne, jednoznacznie zidentyfikowane środowisko testowe. Nie usuwać wolumenów ani archiwów użytkownika.

## 4. Szczegółowy plan K1–K7

### K1. Rotacja klucza szyfrującego TOTP

**[KOD]** `B/security/TotpSecretCipher.java:21–25,28–57` używa jednego klucza i formatu `IV.szyfrogram` bez identyfikatora klucza. Walidacja długości/Base64 jest w `:61–74`. `B/profile/TwoFactorService.java:65–75` zapisuje także zaszyfrowany sekret rozpoczętej konfiguracji w `HttpSession`. Sama migracja pola użytkownika nie pokrywa więc wszystkich aktywnych szyfrogramów. Edytor ostrzega o ryzyku w `CC/configuration_files.py:178–181`.

**Agent — kontrola techniczna:**

1. Zmapować wszystkie miejsca szyfrowania/odszyfrowania oraz aktywne sesje konfiguracji 2FA, bez odczytu realnych sekretów.
2. Przygotować dwa sztuczne klucze i użytkowników z syntetycznymi sekretami; pokazać, że prosta podmiana klucza uniemożliwia odszyfrowanie starych wartości.
3. Sprawdzić projekt `key-id` i okresu odczytu starym/nowym kluczem. Ponownie zaszyfrować **ten sam sekret TOTP** nowym kluczem, zamiast generować użytkownikowi inny sekret i unieważniać jego aplikację uwierzytelniającą.
4. Przetestować partiami migrację, checkpoint, awarię między partiami, wznowienie i raport liczebności. Błędny szyfrogram ma przerwać/izolować problem według jawnej procedury, nie zostać nadpisany pustą wartością.
5. Sprawdzić niedokończony setup w sesji: kompatybilne odszyfrowanie albo kontrolowane rozpoczęcie konfiguracji od nowa. Przetestować mieszane wersje instancji podczas wdrożenia.
6. Sprawdzić odtworzenie kopii i zgodność wersji aplikacji/schematu/kluczy. Nie raportować treści odszyfrowanych danych.

**Użytkownik/operator:** zatwierdza magazyn i uprawnienia kluczy, okno rotacji, politykę zachowania starego klucza i tryb odtworzenia. Dopiero po udanej próbie zatwierdza pracę na rzeczywistych danych. Stary klucz nie może zniknąć przed zakończeniem migracji oraz rozstrzygnięciem potrzeb odczytu kopii i sesji.

**Zaliczenie:** wszystkie poprawne rekordy testowe i przewidziane sesje są obsłużone; kody z dotychczasowego tokena nadal działają; awaria nie gubi sekretów; istnieje sprawdzona procedura odtworzenia. **STOP:** brak starego klucza, nieodtwarzalna kopia lub szyfrogram o nieustalonym pochodzeniu. Rotacja w obecnej postaci wymaga najpierw implementacji mechanizmu migracji.

### K2. Sesje współdzielone i współbieżność

**[KOD]** `B/transfer/TransferDraftStore.java:16–33` synchronizuje obiekt sesji i zapisuje kopię całej mapy, z limitem 10 draftów (`:14,20–21`). `B/auth/TwoFactorLoginService.java:90–101` zwiększa liczbę prób metodą odczyt–modyfikacja–zapis. Lokalny monitor nie stanowi blokady między procesami.

**Agent — kontrola techniczna:**

1. Uruchomić dwie izolowane instancje banku ze wspólnym testowym Redis/PostgreSQL; kierować równoległe żądania raz do jednej, raz do obu instancji, używając tego samego cookie sesji.
2. Zapisać równolegle dwa różne drafty. Oba muszą być dostępne po zakończeniu żądań. Sprawdzić limit 10 bez arbitralnego usuwania świeższego draftu wskutek nadpisania starej mapy.
3. Wysłać serię równoległych błędnych kodów oraz poprawny kod na granicy limitu pięciu prób. Oczekiwane: brak utraconych zliczeń i brak akceptacji po wyczerpaniu limitu zgodnie z ustaloną kolejnością atomowych operacji.
4. Równolegle finalizować jedno wyzwanie, wykonać logout, sprawdzić zmianę ID sesji i wygaśnięcie. Zakończone/usunięte wyzwanie nie może odżyć przez późniejszy zapis starej sesji.
5. Powtórzyć scenariusze po zatrzymaniu jednej instancji; przy awarii Redis żądanie nie może uzyskać uwierzytelnienia „na skróty”.

**Użytkownik/operator:** potwierdza liczbę instancji, sposób routingu i oczekiwany UX wielu kart. Sticky sessions nie zastępują kontroli atomowości; test z jednym współdzielonym obiektem `MockHttpSession` też jej nie dowodzi.

**Zaliczenie:** stan oraz licznik są zgodne po odczycie z magazynu przez drugi proces; brak odtworzenia wyzwania i podwójnego efektu. Jeśli test wykryje wyścig, najpierw potrzebny będzie centralny atomowy magazyn wyzwania/draftów lub operacje CAS, nie samo rozszerzenie `synchronized`.

### K3. Powtórne użycie TOTP

**[KOD]** `B/profile/TwoFactorService.java:118–145,166–174` sprawdza TOTP bez trwałego zużycia zaakceptowanego kroku. Recovery codes mają blokowany odczyt i `markUsed` (`:139–141`). Weryfikator tworzy własny `SystemTimeProvider` (`:171`), dlatego deterministyczne testy czasu wymagają kontrolowanego punktu wstrzyknięcia czasu, a nie przestawiania zegara komputera użytkownika.

**[PLAN]** Zapisać atomowo zużycie zaakceptowanego kroku czasowego dla konkretnego tokena/generacji sekretu, wspólnie dla użyć tego tokena. Nie przechowywać surowego kodu jako klucza deduplikacji. Same wyzwania przelewu nadal muszą wiązać zgodę z konkretną operacją. Zasada jednorazowości po udanej walidacji wynika z [RFC 6238, sekcja 5.2](https://www.rfc-editor.org/rfc/rfc6238#section-5.2); samo poprawne wyliczenie kodu nie zapewnia tej własności.

**Agent — kontrola techniczna:** ten sam kod dwa razy sekwencyjnie i równolegle na dwóch instancjach; wykorzystanie kodu logowania do nowego potwierdzenia operacji; tolerowane sąsiednie kroki, cofnięcie czasu i nowa generacja sekretu; dwa równoległe użycia recovery code. Powtórzenie tego samego już zatwierdzonego żądania biznesowego ma zwracać zapisany wynik, nie wykonywać nowej operacji i nie wymagać ponownego „zużycia” TOTP.

**Użytkownik/operator:** akceptuje komunikaty i UX oczekiwania na kolejny kod, w tym przy szybkim przejściu logowanie → przelew. Nie rekomenduje się akceptowania replay tylko po to, żeby zlikwidować oczekiwanie. Sposób ewentualnego step-up/session assurance wymaga osobnego projektu, nie ukrytego pominięcia walidacji.

**Zaliczenie:** jeden krok tokena skutecznie autoryzuje najwyżej jedno nowe użycie, także równolegle; recovery code jest jednokrotny; retry zakończonej operacji nie księguje ponownie. W obecnej metodzie brak mechanizmu pozwalającego uznać tę kontrolę za zaliczoną.

### K4. Bezpieczne reguły dla endpointów

**[KOD]** `S/config/ShopSecurityConfig.java:72` kończy reguły `permitAll`, a `B/config/SecurityConfig.java:105` wyłącza CSRF dla `/api/**`. To wymaga przeglądu kontekstu uwierzytelnienia każdego endpointu, a nie ogólnego wniosku, że wszystkie endpointy są niezabezpieczone. A01/A02 są osobnymi zależnościami bezpieczeństwa opisanymi w audycie.

**Agent — kontrola techniczna:**

1. Sporządzić macierz: URL, metoda HTTP, właściciel danych, rola, sesja/Basic/API key, wymagany scope, CSRF, publiczność i odpowiedź na odmowę.
2. Testować brak/błędny/wygasły klucz, niewłaściwy scope, anonimowego klienta, zwykłego użytkownika, administratora, cudze ID oraz utratę uprawnień pomiędzy etapami.
3. Dla operacji opartych na cookie sprawdzić brak/błędny token CSRF i obce Origin. Dla bezstanowych API udokumentować, dlaczego i jaki inny mechanizm chroni wywołanie.
4. Testować Basic kontra MFA i zmianę flag konta podczas wyzwania. Sprawdzić rzeczywistą odmowę chronionej operacji, nie sam redirect.
5. Zaprojektować regresję dla nowego endpointu bez jawnej polityki: ma być odrzucony. Zachować świadomie publiczny katalog, start logowania i dozwolone callbacki, z właściwą kontrolą podpisu/state/odczytem autorytatywnego stanu.

**Użytkownik/operator:** zatwierdza macierz ról i publicznych integracji, zwłaszcza wymagania zgodności istniejących klientów API. **Zaliczenie:** każda operacja zmieniająca stan ma jednoznaczną politykę; testy odmowy nie zmieniają danych; zamierzona publiczność jest jawna. A01/A02 nie mogą pozostać otwarte przy akceptacji uwierzytelnienia.

### K5. Proxy i Forwarded headers

**[KOD]** `src/main/resources/application-prod.yaml:29` i `shop-app/src/main/resources/application-prod.yaml:25` ustawiają `forward-headers-strategy: framework`. Nie potwierdzono konfiguracji prawdziwej sieci wdrożeniowej.

**Agent — kontrola techniczna:** testowe proxy i backend; podrobione `Forwarded`, `X-Forwarded-Host`, `X-Forwarded-Proto`, `X-Forwarded-For`; sprawdzenie hosta/schematu przekierowań, cookies i identyfikacji IP używanej do limitów/audytu. Porównać wejście przez proxy oraz próbę bezpośrednią. Zaufana granica ma usuwać dane nagłówków przyniesione przez niezaufanego klienta i ustawiać własne — zgodnie z [dokumentacją Spring, Forwarded Headers / Security Considerations](https://docs.spring.io/spring-framework/reference/web/webmvc/filters.html#filters-forwarded-headers).

**Użytkownik/operator:** dostarcza rzeczywistą topologię i konfigurację bramy oraz firewall/security groups; zapewnia test z zewnętrznej sieci. Mogę przeanalizować i wykonać sprawdzenia po uzyskaniu dostępu, ale lokalny test nie dowodzi blokady portu w docelowej infrastrukturze.

**Zaliczenie:** backend niedostępny z niezaufanej sieci z pominięciem bramy; podstawione nagłówki nie zmieniają docelowego hosta/schematu ani nie pozwalają obejść polityki IP. **STOP wdrożenia zewnętrznego:** nieustalona granica zaufania lub możliwość bezpośredniego podania zaufanych nagłówków do backendu.

### K6. Granica zaufania Control Center

**[KOD]** `CC/server.py:786–803` weryfikuje Host/Origin i ustawia nagłówki ochronne; bind do loopback jest w `:1100–1101`. Bootstrap zwraca token w `:822–825`, a mutacje weryfikują token w `:1003–1005`. To nie jest osobne logowanie operatora i RBAC.

**Agent — kontrola techniczna:** sprawdzić bind/socket, obcy Host/Origin, brak/błędny token, próbę wywołania nieznanej operacji i zabezpieczenia ścieżek plików. Na sztucznych sekretach sprawdzić maskowanie list, błędów i logów oraz brak cache. Przetestować rozróżnienie DEV/TEST/PROD i potwierdzenia bez uruchamiania prawdziwej rotacji Vault czy operacji produkcyjnej.

**Użytkownik/operator:** ustala, kto ma dostęp do hosta, WSL i port forwarding. Potwierdza, że Centrala ma pozostać lokalna, lub zleca osobny zakres: uwierzytelnienie operatorów, RBAC, audyt i bezpieczne udostępnienie zdalne. Dostęp lokalnego złośliwego procesu/użytkownika pozostaje poza ochroną samym tokenem bootstrap.

**Zaliczenie dla wariantu lokalnego:** brak niezamierzonej ekspozycji sieciowej; negatywne testy HTTP nie uruchamiają operacji; maskowanie działa. Pozytywny wynik nie oznacza gotowości panelu do wystawienia przez reverse proxy jako usługi wieloużytkownikowej.

### K7. Wydajność i wzrost danych

**[KOD]** `B/transfer/ScheduledTransferRepository.java:17–19` pobiera wszystkie pasujące zlecenia due; `B/history/HistoryService.java:140–145` pobiera wszystkie pasujące rekordy eksportu.

**Agent — kontrola techniczna:**

1. Przygotować serie danych syntetycznych, przykładowo 100/1000/10 000 zleceń oraz 1000/10 000/100 000 wpisów historii. Są to proponowane rozmiary testu, nie ustalone wymagania ani zgoda na takie obciążenie DEV.
2. W kontrolowanym limicie czasu/CPU/RAM mierzyć czas i pamięć, liczbę zapytań, czas blokad, opóźnienie wykonania zlecenia oraz przerwany eksport. `EXPLAIN ANALYZE` wyłącznie w bazie testowej.
3. Sprawdzić scheduler konkurujący z ręcznymi operacjami i kolejnym przebiegiem; brak podwójnego wykonania i utraty terminu. Wydajność nie może być poprawiana kosztem idempotencji lub transakcji pojedynczego zlecenia.
4. Dopiero z pomiaru wyprowadzić batch/cursor/streaming oraz ograniczenia eksportu. Sprawdzić kompletność, deterministyczną kolejność i restart pomiędzy partiami; nie ucinać bez komunikatu historii klienta.

**Użytkownik/operator:** określa przewidywane wolumeny, profil sprzętu, dopuszczalne opóźnienie i pamięć; zatwierdza próg zatrzymania testu staging. **Zaliczenie:** uzgodnione limity spełnione przy zachowaniu poprawności. Bez uzgodnionych progów można dostarczyć pomiar, ale nie stwierdzenie „wydajność wystarczająca dla produkcji”.

## 5. A03 — plan poprawnych transakcji autoryzacji bankowej

### 5.1. Co jest obecnie nieprawidłowe

**[KOD]** `B/payment/authorization/PaymentAuthorizationService.java:90–123`: transakcyjne `approve` wywołuje transakcyjne `PaymentService.execute` (`B/payment/PaymentService.java:59–61`) i przechwytuje wyjątek, po czym próbuje zapisać odmowę we wspólnej transakcji. Przechwycenie wyjątku nie usuwa oznaczenia rollback-only. Zasady `REQUIRED` i niezależnych transakcji opisuje [Spring: Transaction Propagation](https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/tx-propagation.html).

### 5.2. Docelowe granice — propozycja, nie istniejące klasy

1. **Orkiestrator bez aktywnej transakcji** przyjmuje żądanie i wywołuje osobny publiczny punkt transakcyjny. Samo zdjęcie adnotacji z jednej metody nie wystarczy, jeśli jej caller wnosi transakcję; trzeba sprawdzić cały call chain i wymusić tę granicę.
2. **Transakcja bankowego zatwierdzenia** blokuje autoryzację, sprawdza stan końcowy przed terminem ważności, weryfikuje dane i uruchamia `PaymentService` jako uczestnika tej samej fizycznej transakcji. Salda, księga i `COMPLETED` z referencją zatwierdzają się razem. Callback sukcesu wolno wydać po udanym commit, nie tylko po `saveAndFlush`.
3. **Wyjątek opuszcza punkt transakcyjny.** Zanim orkiestrator go obsłuży, transakcja musi się zakończyć i zwolnić blokady. Nie wolno uznać braku odpowiedzi na commit za dowód rollbacku.
4. **Klasyfikacja wyniku:** potwierdzona odmowa biznesowa bez obciążenia może zostać zapisana w nowej krótkiej transakcji. Błąd infrastruktury, niepewny commit czy brak możliwości sprawdzenia wyniku wymagają uzgodnienia. Nie klasyfikować po dowolnym `RuntimeException` lub tekście komunikatu.
5. **Zapis odmowy / wyniku uzgadniania** ponownie blokuje autoryzację i sprawdza jej wersję/tożsamość oraz księgowanie po stałym kluczu. Nie nadpisuje `COMPLETED`/`REDEEMED` ani nowszego rozstrzygnięcia równoległego żądania. Odmowa jest finalna tylko wtedy, gdy wcześniejsza operacja nie może już zostać zatwierdzona.
6. **Nieudany zapis wyniku** pozostawia rozpoznawalne nierozstrzygnięcie i alert. Nie wysyła fałszywego potwierdzenia trwałej odmowy ani nie odblokowuje nowej próby w sklepie.

Możliwe wykonanie: osobny bean obsługiwany przez proxy albo jawne granice `TransactionTemplate`. Dobór jest wtórny wobec powyższych zasad. Wywołanie własnej metody z adnotacją nie może być traktowane jako dowód przejścia przez proxy.

**Czego nie robić:** nie stosować ogólnego `noRollbackFor`, nie wyodrębniać samego księgowania w niezależny commit przed zakończeniem autoryzacji, nie dodawać bez analizy `REQUIRES_NEW` wewnątrz transakcji trzymającej jej wiersz. Zawieszona transakcja nadal może trzymać zasoby; druga może czekać na jej blokadę lub połączenie. To ryzyko techniczne opisane również w [dokumentacji propagacji Spring](https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/tx-propagation.html).

### 5.3. Dodatkowy scenariusz wykryty przy doprecyzowaniu planu

**[KOD]** W `B/payment/authorization/PaymentAuthorizationService.java:93–99` `approve()` sprawdza wygaśnięcie **przed** stanem końcowym. `B/payment/authorization/PaymentAuthorization.java:137–139` bezwarunkowo ustawia `FAILED`. Zatem ponowne `approve` po terminie może zmienić uprzednio `COMPLETED` albo `REDEEMED` w `FAILED`, mimo wcześniejszego zatwierdzenia pieniędzy. Jest to ustalenie z kolejności instrukcji, nie wykonany test realnej płatności.

**[KOD]** `reject()` zmienia encję tylko dla PENDING (`PaymentAuthorizationService.java:136–142`), ale zawsze generuje callback `cancelled` (`:143`), także dla zakończonej autoryzacji. Tymczasem `S/web/ShopPaymentCallbackController.java:74–81` potrafi wyprowadzić FAILED z informacji przeglądarkowego callbacku. Poprawka samego rollback-only nie rozwiąże tego dodatkowego scenariusza.

**[PLAN]** Stan zatwierdzonego obciążenia jest nieodwracalny przez timeout/anulowanie autoryzacji. Termin może zakończyć jedynie stan nadal oczekujący. Metody encji oraz aktualizacje bazowe muszą wymuszać dozwolone przejścia; nie wystarczy zabezpieczyć jedną ścieżkę kontrolera. Ponowne approve/reject zwraca wynik odpowiadający istniejącemu stanowi lub kieruje do odczytu statusu. Zwrot pieniędzy jest odrębną, powiązaną operacją, nie zmianą historycznego sukcesu w FAILED.

**Wniosek A03:** proponowane rozdzielenie transakcji usuwa problem rollback-only, jeśli zachowa atomowy sukces i poprawną klasyfikację. Dla zamknięcia całego obszaru trzeba dodatkowo naprawić powyższe przejścia stanów i wyścigi approve/reject/expiry.

## 6. A13 — trwała próba, UNKNOWN i blokada nowego klucza

### 6.1. Dowody obecnego problemu

| Miejsce | Potwierdzone zachowanie | Co musi się zmienić |
|---|---|---|
| `S/order/OrderPaymentService.java:32–48` | RuntimeException po próbie integracji prowadzi do `markFailed` | Rozróżnić pewną odmowę od braku potwierdzenia; zapisać i zachować tożsamość próby przed HTTP |
| `S/web/ShopPaymentCallbackController.java:48–81` | Problem redeem/zapisu lub negatywny status callbacku może dać FAILED | Callback to sygnał do weryfikacji; błąd potwierdzenia nie cofa bankowego obciążenia |
| `S/order/OrderRepository.java:156–169` | Nowy klucz blokowany tylko przy PENDING | Zakaz zmiany próby także przy nieustalonym wyniku, niezależnie od metody płatności |
| `S/order/OrderRepository.java:181–201` | Jedno pole autoryzacji/operatora jest nadpisywane | Osobne nieusuwane rekordy prób i ich powiązania |
| `S/order/OrderRepository.java:250–267` | `markPaid` identyfikuje rekord samym ID zamówienia | Warunkowa aktualizacja konkretnej próby i referencji; poprawne rozliczenie spóźnionego wyniku |
| `B/payment/PaymentService.java:66–70,86–89` | Deduplikacja według klucza | Dodatkowa ochrona operacji biznesowej przed dwoma różnymi kluczami |
| `S/order/OrderRepository.java:316–331` | Po trzech błędach zawieszane uzgadnianie | Zachować tę informację osobno od finansowego wyniku; zawieszenie nie dowodzi odmowy |

### 6.2. Rozdzielić trzy różne informacje

1. **Wynik finansowy w banku/operatorze:** czy obciążenie zostało zatwierdzone i pod jaką referencją.
2. **Stan protokołu autoryzacji:** np. oczekiwanie, wydanie kodu, odebranie kodu. `REDEEMED` nie jest nowym obciążeniem; utrata odpowiedzi redeem nie usuwa wcześniejszego sukcesu (`B/payment/authorization/PaymentAuthorizationService.java:125–173`).
3. **Wiedza sklepu:** czy sklep potrafi już bezpiecznie przypisać wynik do zamówienia. `UNKNOWN` oznacza niewiedzę o wyniku, a nie „bank na pewno nie zapłacił”.

Nazwy i przyporządkowanie stanów banku oraz sklepu należy zaprojektować jawnie; nie dopisywać identycznego enumu w obu modułach bez rozróżnienia tych znaczeń.

### 6.3. Minimalny model `PaymentAttempt` — [PLAN]

- Trwałe ID próby, ID zamówienia i identyfikator sprzedawcy; jawny provider oraz rodzaj operacji, zamiast rozpoznawania po prefiksie klucza.
- Stały klucz idempotencji i niezmienny opis żądania: kwota, waluta, cel/odbiorca oraz identyfikator operacji biznesowej. Odcisk danych służy wykrywaniu niezgodnych ponowień; nie zastępuje kontroli dostępu.
- ID autoryzacji/operatora, referencja finansowa, wersja rekordu, znaczniki czasu, kategoria ostatniego błędu, termin kolejnego sprawdzenia i stan uzgadniania.
- Trwałe powiązanie korelacji/state z próbą przed wysłaniem pierwszego żądania. Surowe kody, cookie, dane kart i sekrety nie trafiają do logów ani dowolnego JSON payload; dane potrzebne protokołowi mają ograniczony dostęp i retencję.
- Przykładowe stany próby: `CREATED`, `PROCESSING`, `AWAITING_AUTHORIZATION`, `UNKNOWN`, `SUCCEEDED`, `DECLINED`, `CANCELLED`, `EXPIRED`. `reconciliation_suspended_at` pozostaje odrębne. To propozycja słownika; trzeba zdefiniować źródło i dozwolone przejścia każdego stanu.

**Przed wysłaniem HTTP** sklep zapisuje próbę i blokuje możliwość konkurencyjnego utworzenia drugiej. Transakcja sklepu kończy się przed wywołaniem sieciowym. Po odpowiedzi druga krótka transakcja warunkowo aktualizuje tę samą próbę. Restart pomiędzy etapami musi pozostawiać rekord możliwy do uzgodnienia, nie „wolne” zamówienie.

**Ograniczenia bazy:** unikalność klucza w ustalonym zakresie provider/sprzedawca, jedno aktywne lub nierozstrzygnięte podejście do opłacenia zamówienia oraz trwała ochrona zamówienia już opłaconego. Egzekwować to wspólną blokadą zamówienia i ograniczeniami/warunkowymi aktualizacjami DB; sam `if` w kontrolerze ani wyłączony przycisk nie chronią przed wyścigiem dwóch procesów. Sprawdzać liczbę zmienionych wierszy.

### 6.4. Obowiązkowe reguły ponawiania — [PLAN]

| Sytuacja | Oczekiwane zachowanie |
|---|---|
| Ten sam klucz, identyczne dane, próba trwa | Odczyt/wznowienie tej samej próby; bez nowego obciążenia i bez nowego state |
| Ten sam klucz, inne dane | Kontrolowany konflikt; brak zmiany starej próby |
| Inny klucz, poprzednia aktywna lub UNKNOWN | Odmowa utworzenia nowej próby; wskazanie istniejącej i jej stanu do uzgodnienia |
| Zmiana bank → karta/Stripe przy UNKNOWN | Ta sama blokada dla całego zamówienia; nie wolno obejść jej zmianą providera |
| Potwierdzony sukces / zamówienie PAID | Zwrot istniejącego wyniku; brak nowej płatności |
| Potwierdzona odmowa/anulowanie/wygaśnięcie bez możliwości późniejszego obciążenia | Można utworzyć nową próbę, zachowując poprzednią historię i kontrolę współbieżności |
| Timeout, 5xx, utrata połączenia/odpowiedzi albo 404 przy uzgadnianiu | Nie są automatycznie dowodem odmowy; wynik do uzgodnienia zgodnie z kontraktem providera |
| Trzy nieudane sprawdzenia | Zawieszenie automatycznych sprawdzeń + powiadomienie; nadal zakaz nowej płatności, dopóki wynik nie zostanie rozstrzygnięty |
| Callback przeglądarki twierdzi „cancelled” | Weryfikacja wyniku serwer–serwer; brak samodzielnej zmiany na pewną odmowę |
| Stary callback/webhook po późniejszym zdarzeniu | Odszukanie oryginalnej próby, kontrola podpisu/state/parametrów i monotoniczne rozstrzygnięcie; brak nadpisania innej referencji |

Nie usuwać UNKNOWN po arbitralnym czasie. Sam koniec ważności formularza lub przekierowania nie dowodzi, że wcześniej rozpoczęte księgowanie nie zakończyło się sukcesem. Potrzebny autorytatywny, końcowy stan oraz wykluczenie operacji nadal będącej w toku.

**Dodatkowa ochrona banku:** zdefiniować tożsamość zakupu, np. sprzedawca + identyfikator zamówienia + rodzaj operacji, i gwarancję najwyżej jednego sukcesu zakupu w bankowej bazie także dla różnych kluczy. Osobne próby po pewnej odmowie są dozwolone, ale nie drugi sukces. Zwrot musi pozostać odrębną operacją powiązaną z oryginałem; nie nakładać ślepo `UNIQUE(order_id)` na wszystkie płatności i refundy. Przed migracją sprawdzić historyczne kolizje i zakres identyfikatora sprzedawcy. Kolizja oznacza raport i przerwanie migracji, nie usunięcie danych.

**Operator zewnętrzny:** idempotencja ma określony kontrakt i okres retencji. Dla Stripe API v1 klucz może zostać usunięty po co najmniej 24 godzinach; po usunięciu jego ponowne użycie może utworzyć nowe żądanie. Dlatego stary UNKNOWN wymaga odczytu zachowanego ID i uzgodnienia, a nie bezterminowego ponawiania POST. Źródło: [Stripe — Idempotent requests](https://docs.stripe.com/api/idempotent_requests). Trzeba sprawdzić używaną wersję API i konkretny klient przy implementacji.

**Obsługa sprzeczności:** jeśli uzgadnianie odkryje dwa rzeczywiste sukcesy historyczne, zgłosić incydent finansowy, zachować oba dowody i wstrzymać automatyczne działania. Nie ukrywać jednego wpisu, nie zmieniać bankowego sukcesu w FAILED i nie wykonywać samodzielnego zwrotu bez oddzielnej procedury/uprawnienia. Ręczne „rozwiązanie” UNKNOWN wymaga dowodu od banku/operatora i audytu, nie przycisku wymuszającego nową próbę.

### 6.5. Kiedy A13 można uznać za rozwiązane

Po spełnieniu łącznie: trwałe próby przed HTTP, ograniczenia DB, niezmienne referencje i dane, poprawne UNKNOWN, blokada nowego klucza/metody, uzgadnianie po restarcie i spóźnionych zdarzeniach oraz ochrona przed drugim sukcesem biznesowym. Samo A03 nie chroni przed nowym kluczem, a samo A13 nie naprawia bankowego rollback-only.

A04/A06/A07 pozostają zależnościami: wybór poprawnego providera, świeży stan po blokadzie oraz ograniczony czas wywołań i bezpieczne retry. Nie ogłaszać gotowości całych płatności po poprawieniu jedynie dwóch etykiet ustaleń.

## 7. Macierz testów akceptacyjnych A03/A13

Wszystkie poniższe testy może przygotować i wykonać agent na izolowanym środowisku po zleceniu implementacji/testów. Wymagają prawdziwych transakcji PostgreSQL i częściowo dwóch procesów oraz sterowanego substytutu banku/providera; same mocki repozytorium nie wykryją rollback-only i wyścigów commit. W tej fazie **żaden nie został nowo wykonany**.

| ID | Scenariusz | Dowód zaliczenia |
|---|---|---|
| P01 | Brak środków, zablokowany rachunek, przekroczony limit w approve | Brak obciążenia; trwały właściwy wynik; brak przypadkowego `UnexpectedRollbackException` i fałszywego callbacku sukcesu |
| P02 | Sukces normalny oraz awaria po modyfikacji sald, przed zakończeniem autoryzacji | W sukcesie salda/księga/autoryzacja razem; w rollbacku żaden z tych efektów nie pozostaje częściowo |
| P03 | Bank zatwierdza, odpowiedź ginie; klient używa tego samego i nowego klucza | Najwyżej jeden sukces finansowy; nowy klucz zablokowany; zachowana oryginalna próba i referencja |
| P04 | Awaria po zapisaniu próby, przed HTTP oraz po odpowiedzi, przed zapisem sklepu | Po restarcie próba istnieje i jest uzgadniana; brak „osieroconego” obciążenia oraz drugiej próby |
| P05 | Równoległe starty płatności z dwoma kluczami i przez dwie metody | DB dopuszcza najwyżej jedną aktywną/nierozstrzygniętą próbę dla zamówienia |
| P06 | Retry tego samego klucza z inną kwotą/walutą/odbiorcą | Konflikt bez obciążenia i bez podmiany danych zapisanej próby |
| P07 | Równoległe approve/approve, approve/reject, approve/expiry | Jedno spójne końcowe rozstrzygnięcie; sukces nie jest potem zamieniany na odmowę |
| P08 | Ponowne approve po terminie dla COMPLETED i REDEEMED; reject już opłaconej autoryzacji | Sukces i referencja pozostają; callback/odczyt nie twierdzą, że pieniądze nie zostały pobrane |
| P09 | Redeem zatwierdzony, odpowiedź/zapis sklepu ginie; ponowienie kodu | Odrzucenie zużytego kodu nie daje nowej płatności ani pewnego FAILED; status pozwala odtworzyć sukces |
| P10 | Trzy 404/timeouts uzgadniania oraz próba nowego klucza po zawieszeniu | Zawieszenie i alert; wynik nadal niepewny; brak odblokowania drugiej płatności |
| P11 | Spóźniony/powtórzony callback lub webhook, zła kwota/state/podpis | Właściwa próba identyfikowana; brak regresji PAID i podmiany referencji; niezgodność odrzucona i zgłoszona |
| P12 | Awaria zapisu odmowy oraz niepewne potwierdzenie commit w banku | Brak fałszywej pewnej odmowy; wyszukanie po stałej tożsamości; bezpieczne nierozstrzygnięcie |
| P13 | PENDING Stripe z kluczem `card-...`; stary UNKNOWN po okresie retencji klucza | Dispatch po providerze; brak zapytania do obcego endpointu i brak ślepego nowego POST |
| P14 | Pewna terminalna odmowa starej próby, następnie nowa próba i stary callback | Nowa próba dozwolona dopiero po finalnym wykluczeniu starego obciążenia; historia nie znika; brak nadpisania wyniku |
| P15 | Migracja z istniejących zamówień, w tym niejednoznacznego FAILED i kolizji | Nie zakładać automatycznie „brak obciążenia”; niejednoznaczne rekordy do uzgodnienia; kolizje zatrzymują migrację z raportem |
| P16 | Istniejący sukces + nowe żądanie tego samego zakupu z innym kluczem bezpośrednio do API banku | Ochrona operacji biznesowej uniemożliwia drugi sukces; zwrot/refund nadal działa jako odrębna powiązana operacja |

Obserwować także brak pozostawionych blokad i wyczerpania puli po błędzie. Zliczać sukcesy w księdze oraz różnicę sald, nie tylko liczbę odpowiedzi HTTP 200. Dla zewnętrznego operatora po testach substytutu potrzebny test kontraktowy na jego środowisku testowym; poświadczenia i zgodę zapewnia użytkownik.

**Istniejący test wymagający zmiany oczekiwania:** `shop-app/src/test/java/org/demo/shopapp/order/OrderPaymentServiceTest.java:57–72` utrwala zasadę „Bank unavailable → FAILED”. Przy wprowadzeniu UNKNOWN trzeba rozdzielić test pewnej odmowy od testu nieznanego wyniku. Nie wolno po prostu usunąć testu błędu, żeby suite pozostał zielony. Regresję trzech 404 można rozszerzyć z `shop-app/src/test/java/org/demo/shopapp/order/PaymentReconciliationJobTest.java:87–117`.

## 8. Kolejność realizacji i punkty decyzji

### Etap 0 — uzgodnienia, bez działań na danych

- [ ] Właściciel potwierdza zakres: lokalny DEV/TEST czy przygotowanie konkretnego wdrożenia; CC lokalne czy sieciowe.
- [ ] Ustalić niezmienną tożsamość zakupu i sprzedawcy, listę providerów oraz znaczenie terminalnej odmowy. Zmiana metody nie może omijać nierozstrzygnięcia zamówienia.
- [ ] Uzgodnić politykę TOTP/UX, docelową topologię, wolumeny i progi K7. Brak decyzji nie blokuje przeglądu kodu, ale blokuje końcowe zatwierdzenie odpowiedniej kontroli.

### Etap 1 — odtworzenie i testy czerwone w izolacji

- [ ] Agent przygotowuje scenariusze P01–P16 oraz K2/K3/K4; zapisuje rzeczywiste wyniki obecnego kodu. Test, którego nie udało się wiarygodnie wywołać, pozostaje niezweryfikowany.
- [ ] Bezpieczna inwentaryzacja migracji/kontraktów i plan przeniesienia istniejących prób; brak ingerencji w bazy użytkownika.

### Etap 2 — naprawy przed refaktoryzacją kosmetyczną

- [ ] Zaimplementować model prób, ograniczenia DB i przejścia stanów; A03 z atomowym sukcesem oraz ochroną stanów końcowych, A13 z UNKNOWN i blokadą nowej próby.
- [ ] Powiązać callbacki/status/redeem/reconciler ze stałą próbą; uwzględnić A04/A06/A07. Osobno zamknąć A01/A02 przed akceptacją dostępu zewnętrznego.
- [ ] Dopiero na tej podstawie rozdzielać większe klasy bez zmiany ustalonych granic transakcji.

### Etap 3 — pełna kontrola techniczna

- [ ] Agent wykonuje macierz testów, K1–K7 w dostępnym izolowanym zakresie i regresję obu modułów/CC; potwierdza świeży checkout oraz migrację nowej bazy i kontrolowanej kopii danych testowych.
- [ ] Dołączyć testy do CI, maskowanie dowodów i kanoniczne diagramy aktualnego kodu. Nie zmieniać starych obrazków w miejsce dowodów testowych.
- [ ] Właściciel akceptuje scenariusze UI: powrót do istniejącej płatności, UNKNOWN, zawieszenie i komunikat braku możliwości rozpoczęcia nowej próby.

### Etap 4 — przygotowanie rzeczywistego wdrożenia

- [ ] Operator zapewnia odtwarzalną kopię, okno zmian i plan bezpiecznego cofnięcia/wstrzymania; agent może pomóc wykonać zatwierdzone polecenia z dostępem.
- [ ] Sprawdzić rzeczywiste proxy/firewall/TLS, uprawnienia hosta, realną dostępność usług i kontrakt providera TEST. Rotacja rzeczywistych kluczy to osobno zatwierdzona operacja.
- [ ] Przeprowadzić kontrolowane przejście wersji banku i sklepu. Podczas migracji, gdy stary kod nie rozumie blokady UNKNOWN, wstrzymać nowe płatności lub zapewnić udowodnioną kompatybilność. Nie pozostawiać mieszanego wdrożenia pozwalającego ominąć nowy model.
- [ ] Jeśli migracja odkryje sprzeczne sukcesy/nieodtwarzalne referencje, zatrzymać zmianę i sporządzić raport. Nie scalać i nie usuwać operacji automatycznie.

### Etap 5 — decyzja wydaniowa

- [ ] Wszystkie kryteria niezbędne dla docelowego zakresu mają dowód PASS, a nie deklarację. Wynik K5/K6 z lokalnych testów nie zastępuje kontroli prawdziwej ekspozycji.
- [ ] Brak otwartych problemów P1 i nierozstrzygniętych wyników migracji; A04/A06/A07 przetestowane dla używanych providerów.
- [ ] Właściciel/operator zatwierdza wydanie i monitoring. Samo wdrożenie A03/A13 nie jest certyfikacją gotowości całego banku do prawdziwych środków.

## 9. Co wykonano w ramach przygotowania tego planu

- Ponownie sprawdzono źródła opisujące siedem kontroli oraz transakcje, callback, ponawianie i aktualizacje statusów dla A03/A13.
- Ustalono dodatkowy przypadek regresji COMPLETED/REDEEMED po czasie w `approve` oraz niespójny callback `reject`; włączono je do zakresu i testów P07/P08.
- Rozdzielono techniczne testy możliwe do wykonania przez agenta od danych, decyzji i dostępu wymaganych od operatora.
- Utworzono ten plan i odesłanie w audycie. Nie wdrażano napraw, nie uruchamiano nowych testów, nie odczytywano wartości rzeczywistych sekretów i nie wykonywano operacji na rachunkach.

**Decyzja końcowa na dziś:** A03 i A13 są właściwymi naprawami przyczyn, pod warunkiem wdrożenia pełnych gwarancji z sekcji 5–7. Obecny stan nie pozwala jeszcze oznaczyć tych problemów jako rozwiązane. Najpierw model i testy awarii, następnie implementacja oraz dopiero pozytywna weryfikacja.
