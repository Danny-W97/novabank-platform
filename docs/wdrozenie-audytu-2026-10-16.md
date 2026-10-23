# Wdrożenie planu audytu — dziennik prac

## Niedokończone zakupy i bezpieczna rezygnacja — 21.10.2026

Zakres: wspólny mechanizm rezygnacji dla sklepu i CC, zachowanie produktów, blokada ponownej wysyłki starego formularza, kontrola porzuconych zakupów oraz widoki Bieżące / Do wyjaśnienia / Historia. Nie wykonywano anulowań, nowych migracji ani zmian konfiguracji na bazach operatora. Diagramy i notatki banku pozostają do aktualizacji końcowej.

- **V16 sklepu** dodaje intencję i potwierdzenie rezygnacji, status zamówienia CANCELLED oraz trwały rejestr żądań i zdarzeń. Nie usuwa zamówień, pozycji, prób ani zwrotów. Samo wdrożenie migracji nie anuluje historycznych płatności.
- `PurchaseCancellationService` używa tej samej blokady klienta i blokady zamówienia co początek próby płatniczej. Zapis intencji uniemożliwia późniejsze rozpoczęcie nowego dispatchu tego zamówienia, także z innej karty i po restarcie. Wywołania operatora są poza transakcją DB. Końcowy zapis ponownie sprawdza stan pod blokadą.
- Bez prób i z wiarygodną nową tożsamością zakupu można zakończyć rezygnację lokalnie. Dla bankowej autoryzacji nowe API cancel-by-key blokuje ten sam zakup/autoryzację co approve, sprawdza właściciela sprzedawcy i wykorzystuje istniejący terminalny FAILED autoryzacji. Uwierzytelnienie wymaga klucza API i scope `payments:authorize`; endpoint serwerowy nie używa browser-CSRF. Wygrana autoryzacji oznacza PAID, nie automatyczny zwrot.
- Dla wewnętrznej karty wysłanego synchronicznego obciążenia nie odwołuje się lokalnie: konieczny jest potwierdzony wynik banku. Stripe: wygaszenie wyłącznie otwartej Checkout Session, ponowny odczyt z PaymentIntent, kontrola powiązań/kwot/waluty. Brak pola PaymentIntent nie jest równoważny jawnej wartości null; przetwarzanie lub możliwe obciążenie pozostaje do wyjaśnienia. Timeout/404/niezgodność nigdy nie dowodzą anulowania.
- Sukces rezygnacji zachowuje pozycje bieżącego koszyka, archiwizuje snapshot starej tożsamości i nadaje nową. Nie modyfikuje niezależnego nowszego koszyka. Niepotwierdzona rezygnacja zapisuje REVIEW bez wymuszania statusu finansowego. Ponowienie jest oparte o UUID i parametry, zdarzenia audytu są zachowane. Późniejszy sukces sprzeczny z potwierdzonym CANCELLED jest konfliktem wymagającym wyjaśnienia, nie cichą zmianą wyniku.
- Klient ma „Kontynuuj płatność” oraz „Zrezygnuj i wróć do koszyka”; analogiczna rezygnacja jest w Moich zamówieniach. Zwykłe anulowanie nie opróżnia koszyka. Boczny koszyk sklepu pokazuje informację o niedokończonym zakupie i odnośnik do jego obsługi; dodawanie lub zmiana ilości prowadzi do tego panelu zamiast próbować zmieniać zamrożone pozycje. Po potwierdzeniu rezygnacji formularz ponownie odblokowuje właściwe przyciski, a karta pobiera nową wycenę. Dotychczasowy niezależny nowy zakup pozostaje jawnym wyjątkiem ryzyka, nie standardową ścieżką. Uprawnienia konta i CSRF są sprawdzane po stronie serwera.
- Automatyczna kontrola: domyślnie 30 minut od utworzenia zakupu/najnowszej próby, co minutę, do 25 kandydatów. `SHOP_ABANDONED_PURCHASE_MINUTES` (minimum 10) i `SHOP_ABANDONED_PURCHASE_CHECK_MS` konfigurują wartości. Wyłączenie `shop.orders.reconciliation-enabled` wyłącza też tę kontrolę. Przed intencją ponownie sprawdzany jest wiek pod blokadą. Istniejące nierozstrzygnięte żądania są sprawdzane nie częściej niż co 5 minut. Historyczne zakupy bez nowej tożsamości są wyłączone z automatycznego anulowania. Upływ czasu uruchamia kontrolę, nie dowodzi braku obciążenia.
- CC ma trzy widoki i skrót „Zakupy tego klienta” w szczegółach konta sklepu. Historia to zakończone zakupy starsze niż 7 dni; UNKNOWN, konflikty i zawieszenia pozostają do wyjaśnienia niezależnie od wieku. To filtry, nie fizyczne przenoszenie ani usuwanie danych. Odczyty wymagają V16. `purchase-cancel` / `purchase-refresh` używają aktualnego JAR sklepu i tego samego serwisu, bez Boot/Flyway/HTTP/schedulerów uruchamianych przez narzędzie. Tylko DEV, sprawdzone lokalne kontenery/porty tego checkoutu, zamknięte parametry i słowo CANCEL / REFRESH. Stripe narzędzia CC ograniczono do kluczy TEST i oficjalnego API. Niepotwierdzona rezygnacja kończy zadanie kodem 2 i wyraźnym komunikatem; nie jest zielonym potwierdzeniem anulowania. Audyt LOCAL_CC_DEV oznacza lokalny token panelu, nie wieloosobowy model uprawnień.

### Wdrożenie i weryfikacja dodatku

Przed uruchomieniem: zachować świeże kopie, zakończyć zadania i zatrzymać bank/sklep/listenera (także z IDE), zrestartować CC, poprawnie zbudować obie aplikacje i uruchomić aktualny bank oraz sklep. Sprawdzić faktyczne zastosowanie V16 sklepu, odświeżyć kartę CC i sklep. Sama podmiana frontu nie wdraża nowego API ani schematu. Nie używać JAR po nieudanym buildzie; testy agenta są na izolowanym eksporcie źródeł, bez przepakowywania działających artefaktów.

Weryfikacja: pełna regresja na eksporcie publicznych źródeł — jednostkowe banku 169 PASS, sklepu 44 PASS; integracyjne banku 78 testów (77 PASS, 1 skip — osobno włączany test obciążenia), sklepu 45 PASS. Po końcowym zabezpieczeniu przed sprzecznym dowodem sprawy i poprawce potwierdzenia REFRESH powtórzono jednostkowe 169/44 PASS oraz wybraną integrację banku 61 PASS i sklepu 41 PASS, w tym 11 testów rezygnacji. Test zapakowanego narzędzia wykonuje teraz zarówno REFRESH (bez zapisu intencji rezygnacji), jak i CANCEL. Logi: `target/purchase-cancellation-final-full.log`, `target/purchase-cancellation-final-smoke.log`, `target/payment-cases-verify/{full,smoke}/`.

CC: Python 273 testy (254 PASS, 19 skip — opt-in środowiskowe), osobno rzeczywisty PostgreSQL 3 PASS z V1–V16, JavaScript 15 zestawów PASS. Końcowe poprawki checkoutu dodatkowo potwierdzono testem zachowania produktów i ponownego odblokowania obu metod płatności. Logi: `target/purchase-cancellation-python.log`, `target/purchase-cancellation-sql.log`, `target/purchase-cancellation-js.log`. Testy obejmują m.in. równoległy dispatch/rezygnację, równoległe approve/cancel banku, obcą tożsamość/klucz/CSRF, replay UUID, spóźniony wynik, brak dowodu/404, wygasłą sesję Stripe z brakującym/przetwarzanym PaymentIntent, starszy zakup i nowszy niezależny koszyk, wiek zakupów oraz audyt i filtry CC.

Skan sekretów Gitleaks: historia i kandydat publicznych źródeł po 0 znalezisk, exit=0 (`target/purchase-cancellation-secrets.log`). Nie czytano wartości sekretów operatora. Kontrola `git diff --check` zmienianych śledzonych plików nie wykazała błędów białych znaków.

Testy używają własnych kontenerów i danych syntetycznych; wywołania Stripe sprawdzane są na lokalnym symulatorze, nie na rzeczywistym koncie operatora. Zasady wygaszania otwartej sesji sprawdzono w oficjalnym [API Stripe](https://docs.stripe.com/api/checkout/sessions/expire). Nie wykonano wizualnej akceptacji w przeglądarce, anulowania w rzeczywistym Stripe TEST ani wdrożenia do DEV/PROD. Regresje nie zastępują tych kontroli. Dokumenty banku, Mermaid i PNG pozostają bez zmian.

## Sprawy płatności i niezależny nowy zakup — 20.10.2026

Zakres: panel CC **Uzgadnianie płatności**, oddzielny wynik finansowy i workflow, audytowane decyzje operatora oraz trwała tożsamość zakupu. Nie uruchamiano V15 ani nowych decyzji/uzgodnień na bazach użytkownika. Dokumenty banku, Mermaid i PNG pozostają na koniec. K1: użytkownik potwierdził pomyślne logowanie w przygotowanym środowisku odtworzenia; jest to potwierdzenie operatora, nie dodatkowy test wykonany przez agenta.

### Zasady i zakres wdrożenia

- Addytywna V15 dodaje sprawy, zdarzenia audytu, plany finansowe, UUID zakupu i archiwalne snapshoty koszyków. Nie usuwa zamówień, pozycji, prób ani zwrotów. Istniejące koszyki otrzymują znacznik wymagający jawnego rozpoczęcia nowego zakupu; ich produkty nie są usuwane przez samą migrację.
- `workflow_status` (OPEN/ESCALATED/CLOSED_UNRESOLVED/RESOLVED) jest oddzielony od `financial_outcome`. Decyzja CLOSE_UNRESOLVED nie ustawia FAILED/DECLINED i nie odblokowuje kasy. ALLOW_NEW_PURCHASE / REVOKE_NEW_PURCHASE nie zmieniają wyniku finansowego, sald ani koszyka. Wymagane wersja sprawy, UUID żądania, uzasadnienie i wpisanie DECIDE. Aktor `local-cc-dev` oznacza lokalny token CC, nie zweryfikowaną tożsamość osoby; to nie produkcyjny model RBAC/four-eyes.
- Wynik zadania sprawy rozróżnia `financialApplied` od administracyjnej decyzji. `financialApplied:false` przy zgodzie na zakup oznacza brak zapisu finansowego, nie brak zapisu audytu i zgody; szczegóły potwierdzają nową wersję sprawy i jej zdarzenie.
- CHECK zapisuje zebrane dowody i ograniczony czasowo plan. Sukcesy banku/kart i Stripe TEST są potwierdzane jak w importerze. Dawne statusy FAILED/DECLINED są pokazywane jako dane pomocnicze, nie samodzielny dowód. Automatyczne utrwalenie odmowy wymaga trwałego bankowego rejestru DECLINED ze zgodnymi parametrami i bez innej aktywnej/udanej próby i księgowego sukcesu. Samo 404, brak wpisu, wygaśnięcie Stripe lub stary lokalny FAILED pozostają UNKNOWN.
- Zwroty mają osobny widok i ocenę. Pierwszy automat przyjmuje tylko pełny pojedynczy zwrot bankowy, zgodny po stronach rachunków, kwocie, walucie i referencji z zakończonym zwrotem sklepu. Zachowuje pierwotny SUCCEEDED/PAID i istniejący zwrot, a wynik sprawy oznacza REFUNDED. Nie wysyła nowego zwrotu. Częściowe, niezgodne i niepotwierdzone zwroty (w tym nierozliczone historie Stripe) trafiają do REFUND_REVIEW. Wielokrotne oryginalne księgowania trafiają do CONFLICT; nie są automatycznie kompensowane.
- Finansowy APPLY wymaga konkretnego planu, RECONCILE, sprawdzonych kopii obu baz oraz zatrzymania zapisów. Plany są związane z kopią i wersją sprawy. READY wygasa po godzinie; APPLYING można wznowić tym samym UUID i uzasadnieniem po awarii. W trakcie APPLYING nowa kontrola/decyzja tej sprawy jest blokowana. Zapis finansowy i końcowy audyt nie są jedną transakcją obu baz: dziennik przechowuje stan wznowienia, a ponowienie nie księguje nowego obciążenia. Przypisane aktywne próby pozostają pod kontrolą zwykłego reconciler'a, nie historycznego przepisywania.
- CHECK/APPLY korzystają z sum sprawdzonej pary backupów. Sama decyzja administracyjna nie deklaruje wykonania kopii ani nie zapisuje danych finansowych; nadal sprawdza lokalne kontenery/porty tego checkoutu i mapowania sprzedawcy. Polecenia nie przyjmują dowolnego SQL, adresu operatora ani dowolnego wyniku finansowego.
- Nowy zakup: operator musi dopuścić go dla **każdej** nierozstrzygniętej płatności blokującej danego klienta. Klient następnie w checkout potwierdza „Odłóż koszyk i rozpocznij nowy zakup”. Powstaje snapshot starego koszyka i nowy pusty koszyk z nowym UUID; stare zamówienie i płatność pozostają. Zgoda nie obejmuje przyszłych nierozstrzygniętych zakupów. Nowy UUID bez tego przejścia nie jest obejściem blokady. Zgody i przejścia koszyka są serializowane blokadą klienta; cofnięcie zgody nie usuwa już utworzonych zamówień.
- Ten sam checkout zwraca to samo zamówienie także równolegle. Zawartość po związaniu z zamówieniem jest zamrożona. Sukces czyści i zamyka wyłącznie pasujący bieżący koszyk; spóźniony sukces starego zakupu nie usuwa produktów z niezależnego nowego koszyka. Snapshot pozostaje w bazie; nie dodano jeszcze klientowskiego ekranu odtwarzania snapshotu.
- Widok i operacje CC są wyłącznie DEV, z tokenem i dotychczasową ochroną Host/Origin. Odczyty są ograniczone, READ ONLY, z timeoutami; bez PAN/CVV/sekretów i pełnego prywatnego manifestu. Interfejs renderuje tekst, nie HTML z danych. Po zmianie profilu/opuszczeniu widoku usuwa dane i ignoruje stare odpowiedzi.

### Uruchomienie po wdrożeniu

1. Zakończyć aktywne zadania, zachować świeży backup i klucze, zatrzymać bank/sklep/listenera, również instancje z IDE.
2. Dopiero po zatrzymaniu aplikacji i zakończeniu zadań zrestartować CC i odświeżyć kartę. Zbudować oba aktualne JAR-y. W bieżącej pracy przebudowa banku w istniejącym `target` napotkała blokadę pliku `.jar.original` działającej aplikacji; nie zatrzymywano procesu operatora. Regresja korzysta z eksportu publicznych źródeł do osobnego katalogu tymczasowego (`scripts/ci/payment_cases_verify.py`), a nie z przebudowy działającego JAR. Przed uruchomieniem DEV wymagany jest prawidłowy, zakończony build po zatrzymaniu aplikacji; nie używać artefaktu po przerwanym przepakowaniu.
3. Uruchomić aktualne aplikacje, sprawdzić faktyczną wersję V15 sklepu. Przed finansowym APPLY ponownie zatrzymać bank/sklep/listenera oraz inne zapisy i przygotować aktualną, sprawdzoną parę kopii. Sam restart CC nie zatrzymuje procesów uruchomionych z IDE ani nie zastępuje wdrożenia aktualnego JAR.
4. W CC → Uzgadnianie płatności wyszukać ID klienta, przejrzeć wszystkie nierozstrzygnięte sprawy. CHECK lub jawna decyzja wymaga potwierdzenia; finansowy APPLY dodatkowo kopii, zatrzymania zapisów i RECONCILE. UNKNOWN nie jest przyciskiem „uznaj za nieopłacone”.
5. Jeśli operator zaakceptuje ryzyko nowych niezależnych zakupów, udzielić osobnych zgód dla wszystkich blokujących spraw klienta. Klient potwierdza w checkout odłożenie koszyka, po czym dodaje produkty do nowego pustego zakupu. Nie ponawia starej płatności nowym kluczem.

### Weryfikacja

Końcowa pełna regresja na izolowanym eksporcie źródeł: jednostkowe banku 168 PASS, sklepu 40 PASS; integracyjne banku 75 testów (74 PASS, 1 skip — osobno włączany profil obciążenia), sklepu 35 PASS. W banku 9 testów spraw płatności obejmuje m.in. trwałą odmowę sprzeczną z aktywną autoryzacją, pełny zwrot, konflikt, błąd końcowego audytu po zapisie finansowym i wznowienie pierwotnego żądania. W sklepie 7 nowych testów tożsamości zakupu obejmuje równoległy checkout, jawny nowy zakup, uprawnienia i spóźnione sprzątanie. Logi: `target/payment-cases-final-full.log` oraz `target/payment-cases-verify/full/`.

CC: Python 265 testów (248 PASS, 17 skip), 14 zestawów JavaScript PASS; 3 testy helperów CI PASS. Dwa nowe opt-in testy rzeczywistego SQL uruchomiono dodatkowo: 2 PASS na własnym, nieudostępnionym kontenerze PostgreSQL z migracjami V1–V15; potwierdzono stronicowanie, filtry, szczegóły i brak prywatnego manifestu w odczycie. Testy te pozostają pomijane w domyślnym zestawie, a osobny krok CI włącza je jawnie. Gitleaks: historia i kandydat źródeł po 0 znalezisk, exit=0. Logi: `target/payment-cases-cc-python.log`, `target/payment-cases-cc-js.log`, `target/payment-cases-ci-helpers.log`, `target/payment-cases-sql.log`, `target/payment-cases-secrets.log`. Nie wykonano zdalnego CI, wizualnej weryfikacji w przeglądarce ani decyzji na danych DEV. Testy używają danych syntetycznych i nie zastępują akceptacji operatora.

Po ostatnim doprecyzowaniu pola JSON `financialApplied` powtórzono jednostkowe 168/40 PASS oraz wybraną integrację 19/30 PASS, w tym rzeczywiste wywołanie zapakowanego CLI i jego wynik JSON (`target/payment-cases-final-smoke.log`, `target/payment-cases-verify/smoke/`). Dodatkowa regresja UI potwierdza siedem kolumn listy wraz z widoczną zgodą na nowy zakup; oba nowe zestawy JS uruchomiono ponownie z wynikiem PASS.

## Migracja historii i testy operatora — 20.10.2026

Zmiana decyzji użytkownika: **nie usuwamy starych zamówień ani płatności**. Implementowany jest jawny import wyłącznie jednoznacznie potwierdzonych sukcesów; nie ma automatycznej konwersji historycznego FAILED na brak obciążenia. Nie uruchamiano importu ani nowych migracji Flyway na bazach użytkownika. Dokumenty banku, Mermaid i PNG pozostają na koniec.

- [ ] **TODO K5/K6 — docelowa sieć, proxy/firewall i granica zaufania CC: do wykonania później**, zgodnie z dyspozycją użytkownika. Nie oznaczać lokalnego testu Host/Origin/tokena jako pełnego audytu sieci.
- [x] Weryfikacja końcowa bieżącej implementacji — lokalne regresje poniżej. Nie oznacza wykonania rzeczywistej migracji ani zamknięcia akceptacji operacyjnej.

### Dodane operacje CC i granice

- Bezpieczeństwo: **Migracja historycznych płatności — plan / wykonaj**. WSL, wyłącznie lokalny DEV; weryfikacja tożsamości obu kontenerów Compose tego checkoutu i zgodności portów JDBC. Wymagane aktualny JAR banku oraz schema sklepu V14. Sumy SHA-256 najnowszej kompletnej pary kopii są sprawdzane, a ich hashe wiążą plan. Odtworzenie kopii i zatrzymanie innych zapisów nadal wymagają potwierdzenia operatora, nie są dowodzone samym hashem.
- Plan pokazuje techniczne ID i wyniki, nie konta, hasła ani dane klienta. Import wymaga tego samego `planDigest`, słowa `MIGRATE` i dwóch potwierdzeń. Ścisłe źródła: pojedyncza oryginalna księgowa płatność i zgodna zakończona autoryzacja/karta, w tym zgodność rachunku płatnika; albo opłacona, zakończona sesja Stripe TEST z odpowiadającym zamówieniem, kwotą i walutą. Stripe czytany jest tylko przez HTTPS GET oficjalnego API, nigdy nowym żądaniem obciążenia. Import sesji Stripe ma konserwatywne ograniczenie do PLN; brak pełnego dowodu dla przeliczeń, odmów, starych utraconych referencji i konfliktów pozostawia blokadę. Historie z wykrytym zwrotem pozostają poza importem PAID-only, również przy starym bankowym zwrocie bez `refund_original_id`. Limit jednego przeglądu wynosi 1000 nieprzypisanych zamówień; większy zakres wymaga osobnego opracowania, nie automatycznego skracania listy.
- Import nie jest transakcją rozproszoną. Dla bankowych sukcesów najpierw zatwierdza rejestr tożsamości banku, następnie próbę i zamówienie sklepu. Po awarii pomiędzy bazami bank zachowuje ochronę przed nowym obciążeniem, a stary nieprzypisany rekord sklepu pozostaje zablokowany. Dziennik `historical_payment_migration_runs` przechowuje zatwierdzony manifest i checkpoint; ponowienie tego samego planu wznawia/odczytuje wynik. Dane manifestu zostają w bazie sklepu i nie są publicznym logiem CC.
- V14 dodaje `historical_imported_at` i dziennik. Import nie usuwa pozycji, zwrotów ani zamówień, nie zmienia sald i kwot księgi. Znacznik importu wyklucza retrospektywne czyszczenie obecnego koszyka przez reconciler lub callback. Brak dowodu sukcesu pozostaje nierozstrzygnięciem — ten etap nie wdraża ręcznego wymuszania odmowy.
- Testy: osobne kafelki **2FA: kopia, rotacja i logowanie HTTP**, **Bezpieczeństwo: sesje, 2FA i dostęp HTTP**, **Awarie płatności: bank / sklep**, **Obciążenie: równoległe przelewy HTTP**. Każdy korzysta z danych syntetycznych i zamkniętej selekcji Maven, bez parametrów URL/SQL/kluczy z przeglądarki. Procesy nie dostają sekretów operatora TEST/DEV/Stripe.
- K1: test tworzy własny pg_dump, odtwarza do drugiego PostgreSQL, rotuje ten sam sekret, uruchamia osobną JVM z keyringiem i przechodzi hasło → 2FA → rzeczywisty dashboard HTTP 200. Nie jest to ponowny test rzeczywistej kopii operatora; uzupełnia istniejący dwufazowy test odszyfrowania.
- K3/K4 i awarie: rozszerzono test dwóch JVM o blokadę użytkownika po haśle oraz utratę odpowiedzi HTTP po faktycznym commit przelewu i retry przez drugi proces. Istniejące testy obejmują role/CSRF, wspólne wyzwanie, równoległe zużycie kodu i drafty. Nie deklaruje się pełnego pokrycia wszystkich kombinacji P01–P16 ani wszystkich punktów fizycznej awarii DB/HTTP bank–sklep.
- K7: stały opt-in profil 8 klientów, 80 przelewów HTTP z retry oraz jeden rzeczywisty przelew schedulerowy, na własnych PostgreSQL 18/Redis 7.4. Raport przebiegu i podsumowania: log zadania oraz `target/cc-transfer-load-report.json`. Pomiary: czas operacji (confirm + execute + retry), p95/p99, błędy, timeouty HTTP, deadlocki i oczekiwanie na blokady PostgreSQL, pula Hikari, próbki heap/RSS i czas CPU JVM, opóźnienie wykonania zlecenia. Kontrola sald każdego rachunku, liczby księgowań, replay i zwolnienia blokad NOWAIT.
- K7 — ograniczenia: generator i bank działają w tej samej JVM; CPU/RAM nie mierzą całego hosta, PostgreSQL i Redis. Konta syntetyczne nie mają PIN/2FA i korzystają z zapisanych odbiorców. To mały test poprawności pod współbieżnością, nie regulowane SLO/stress test; progi p95/p99 i docelowy profil obciążenia nadal wymagają uzgodnienia. Poprawność finansowa nie oznacza akceptacji wydajności produkcyjnej.

### Wyniki i uruchomienie przez operatora

| Kontrola lokalna | Wynik / artefakt |
|---|---|
| Pełna integracja banku, z włączonym obciążeniem | 63 PASS, 0 failures/errors/skips; `target/audit-bank-final.log`. Obejmuje 40 przypadków `AuditPaymentConcurrencyIT`, 8 rzeczywistych HTTP `SharedSessionHttpIT` i 7 ówczesnych testów importera. Dodatkowe końcowe regresje importera opisano poniżej. |
| Końcowy importer po dodatkowych zabezpieczeniach | 10 PASS; `target/audit-migration-final.log`. BANK/CARD/Stripe, brak dowodu i sprzeczne wyniki, zwroty, zgodność płatnika i kwot, wznowienie po commit banku/awarii sklepu, częściowa partia i zmiana już importowanego rekordu. Osobny test wykonuje plan i zapis przez zapakowany JAR/PropertiesLauncher, bez startu Spring/Flyway. To końcowy podzbiór regresji, nie 10 dodatkowych niezależnych pozycji ponad poprzednie 63. |
| Jednostkowe/MVC banku | 168 PASS; `target/audit-bank-unit-final.log`. |
| Pełna integracja sklepu | 28 PASS; `shop-app/target/audit-shop-final.log`. W tym importowany sukces nie wchodzi do kolejki sprzątania koszyka, a zwykły nowy sukces nadal jest sprzątany jednokrotnie. |
| Jednostkowe sklepu | 39 PASS; `shop-app/target/audit-shop-unit-final.log`. |
| Python CC | 258 testów: 243 PASS, 15 skip; `target/audit-cc-regression.log`. Pominięte zestawy wymagają osobnego jawnego środowiska live; nie zaliczono ich automatycznie. |
| JavaScript CC i helpery CI | 12 zestawów JS PASS; `target/audit-cc-js.log`. 3 testy helperów PASS; `target/audit-ci-helpers-final.log`. |
| Gitleaks, historia i kandydat źródeł | 0 znalezisk, exit=0 dla obu; `target/audit-secrets-final.log`. Bez montowania plików env i danych runtime; wynik nie zastępuje testu wszystkich uprawnień ani konfiguracji docelowej. |
| Mały profil obciążenia | 80 operacji i 81 księgowań łącznie ze schedulerem; błędy/timeouty/deadlocki 0. Salda, unikalność i zwolnienie blokad potwierdzone. p95 całej operacji 507 ms, p99 577 ms, opóźnienie schedulera 202 ms. Wynik `INTEGRITY_PASS_PERFORMANCE_UNASSESSED`; metryki danego lokalnego przebiegu, nie deklarowane SLO. |

Po zakończeniu aktywnych zadań należy zrestartować CC i odświeżyć kartę. Nowe kafelki testowe są w **Operacje → Testy**, a import w **Operacje → Bezpieczeństwo**. Testy budują własne zasoby; nie wymagają uruchamiania baz DEV. Przed rzeczywistym importem trzeba wdrożyć aktualny sklep z V14 i aktualny JAR banku, zatrzymać bank/sklep/listenera i inne zapisy, wykonać świeżą parę kopii i sprawdzić odtworzenie obu, dopiero potem wyświetlić plan i zatwierdzić właściwy hash. Nie usuwać starych kopii ani zmieniać klucza płatności w celu obejścia UNKNOWN. Oryginalny hash i zakres kopii muszą pozostać zgodne przy wznowieniu; zmiana planu/konfiguracji/kopii wymaga ponownego przeglądu, nie wymuszenia importu.

Nadal otwarte: rzeczywista migracja danych operatora i jego akceptacja planu; logowanie na rzeczywistym odtworzonym koncie; docelowa macierz K3/K4 wszystkich ról/endpointów; fizyczne awarie procesów i połączeń w każdym wariancie P01–P16 (w tym wspólny transport bank–sklep po commit); K5/K6 TODO; docelowe progi i profil K7; zatwierdzony commit i zdalne CI. Nie uznano ich za zaliczone samym wykonaniem powyższych lokalnych testów. Finalna aktualizacja dokumentów banku pozostaje na koniec.

## Poprawka odczytu SHA-256 w teście odzyskania 2FA — 19.10.2026

Po rzeczywistym uruchomieniu etapu 1 przez operatora potwierdzono błąd parsera: `split()` rozdzielał pełną ścieżkę zapisywaną przez `backup.sh` na kolejne elementy, w tym na spacji w `Dysk D`. Komunikat „Brak poprawnej sumy SHA-256 kopii banku” pojawiał się przed obliczeniem i porównaniem sumy dumpu, więc sam w sobie nie dowodził uszkodzenia kopii.

W `scripts/control-center/web/totp_recovery.py` parser rozpoznaje teraz jeden rekord formatu sha256sum/shasum (64 znaki hex, separator, znacznik tekstowy/binarny, pełna nazwa ze spacjami). Nie otwiera ścieżki z metadanych. Nadal oblicza SHA-256 wybranej kopii i porównuje ją z metadanymi oraz sumą zapamiętanego planu. Odrzuca nieprawidłowy format, wiele rekordów i niezgodną sumę; nie przelicza ani nie nadpisuje pliku `.sha256` użytkownika.

Weryfikacja: nowe regresje odtworzyły błąd przed poprawką; po poprawce 13 testów modułu PASS. Pełna regresja Python CC: 254 testy, 239 PASS, 15 skip. Dodatkowo 3 rzeczywiste testy Docker/JAR PASS na nowo wygenerowanych sztucznych kopiach w katalogu ze spacjami; metadane tworzy rzeczywiste `sha256sum` z pełną ścieżką, tak jak skrypt backupu. Sprawdzono poprawne odszyfrowanie legacy/keyring, odrzucenie błędnego klucza oraz uszkodzonego archiwum i sprzątanie kontenerów. Nie uruchamiano testu na istniejącej kopii operatora, nie używano jego kluczy ani nie restartowano CC. Dokumenty banku, Mermaid i PNG pozostają bez zmian.

## Control Center — automatyczny test odzyskania sekretów 2FA — 19.10.2026

Dodano osobny kafelek **DEV → Operacje → Backup DEV → Test odzyskania 2FA — 1/2: wykrycie kluczy**. Nie zmieniono starego testu odtworzenia obu baz. Etap 1 wymaga zwykłego potwierdzenia operacji, wybiera najnowszą kompletną parę kopii w katalogu skonfigurowanym w Ustawieniach operacji, sprawdza SHA-256 bankowego dumpu i odtwarza wyłącznie bank w izolowanym PostgreSQL. Z danych kopii odczytuje identyfikatory kluczy: format bez prefiksu → `legacy`, format `v1.<id>...` → zapisany identyfikator. Nie próbuje odgadnąć kluczy ani wczytywać ich z dev.env do testu.

Po etapie 1 formularz automatycznie pokazuje odpowiednie pola hasłowe. Dla `legacy` należy podać zachowany `TOTP_ENCRYPTION_KEY`; dla nazwanych ID — odpowiadające im wartości z historycznego keyringu. `ACTIVE_ID` nie jest wymagany do samego odszyfrowania: identyfikator jest w szyfrogramie. Formularz wymaga potwierdzenia zakresu. Etap **2/2: odszyfrowanie** jest dostępny wyłącznie z ważnym, serwerowym planem. Plan wygasa po 20 minutach, jest związany z SHA-256 i nie przeżywa restartu panelu. Po anulowaniu formularza można wrócić przez „Podaj klucze do tej kopii” w dzienniku etapu 1.

Każdy etap odtwarza własny kontener i sprząta go przed zakończeniem; żaden nie czeka na podanie kluczy. Etap 2 ponownie sprawdza dokładnie tę samą kopię oraz liczbę rekordów, odszyfrowuje całość partiami po 100 i raportuje liczbę zweryfikowanych sekretów. Zero rekordów, błędny klucz, uszkodzona kopia, zmiana SHA, timeout lub niepotwierdzone sprzątanie nie są wynikiem PASS. Zielony wynik etapu 1 oznacza tylko ukończenie wykrycia, nie potwierdzenie odzyskania sekretów.

### Izolacja, sekrety i granice testu

- Wyłącznie DEV, WSL/Linux i lokalny Docker socket; bez dowolnej ścieżki, SQL, adresu DB lub obrazu podawanego przez przeglądarkę. Korzysta z katalogu backupów przypisanego do projektu; źródłowa kopia i env pozostają bez zmian.
- Wymagany lokalny obraz `postgres:18`, JDK 25 i aktualny JAR banku zawierający `TotpRecoveryVerificationCommand`. Nie pobiera automatycznie obrazów ani nie buduje JAR w tle. Nie trzeba uruchamiać banku, sklepu ani Redis. Limity: dump 512 MiB, do 100000 rekordów i 32 identyfikatorów, do 15 minut na etap.
- Kontener bez opublikowanych portów, sieci, montowania hosta, wolumenów DEV i logów Docker; użytkownik postgres bez capabilities, no-new-privileges, limity RAM/CPU/PID oraz tmpfs na dane. Usuwany jest tylko zasób o losowej nazwie i zgodnej etykiecie tego zadania. Watchdog 16 minut i `--rm` ograniczają pozostawienie zasobu po nagłym zniknięciu procesu panelu; nie zastępują kontroli Docker po awarii hosta.
- Klucze wyłącznie w prywatnych parametrach zadania i stdin ograniczonego weryfikatora Java, bez argumentów procesu, plików env, publicznych Job/result, logów i zapisanej historii CC. Weryfikator nie uruchamia Spring, Flyway, HTTP ani schedulerów i nie wypisuje odszyfrowanej wartości. Formularz czyści pola po zamknięciu, wysłaniu lub ukryciu karty. To ograniczenie przechowywania przez aplikację, nie gwarancja fizycznego wyzerowania RAM ani ochrony przed administratorem hosta.
- Anulowanie jest kontrolowane między etapami/partiami; rozpoczęte polecenie może czekać do swojego timeoutu, następnie wykonywane jest sprzątanie. Po zmianie backendu należy bez aktywnych zadań zrestartować CC i odświeżyć kartę.
- **Nie jest to test logowania hasłem i kodem TOTP ani pełne zaliczenie K1/DR.** Wynik zawiera `loginTested=false`. Nie wykonuje rotacji, migracji istniejącego DEV/PROD ani operacji płatności. Backup jest odtwarzany jako SQL w izolowanym kontenerze: operator nadal musi wskazywać zaufane kopie, nie archiwa z nieznanego źródła.

Źródła: `scripts/control-center/web/totp_recovery.py`, `totp-recovery-ui.js`, integracja `server.py`/`app.js`, kontrakt `openapi.py`; `src/main/java/org/demo/bankdemo/maintenance/TotpRecoveryVerificationCommand.java`. Regresje: `test_totp_recovery.py`, `test_totp_recovery_ui.js`, `test_totp_recovery_live.py` oraz Java `TotpRecoveryVerificationCommandTest`. Live fixture generuje wyłącznie sztuczne klucze i własny pg_dump w odrębnym kontenerze; nie czyta backupów użytkownika. Włączono ten zestaw w bankowym jobie integracyjnym CI; nie jest to deklaracja wykonanego zdalnego CI.

### Weryfikacja dodatku — 19.10.2026

| Kontrola | Wynik |
|---|---|
| Java banku, jednostkowe/MVC | 168 PASS, bez failures/errors/skips; w tym 3 nowe testy weryfikatora. `target/totp-recovery-unit.log`. |
| Aktualny zapakowany JAR | Maven package PASS; zawiera weryfikator stdin. `target/totp-recovery-package.log`. |
| Control Center Python, pełna regresja izolowana | 251 testów: 236 PASS, 15 skip. Z pominiętych 3 nowe live Docker uruchomiono osobno poniżej; pozostałe 12 live SQL/systemd nadal wymagają jawnego środowiska. |
| Rzeczywisty Docker/JAR, sztuczna kopia | 3 PASS: restore + klucz legacy i keyring, odrzucenie błędnego klucza, odrzucenie uszkodzonego archiwum z pasującą sumą. Każdy przypadek sprawdza brak pozostawionego kontenera i brak kluczy w logach. Zestaw powtórzono po końcowych flagach JVM. |
| Control Center JavaScript | 11 zestawów PASS; nowy zestaw sprawdza dynamiczne pola, potwierdzenie, blokadę PROD, busy, czyszczenie kluczy, błędy i brak samoczynnego ponownego otwierania formularza. |
| Helpery CI / eksport źródeł | 3 testy Python PASS. |
| Gitleaks | historia 0, kandydat 0, exit=0; kontrola źródeł bez montowania danych runtime. |
| Dokumenty banku | Potwierdzono zgodność SHA-256 wszystkich 7 chronionych notatek z poprzednim etapem. Nie zmieniano Mermaid ani PNG. |

W tym dodatku nie uruchamiano ponownie pełnej integracji banku/sklepu ani testów płatności — aplikacyjna zmiana Java to osobny, nierejestrowany jako bean weryfikator bez startu Spring. Wykonano pełne jednostkowe/MVC banku i rzeczywiste wywołanie zapakowanego weryfikatora z kontenerowym odtworzeniem. **Nie użyto istniejących backupów ani kluczy użytkownika.** Nie uruchomiono rotacji, nie zmieniono dev.env/test.env użytkownika i nie restartowano jego CC/aplikacji. Zdalne CI nie było uruchamiane; zmiana workflow oczekuje zwykłego przeglądu i wydania wraz z pozostałymi źródłami.

## Uzupełnienie implementacji i refaktoryzacja — 18.10.2026

Zakres bieżącego zlecenia: dodatkowe testy awarii oraz dalszy podział `CardService` i `ProfileController`. Nie uruchamiano migracji danych użytkownika ani rotacji kluczy. Notatki banku, Mermaid i PNG pozostają bez zmian; ich aktualizacja nastąpi na końcu.

### A03/A13/P15 — własny importer zamiast zewnętrznego produktu

Zewnętrzny importer nie jest wymagany. Można opracować własną, schematowo świadomą komendę administracyjną. Istniejące `PaymentMigrationReview` i `scripts/maintenance/payment_mapping_review.py` to inwentaryzacja i bramka przeglądu manifestu, **nie importer**. W tym etapie nie dodano trybu zapisującego historyczny backfill i nie wykonano go na żadnej bazie użytkownika.

Przed trybem zapisu potrzebne są: zatwierdzone snapshoty obu baz, jawne mapowanie sprzedawca/zamówienie/próba/provider/referencja, potwierdzenia wyników oraz uzgodnione okno serwisowe. Własny importer powinien sprawdzać niezmienność planu i istniejących rekordów, mieć dry-run, idempotentny checkpoint, raport konfliktów i test odtworzenia. Zapis w dwóch niezależnych bazach nie jest jedną lokalną transakcją: potrzebny jest zatwierdzony protokół etapów i wznowienia, który nie odblokuje nowej płatności przy częściowym wykonaniu. Nie wolno wywoływać nowego obciążenia ani traktować historycznego FAILED jako dowodu braku obciążenia.

### Podział klas — zaimplementowany zakres

- `CardService`: niewielka fasada zachowująca publiczne metody i rekordy wynikowe. Deleguje do wymaganych beanów; nie przechwytuje awarii infrastruktury i nie tworzy oddzielnej transakcji.
- `CardQueryService`: widok karty, odczyt limitu i statusu online. `cardFor` nadal jest transakcyjne z możliwością zapisu, ponieważ odczyt statusu może zakończyć czasową blokadę.
- `CardLifecycleService`: podłączenie, limity, blokowanie i aktywacja karty z dotychczasową blokadą rachunku.
- `CardPaymentService`: płatność lokalna, blokady, waluta/subkonto, limit i wynik karty.
- `OnlineCardPaymentService`: płatność online, replay, blokady obu rachunków i zapis obu ksiąg. Zwykłe `@Transactional` pracownika dołącza do transakcji orkiestratora; brak `REQUIRES_NEW`.
- `CardPaymentPolicy`: wspólne czyste walidacje, normalizacja i mapowanie DTO. Wydzielone ciała metod finansowych i walidacyjnych zachowują dotychczasową logikę; zmienia się właściciel odpowiedzialności.
- `ProfileController`: wyświetlanie i edycja danych. `ProfileSecurityController`: hasło i PIN. `ProfileTwoFactorController`: konfiguracja 2FA i jednorazowy odczyt kodów odzyskiwania. `ProfileLimitsController`: limity. `ProfilePageModel`: wyłącznie wspólne dane strony.
- Wszystkie dotychczasowe adresy `/profile/...`, formularze i przekierowania pozostawiono. Wydzielone kontrolery korzystają z istniejącego `SecurityConfig`; nie wprowadzono nowych reguł permitAll ani ścieżek pomijających zależności bezpieczeństwa.

### Dodatkowe testy — implementacja

- P01: blokada nadawcy/odbiorcy, zamknięty rachunek nadawcy, limit pojedynczy i dzienny użytkownika, bankowy limit pojedynczy i sumowany dzienny. Sprawdzany trwały FAILED, brak dodatkowego obciążenia i brak nowego zapisu w księdze.
- P02 / refaktoryzacja kart: rollback zewnętrznej transakcji wycofuje płatność lokalną oraz obie strony i obie księgi płatności online. Fasada nie zamienia wyjątku w DECLINED.
- P05/P06: równoległe BANK/STRIPE rezerwują tylko jedną próbę; zmiana waluty/providera przy tym samym kluczu odrzucona.
- P07: równoległy odczyt statusu i approve po kontrolowanym ustawieniu wygaśnięcia; brak obciążenia i końcowy FAILED. To nie jest test różnych zegarów procesów ani każdego porządku na granicy czasu.
- P11: podpisane syntetyczne webhooki przez MockMvc z rzeczywistymi usługami i PostgreSQL sklepu; replay sukcesu, błędny podpis, kwota, waluta, sprzedawca, zamówienie i nieznana próba. Osobno okno czasu podpisu, rotacja podpisów, zły sekret i błędny timestamp. MockMvc nie dowodzi konfiguracji sieci/TLS ani zewnętrznego kontraktu Stripe.
- P12: rzeczywisty błąd PostgreSQL podczas zapisu odmowy (wyłącznie trigger w jednorazowej bazie testowej); autoryzacja i rezerwacja pozostają PENDING, retry po usunięciu awarii zapisuje odmowę. Nie symuluje fizycznego zerwania sieci w trakcie COMMIT.
- P13/P14: wygaśnięta sesja Stripe nie jest klasyfikowana jako pewna odmowa; spóźniony sukces starej próby nie nadpisuje nowszego PAID ani referencji; równoległe identyczne sukcesy i późniejsze decline/unknown nie cofają PAID.
- Profil: wszystkie wydzielone strony wymagają uwierzytelnienia, wszystkie mutacje odrzucają błędny CSRF; niezgodne hasła nie wywołują zmiany ani unieważnienia sesji, kody odzyskiwania są usuwane po jednym odczycie, odrzucone wyłączenie 2FA nie unieważnia innych sesji.

### Wyniki końcowe tego uzupełnienia — PASS w wykonanym zakresie

| Zestaw | Wynik | Artefakt |
|---|---|---|
| Bank — jednostkowe/MVC | 165, failures=0, errors=0, skipped=0 | `target/audit-refactor-unit-final.log` |
| Bank — integracyjne | 52, failures=0, errors=0, skipped=0 | `target/audit-refactor-it-verified.log` |
| Sklep — jednostkowe | 39, failures=0, errors=0, skipped=0 | `shop-app/target/audit-refactor-unit-final.log` |
| Sklep — integracyjne | 27, failures=0, errors=0, skipped=0 | `shop-app/target/audit-refactor-it-final.log` |
| Gitleaks | historia=0 zgłoszeń, kandydat=0 zgłoszeń; exit=0 | `target/security/history.json`, `target/security/candidate.json` |
| Notatki banku | SHA-256 wszystkich 7 chronionych notatek identyczne jak przed pracą; PNG nie zmieniano | kontrola plików repo i Obsidian |

Łącznie 283 testy Maven PASS, w tym **53 dodatkowe scenariusze** względem poprzednich 230. Bankowy podzbiór `AuditPaymentConcurrencyIT` obejmuje teraz 40 scenariuszy; `SharedSessionHttpIT` nadal weryfikuje 5 scenariuszy z dwoma rzeczywistymi JVM. Integracyjne bazy i Redis były izolowane, z losowymi portami i sztucznymi danymi. Weryfikacja zakończyła się 18.10.2026; nie wdrażano zmian do istniejących procesów DEV/PROD.

W pierwszym przebiegu nowego testu odmowy asercja wywoływała `PaymentPurchaseGuard.snapshot` poza wymaganą transakcją MANDATORY. Poprawiono tylko sposób kontrolnego odczytu w teście, bez osłabienia konfiguracji aplikacji; końcowy pełny przebieg banku wskazany powyżej ma wynik PASS. Porównanie wydzielonych ciał metod kart z wersją sprzed refaktoryzacji nie wykazało zmiany ich logiki. W metodach profilu wspólne zasilanie modelu zastąpiono delegacją do `ProfilePageModel`.

Pełna macierz P01–P16 pozostaje otwarta w niewykonanych wariantach: m.in. śmierć procesu i fizyczne zerwanie połączenia w oknach COMMIT, wspólny test bank–sklep z utratą transportu po obciążeniu, wszystkie porządki callbacków oraz rzeczywiste Stripe TEST. P15 nadal wymaga zatwierdzonych danych i osobnego trybu zapisu importera. Nie uznano tych braków za testy zaliczone. Control Center nie zmieniano i nie uruchamiano ponownie jego zestawów w tym etapie. Późniejsze tabele historyczne przedstawiają wcześniejsze przebiegi, nie zastępują wyniku tego uzupełnienia.

## Domknięcie audytu — rozpoczęte 18.10.2026

Zlecono wdrożenie rozwiązań dla pozostałych punktów audytu. **Notatki banku, diagramy Mermaid i PNG pozostają teraz bez zmian. Aktualizacja dokumentów banku nastąpi na końcu, po zakończeniu zmian kodu i ich weryfikacji.** Bieżący postęp i wyniki dopisywane są wyłącznie do tego raportu audytowego.

- [x] A06: kontrolowane błędy przejściowe API i bezpieczne ponowienie przelewu po TOTP; regresja końcowa poniżej.
- [x] A10: podgląd, eksport i retencja zamkniętych powiadomień w Control Center; nie uruchamiano purge danych użytkownika.
- [x] A12: walidacja Mermaid, skan sekretów i analiza zależności w CI; lokalne wykonanie, nie deklaracja zdalnego CI.
- [ ] Pełne K1–K4: mechanizmy i lokalna regresja wdrożone; rzeczywista rotacja, akceptacja ról i zakresu pozostają otwarte.
- [x] A03/A13: narzędzia analizy i jawnego przeglądu planowania migracji historycznych płatności; bez niezatwierdzonego backfillu.
- [ ] Pełne K5–K7: kontrole lokalne, bounded reads i pomiar wdrożone; docelowa sieć, wolumeny i progi SLO niezatwierdzone.
- [ ] Pełne P01–P16: rozszerzona regresja PASS w opisanym zakresie; niewykonane warianty wskazano poniżej, nie uznano ich za zaliczone.
- [x] Czytelność: wymagane zależności bezpieczeństwa i stopniowe wydzielenie odpowiedzialności; bez deklaracji pełnego rozdzielenia dużych serwisów.
- [x] Końcowa regresja lokalna, ocena budowy eksportowanego kandydata i lista czynności operatora; bez commitu, zdalnego CI i wydania.

Operacje na rzeczywistych bazach, rotacja rzeczywistych kluczy, testy kontraktowe zewnętrznego konta Stripe i zatwierdzenie docelowej sieci wymagają właściwego środowiska oraz danych operatora. Przygotowanie ich mechanizmów i testy izolowane nie są oznaczane jako wykonanie tych operacji. Nie zatwierdza się automatycznie istniejącego brudnego drzewa Git ani nie usuwa plików użytkownika.

### Wyniki przyrostowe 18.10.2026

- A06: dodano odpowiedź 503/Retry-After z kodem `PAYMENT_OUTCOME_UNCONFIRMED`; nie klasyfikuje niepewnego commit jako odmowy. Wydzielono `TransferConfirmationWorkflow`, odczyt zakończonej operacji z kontrolą właściciela i pełnych danych przed kolejnym TOTP. Usunięto alternatywne konstruktory pomijające ochronę w kontrolerze przelewu i serwisie profilu. Pierwsza regresja: 139 testów jednostkowych/MVC banku, PASS.
- A10: implementacja planu/eksportu/purge w oddzielnym module i formularzu Centrali. Automatyczny purge pozostaje wyłączony; operator wybiera okresy (propozycje formularza: kosz 30 dni, archiwum 180). Chronione są aktywne wpisy, PROD, nierozwiązane sklasyfikowane problemy i niezakończone naprawy. Metadane deduplikacji są przechowywane przez 365 dni; kursory importu logów nie są usuwane. Pierwsze 3 testy retencji i 66 testów serwera, PASS.
- A12: dodano kontrolę Mermaid oraz przypięte skanery Gitleaks i OSV. W repo wykryto dwa historyczne błędy składni Mermaid; dokładne hashe niezmienionych bloków są jawnie odroczone w `scripts/ci/mermaid/deferred.json`. Nie jest to potwierdzenie poprawności tych diagramów; zmiana bloku unieważnia wyjątek. Naprawa dokumentów banku nastąpi na końcu.
- Pierwszy Gitleaks: 6 zgłoszeń w historii i 6 w źródłach kandydujących (po 3 unikalne lokalizacje, zdublowane przez regułę). Zweryfikowano stały, publiczny syntetyczny klucz fixture TOTP, nie rzeczywisty sekret użytkownika. Bieżące fixture kodują/generują wartość w runtime; wyjątki historii wskazują wyłącznie trzy dokładne fingerprinty niezmiennego commitu, bez wyłączania katalogów.
- Pierwszy OSV zgłosił podatne wersje bibliotek. Zastosowano poprawki w tych samych liniach wersji Jackson, Spring Framework, Netty, HttpComponents, Log4j API, Tomcat, PostgreSQL JDBC oraz poprawkę Bouncy Castle. Walidator Mermaid także aktualizowany do poprawionego wydania 11.x. Wynik po aktualizacji i pełna regresja pozostają do potwierdzenia; nie oznaczono SCA jako PASS na podstawie samej zmiany numerów.
- K1: dodano jawne narzędzie `maintenance.MaintenanceCommand` i akcje Centrali do planu/wykonania partii rotacji. Nie uruchamia kontekstu Spring, HTTP, Flyway ani schedulerów. DEV/TEST, wyłącznie jawny lokalny endpoint PostgreSQL, zamknięte argumenty; hasła i klucze tylko w filtrowanym środowisku procesu. Plan obejmuje hash zakresu, szyfrogramów i konfiguracji kluczy. Wykonanie wymaga `ROTATE`, ponownej kontroli planu i blokady administracyjnej; migracja i trwały checkpoint V30 zatwierdzają się razem. Ponowienie po utracie odpowiedzi odczytuje zapisany wynik. Zmiana danych/kluczy przerywa wykonanie.
- K1 — ważny warunek operacyjny: najpierw odtwarzalna kopia i zachowanie starego klucza, następnie załadowanie keyringu zawierającego nowy i stare klucze na **wszystkich** instancjach. Dopiero potem rotacja partiami. Sam zapis env nie zmienia działającego JVM. Panel wyświetla ten warunek; nie potwierdza automatycznie stanu cudzych procesów i nie restartuje usług. Usunięcie starego klucza wymaga osobnego sprawdzenia bazy oraz sesji/backupów.
- A03/A13: akcja „Przegląd historycznych płatności” wykonuje inwentaryzację banku w transakcji wymuszonej READ ONLY, bez przypisywania rekordów. Wskazuje `STOP_MAPPING_REQUIRED` przy wykrytych problemach. `scripts/maintenance/payment_mapping_review.py` kontroluje jawny manifest maksymalnie 1000 mapowań, hashe obu snapshotów, stan okna serwisowego, duplikaty, referencje i wskazanie dowodu uzgodnienia. Historyczne FAILED/PENDING/UNKNOWN nie są dowodem braku obciążenia. To **bramka przeglądu, nie importer i nie weryfikacja treści dowodów**. Backfill zatrzymano do otrzymania zatwierdzonej mapy i danych; nie wprowadzono automatu nadpisującego historię.
- K7: scheduler czyta DTO partiami po 100 (konfiguracja `TRANSFER_SCHEDULER_BATCH_SIZE`, zakres 1–1000), z górnym ID ustalonym na początku i niezmiennym kursorem ID. Zmiana następnego terminu nie umieszcza tego samego zlecenia ponownie w bieżącym przebiegu; błąd nie cofa kursora. Pętla pozostaje poza wspólną transakcją. V31 dodaje częściowy indeks kursora. Eksport historii czyta najwyżej limit+1 wierszy i ma timeout transakcji; limit `HISTORY_EXPORT_MAX_ROWS` domyślnie 1000, zakres 1–10000. Zbyt szeroki eksport kończy się 413 z poleceniem zawężenia filtrów, bez cichego ucięcia wyników. Nie jest to streaming dowolnie dużych PDF.
- Czytelność: `TransferConfirmationWorkflow`, `CardCurrencyValuation`, `notification_retention.py`, `maintenance_commands.py` i oddzielne moduły UI rozdzielają orkiestrację, wycenę i administrację. Zlikwidowano konstruktor karty dopuszczający brak kursów/księgi oraz ścieżki pomijające te zależności. Nie ma awaryjnego przeliczenia obcej waluty 1:1 przy brakującym repozytorium.
- Odtwarzalność: `source_bundle.py` eksportuje publiczne źródła wraz z nowymi plikami i hashami, z kontrolą rozwiązanego celu dowiązań, bez runtime env, kluczy, logów, node_modules i artefaktów. `candidate_build.py` zbudował bank i sklep z nowego katalogu eksportu. To nie jest commit, zdalne CI ani potwierdzenie kompletności wydania z obecnego HEAD; pliki nieśledzone nadal wymagają świadomego przeglądu/stagingu operatora.
- P12: dodatkowy test JDBC wykonuje rzeczywisty `commit()` w syntetycznej bazie, a dopiero potem zgłasza `SQLRecoverableException`, modelując utratę potwierdzenia. Salda, księga i autoryzacja pozostają spójnie zatwierdzone; odczyt statusu odzyskuje COMPLETED, a retry nie obciąża ponownie. Nie jest to fizyczne zerwanie TCP ani test śmierci procesu. Testy MVC oddzielnie sprawdzają bezpieczne 503 dla wyjątków transakcji, JPA, niedostępności zasobów i blokad, bez ujawniania szczegółów SQL.
- K4 — rozróżnienie CSRF: przy braku tokenu **w sesji** konfiguracja `SecurityConfig.invalidSessionUrl("/login?expired")` może obsłużyć `MissingCsrfTokenException` przekierowaniem 302. Przy istniejącym tokenie sesyjnym, ale brakującym tokenie **w żądaniu**, odmowa wynosi 403. Sprawdzono lokalną implementację `CsrfConfigurer.createAccessDeniedHandler` i `InvalidSessionAccessDeniedHandler`, nie wyłączono ochrony ani nie zaakceptowano samego 302 jako dowodu zalogowania. Referencja mechanizmu: [Spring Security — CSRF](https://docs.spring.io/spring-security/reference/servlet/exploits/csrf.html). Test jawnie materializuje token i weryfikuje brak udanego logowania po żądaniu bez niego.

### Regresja i bramki końcowe — 18.10.2026

| Kontrola | Wynik najnowszego zakończonego przebiegu |
|---|---|
| Bank — jednostkowe/MVC | 145 testów, 0 failures, 0 errors, 0 skipped; PASS (`target/audit-close-20261018-bank-final.log`) |
| Bank — integracyjne | 41 testów, 0 failures, 0 errors, 0 skipped; PASS (`target/audit-close-20261018-bank-verified-it.log`). Poprawiono wcześniejsze błędy fixture i obsługi proxy Mockito w teście; liczy się ten zakończony przebieg, nie poprzednie nieudane próby. |
| Podzbiór bankowych testów audytowych | 29 scenariuszy PASS; rotacja/checkpoint/replay, konflikt planu, retry przelewu, read-only inventory, pomiar bounded reads oraz utrata potwierdzenia COMMIT (`AuditPaymentConcurrencyIT`) |
| Dwa procesy / rzeczywiste HTTP | 5 scenariuszy PASS (`SharedSessionHttpIT`): sesja/login/logout, krytyczne odmowy ról/API/CSRF/Basic; pięć równoległych błędów 2FA i jeden zwycięzca dwóch poprawnych potwierdzeń; dwa równoległe drafty; sanitizujące proxy; zapakowane narzędzie administracyjne bez startu Flyway/HTTP. Oddzielne JVM, PostgreSQL 18 i Redis 7.4 na losowych portach testowych. |
| Sklep — jednostkowe / integracyjne | 29 / 15, bez failures/errors/skips; PASS (`shop-app/target/audit-close-20261018-shop-regression*.log`) |
| Control Center Python | 238 testów: 226 wykonanych poprawnie, 12 świadomie pominiętych live SQL/systemd; PASS części izolowanej (`target/audit-close-cc-python.log`) |
| Control Center JavaScript | 9 zestawów PASS, w tym retencja i administracja; jawny runner `scripts/ci/js_tests.py` (`target/audit-close-cc-js.log`) |
| Eksport źródeł / manifest mapowania | 3 testy Python PASS (`target/audit-close-ci-tests.log`) |
| Odtwarzalność pakietów | 699 plików publicznego kandydata; bank i sklep zbudowane w nowym katalogu, offline z lokalnego cache Maven; PASS. To nie jest świeży commit ani test pobierania zależności na nowym hoście. |
| Mermaid | 18 poprawnych bloków; 2 jawnie odroczone, hash-bound błędy w istniejących notatkach; brak nowych błędów. Nie stwierdza zgodności merytorycznej diagramów z nowym kodem. |
| Sekrety / zależności | Końcowy Gitleaks: historia 0, kandydat 0; OSV exit=0, bez zgłoszeń. PASS w wykonanym zakresie skanowania. Raporty bez wartości sekretów: `target/security`. Nie jest to dowód braku wszystkich klas podatności. |
| Ochrona dokumentów banku | Potwierdzono identyczne SHA-256 wszystkich 7 wskazanych notatek w repo i Obsidian. Nie generowano ani nie zmieniano PNG. |

Pomiar K7 z końcowego przebiegu na syntetycznym PostgreSQL 16: na 5000 zleceniach odczyt stron z kontrolowanym przesuwaniem terminu trwał 185 ms, największa strona miała 100 wierszy; odmowa nadmiernego eksportu 5000 operacji historii zajęła 138 ms. `EXPLAIN ANALYZE` pierwszej strony: Index Only Scan po V31, 100 wierszy, wykonanie 0,080 ms. Artefakt: `target/audit-performance-20261018.json`. To pojedynczy lokalny pomiar czasu/stron/planu, **nie** miarodajny pomiar szczytowej pamięci, percentyli pod obciążeniem, całej realizacji przelewów ani zatwierdzone SLO.

**Stan końcowy etapu 18.10:** zaimplementowano i lokalnie zweryfikowano opisane mechanizmy. Wszystkie zakończone zestawy wskazane w tabeli są PASS; 12 kontroli live pominięto jawnie. **Nie zamknięto całego audytu ani wdrożenia operacyjnego.** Niewykonane testy, faktyczna migracja, rotacja, sieć i wydanie pozostają bramkami z następnej listy. Testy i narzędzia nie zostały użyte do zmiany istniejących danych DEV/PROD; nie wykonywano operacji Vault, realnego Stripe ani usuwania plików użytkownika.

#### Nadal wymagane rozstrzygnięcie lub środowisko operatora — nie „wdrożono wszystkiego”

1. **A03/A13, P15 — STOP rzeczywistego backfillu.** Brakuje zatwierdzonych snapshotów, mapy sprzedawca/rachunek/zamówienie/próba/referencja i dowodów wyniku operatora płatności. Można napisać własny importer, ale nie wolno bez tych danych zatwierdzić planu i uruchomić zapisu historii. Narzędzia przeglądu nie zastępują tych dowodów. Nie zmieniono NULL merchantId ani nie uznano FAILED za brak obciążenia.
2. **K1 — rzeczywista kopia, odtworzenie i rotacja kluczy.** Mechanizm i regresja na sztucznych danych są wdrożone; użytkownik musi potwierdzić załadowanie keyringu na wszystkich instancjach, odtworzenie kopii oraz okno zmiany. Nie usunięto żadnych kluczy.
3. **K5/K6 — docelowa sieć, proxy, firewall i dostęp do hosta/WSL.** Lokalny test sanitizującego proxy nie potwierdza odcięcia prawdziwego backendu. `forward-headers-strategy=framework` nadal wymaga usuwania niezaufanych nagłówków na bramie i braku bezpośredniego dostępu do backendu. Panel pozostaje lokalnym narzędziem, nie usługą zdalną z RBAC.
4. **K7 — wolumeny i progi akceptacji.** Wymagane dane docelowe, zasoby, percentyle, akceptowalne czasy i staging do testów obciążenia. Nie wyprowadzano gotowości produkcyjnej z 5000 sztucznych wierszy.
5. **P01–P16 — pełny zakres fault injection i zewnętrzny Stripe TEST.** Pokrycie rozszerzono w bieżącym uzupełnieniu opisanym na początku, w tym o awarię zapisu odmowy i syntetycznie podpisane webhooki. Nadal brak dowodu wszystkich kombinacji śmierci procesu/połączenia w oknie COMMIT, pełnych wariantów webhooków/callbacków oraz testów rzeczywistego konta Stripe. Wykonanie wybranych scenariuszy nie oznacza pełnego PASS każdej pozycji macierzy.
6. **Wydanie.** Nie stage'owano, nie commitowano i nie wysyłano brudnego drzewa. Przed wydaniem trzeba przejrzeć i dołączyć wszystkie wymagane źródła/migracje/lockfile, następnie wykonać CI ze świeżego checkoutu wybranego commitu. Lokalny eksport uwzględnia pliki nieśledzone, których sam HEAD nie zawiera.
7. **Dokumentacja banku — na końcu.** Dopiero po rozstrzygnięciu bramek i ustabilizowaniu kodu zaktualizować notatki, pełne opisy przepływów, diagramy i PNG oraz usunąć dwa dokładne wyjątki walidatora po naprawie bloków. Obecne diagramy nie otrzymują przez ten etap certyfikatu aktualności.

---

Stan historyczny na 17.10.2026: **część kodowa opisana poniżej zaimplementowana i sprawdzona w izolacji; plan kontroli oraz rzeczywiste wdrożenie nie są zamknięte w całości**. Użytkownik wybrał wariant A: trwały `merchantId`, niezależny od rachunku i rotacji kluczy. A05 używa atomowego `UPDATE ... WHERE used_at IS NULL` w tej samej transakcji. **Diagramy i PNG pozostawiono bez zmian.**

## Zakres i bezpieczeństwo

Zmiany dotyczą kodu i testów. Nie wykonywać migracji, rotacji ani operacji finansowych na istniejącym DEV/PROD. Bazy testowe mają być izolowane. Nie odczytywać rzeczywistych sekretów. Historycznych płatności nie przypisywać automatycznie do wymyślonego sprzedawcy; niejednoznaczna migracja musi zakończyć się raportem i blokadą nowych operacji.

## Postęp

- [x] A05: atomowe zużycie resetu hasła i weryfikacji email, testy rollbacku i współbieżności.
- [x] Merchant identity: powiązanie uwierzytelnionego klienta API ze stałym sprzedawcą, kontrola własności statusu/redeem/refund/card.
- [x] A03: atomowy sukces, obsługa odmowy po rollbacku, ochrona stanów końcowych; pokrycie opisano w macierzy poniżej.
- [x] A13: trwałe próby, UNKNOWN, blokady nowej próby/metody, addytywne migracje i bezpieczna odmowa nieprzypisanej historii. To nie jest wykonany backfill danych użytkownika.
- [x] Implementacja A04/A06/A07: provider dispatch, świeży stan po blokadzie, timeouty i retry; ograniczenia testów podano jawnie.
- [x] Implementacja A01/A02/A08/A09/A11/A12 oraz techniczne mechanizmy K1/K2/K3/K4 i testy lokalnej Centrali.
- [x] Regresja obu modułów i Control Center oraz raport; niewykonane kontrole oznaczone jawnie.
- [ ] Pełne zamknięcie K1–K7 i P01–P16 w docelowym zakresie, rzeczywista migracja/backfill, akceptacja operatora i wydanie.

## Wykonanie

1. Rozstrzygnięcie wariantu A przyjęte. Repozytorium zawiera wcześniejsze zmiany; nie resetowano ich.
2. Rozpoczęto A05. Warunkowy zapis tokena jest przed zmianą hasła/zweryfikowaniem użytkownika; niepowodzenie dalszego zapisu ma wycofać obie zmiany.

3. Pierwsze 8 testów `AuditPaymentConcurrencyIT` przeszło (PostgreSQL/Redis Testcontainers): odmowa A03, równoległe approve, stany końcowe, tożsamość i rotacja rachunku, kontrola właściciela, rollback po księgowaniu, współbieżne tokeny oraz rollback zużycia. Kolejne scenariusze K2/K3 dodano później i wymagają ponownego uruchomienia.
4. Pierwszy przebieg `PaymentAttemptConcurrencyIT`: 6 scenariuszy poprawnych, 1 błąd przygotowania fixture, ponieważ stary schemat już posiada indeks jednego aktywnego zwrotu. Usunięto wyłącznie nowo utworzoną, zbędną migrację V14 z tej implementacji (nie była wdrażana do DEV/PROD). Kontrolę sprzecznego importu przeniesiono do V13; fixture symuluje brak starego indeksu w odrębnym schemacie testowym. Dane użytkownika nie były sprawdzane ani usuwane.
5. Implementowane: centralny magazyn wyzwania loginu, atomowe drafty, rejestr kroku TOTP, keyring z key-id i migracja partiami; wymagają końcowej regresji. Rzeczywista rotacja, przypisanie historycznego sprzedawcy i kontrola docelowej sieci pozostają czynnościami operatora, nie zostały uruchomione.

6. Końcowa regresja zakończona 17.10.2026. Wyniki poniżej dotyczą nowych przebiegów, nie wcześniejszego audytu. Poprawiono błąd sygnatury replay zwrotu, kontrakt `refund:<orderNumber>`, fixture kontekstu bez bazy oraz sposób zamykania fixture integracyjnej. Ostatnie przebiegi obu modułów mają kod wyjścia 0.

## Zaimplementowane mechanizmy — zakres dowodów

Ścieżki poniżej są względne wobec repozytorium. To opis nowych źródeł, nie potwierdzenie wdrożenia do istniejących baz. Końcowe wyniki podaje protokół regresji na końcu dokumentu.

| Obszar | Zmiana | Główne źródła |
|---|---|---|
| A05 | Warunkowe zużycie tokena (`used_at IS NULL`, `expires_at > now`) przed zmianą użytkownika, kontrola jednego zmienionego wiersza; obie operacje we wspólnej transakcji | `src/main/java/org/demo/bankdemo/auth/EmailTokenService.java:85`, `PasswordResetTokenRepository.java`, `EmailVerificationTokenRepository.java` w tym samym pakiecie |
| A03 | Orkiestracja approve poza transakcją; księgowanie i sukces autoryzacji razem; pewna odmowa w nowej transakcji dopiero po rollbacku; ponowna kontrola stanu; brak regresji sukcesu po terminie lub reject | `src/main/java/org/demo/bankdemo/payment/authorization/PaymentAuthorizationService.java:127` oraz `PaymentAuthorization.java` |
| A13 / wariant A | Stały sprzedawca z zaufanej konfiguracji klucza API, nie z dowolnego pola klienta; rejestr zakupu i prób w banku; osobne próby w sklepie utrwalane przed HTTP; UNKNOWN blokuje nowy klucz i zmianę providera | `src/main/java/org/demo/bankdemo/config/PaymentApiKeyFilter.java`, `payment/PaymentPurchaseGuard.java`; `shop-app/src/main/java/org/demo/shopapp/order/PaymentAttemptService.java`, `PaymentAttemptRepository.java` |
| A04 | Wybór integracji według utrwalonego providera BANK/CARD/STRIPE, nie prefiksu `card-` | `shop-app/src/main/java/org/demo/shopapp/order/PaymentResolutionService.java` |
| A06 | Skalarne identyfikatory rachunków przed uporządkowanymi blokadami, odświeżenie stanu pod blokadą i kontrola replay dla płatności online; dodatkowy unikalny zakup w księdze | `src/main/java/org/demo/bankdemo/payment/PaymentService.java`, `card/CardService.java`, migracja banku V29 |
| A07 | Jawne timeouty connect 3 s / read 10 s; krótka rezerwacja zwrotu, HTTP poza transakcją, krótki zapis wyniku; niepewny zwrot zachowuje tę samą próbę | `shop-app/src/main/java/org/demo/shopapp/config/ShopBankConfiguration.java`, `payment/StripeCheckoutClient.java`, `order/RefundService.java:31` |
| A08 | Zamknięty słownik LOG/SMTP; fail-fast PROD, jeśli nie ustawiono SMTP; logowanie treści tylko przy jawnym DEV/TEST i nigdy przy aktywnym PROD (również mieszanym dev+prod) | `src/main/java/org/demo/bankdemo/config/EmailProperties.java`, `ProductionConfigurationValidator.java`, `auth/EmailDeliveryService.java` |
| A01/A02 | Wyłączenie ogólnego Basic w banku; Basic metryk nie pozostawia kontekstu klienta; ponowne sprawdzenie flag UserDetails przed zakończeniem 2FA | `src/main/java/org/demo/bankdemo/config/SecurityConfig.java:100`, `MetricsBasicAuthenticationFilter.java`, `auth/TwoFactorLoginService.java` |
| A09 / K2 | Atomowe skrypty Redis: liczniki z TTL, challenge i liczba prób; centralny magazyn wyzwania loginu i draftów zamiast lokalnego monitora sesji | `src/main/java/org/demo/bankdemo/config/AtomicRedisCounter.java`, `auth/LoginChallengeStore.java`, `transfer/TransferDraftStore.java`, `transfer/TransferTotpChallengeService.java`; analogiczny licznik sklepu |
| K1/K3 | Keyring i wersjonowany szyfrogram z key-id, zachowanie odczytu starego formatu, jawna migracja partiami; atomowy rejestr zaakceptowanego kroku TOTP wspólny dla użyć tokena | `src/main/java/org/demo/bankdemo/security/TotpSecretCipher.java`, `TotpKeyRotationService.java:15`, `TotpReplayGuard.java:16`; `profile/TwoFactorService.java` |
| K4 | Default-deny nieznanych API banku i endpointów sklepu, zawężone wyjątki CSRF dla bezstanowej integracji | `src/main/java/org/demo/bankdemo/config/SecurityConfig.java:90`; `shop-app/src/main/java/org/demo/shopapp/config/ShopSecurityConfig.java:77` |
| A11/A12 | Wykluczenie lokalnych sekretów z kontekstu Docker; obowiązkowy job Python/JavaScript Control Center w CI | `.dockerignore:2`; `.github/workflows/ci.yml:20` |

Niepewny błąd transportu, 404 przy odczycie statusu, timeout lub utracony callback nie są pewną odmową. Trzy błędy sprawdzenia zawieszają uzgadnianie, ale nie otwierają nowej płatności. Bankowa odmowa karty ma zapisany wynik i snapshot, dzięki czemu późniejszy odczyt nie jest skazany na 404. Nie zapisuje się PAN/CVV ani surowych kodów w nowych tabelach prób.

Usunięto niewykorzystywane alternatywne metody zapisu statusów w `OrderRepository`. `OrderStateService` jest fasadą tylko do odczytu; przejścia płatności wykonuje `PaymentAttemptService`. Nie usuwano plików użytkownika ani starych wdrożonych migracji.

## Konfiguracja i migracja — bramka przed uruchomieniem istniejącego DEV

**Nie restartować istniejących aplikacji w założeniu, że dane historyczne przypiszą się automatycznie.** Flyway uruchamia migracje przy starcie aplikacji, mimo że w tej pracy użyto go wyłącznie na bazach Testcontainers. Potrzebna jest kopia, kontrola historii i skoordynowane przejście banku oraz sklepu. Stara wersja sklepu nie rozumie blokady UNKNOWN, dlatego nie powinna równolegle przyjmować nowych płatności podczas przejścia.

Nowe nazwy konfiguracji (nie są to wartości rzeczywistych sekretów):

- `SHOP_MERCHANT_ID` — jeden stały identyfikator sklepu, niezależny od rachunku i ID klucza API.
- `BANK_API_KEY_MERCHANTS` — przypisanie istniejących ID kluczy do sprzedawcy, np. `legacy=novatech-shop,new-key=novatech-shop`. Dwa klucze podczas rotacji mają wskazywać ten sam merchantId.
- `BANK_PAYMENT_MERCHANTS` — przypisanie sprzedawcy do dozwolonych rachunków. Wpisy rozdziela przecinek, rachunki tego samego sprzedawcy znak `+`. Rachunków nie pobiera się z niezaufanego merchantId w request.
- `TOTP_ENCRYPTION_KEYS` — keyring `id=***,...`, `TOTP_ENCRYPTION_ACTIVE_ID` — ID używane przy nowym szyfrowaniu. Stary `TOTP_ENCRYPTION_KEY=***` pozostaje potrzebny do starych szyfrogramów i sesji. Zmiana klucza nie jest zwykłą zmianą hasła w edytorze.
- TEST używa osobnych `TEST_TOTP_ENCRYPTION_KEYS`, `TEST_TOTP_ENCRYPTION_ACTIVE_ID` i `TEST_TOTP_ENCRYPTION_KEY`; syntetyczne mapowania profilu test nie są konfiguracją istniejących danych DEV.
- Domyślne ID pojedynczego klucza TEST banku/sklepu to `legacy`. `TEST_BANK_API_KEY_ID` musi odpowiadać rzeczywistemu ID skonfigurowanej definicji, nie nazwie profilu. `TEST_SHOP_MERCHANT_ID` jest wspólne dla obu stron. Dodano regresję domyślnych wartości profilu sklepu.
- `EMAIL_MODE=SMTP` jest wymagany w PROD; dostarczenie działającego JavaMailSender/konfiguracji SMTP i test dostarczenia poczty należą do przygotowania wdrożenia. LOG pozostaje dostępny tylko w jawnie wybranym DEV/TEST; takie logi są poufne. Literówka nie przełącza wysyłki na logowanie linków.

Nazwy dodano do walidacji/edytora Control Center. Nie wypełniano rzeczywistych plików `.env`, Vault ani mapowania istniejącego sprzedawcy. Ogólny edytor „Pliki i sekrety” pozwala dodać pola; samo pojawienie się pól nie przeprowadza migracji danych.

Migracje tej implementacji:

1. Bank **V26** — identity i rejestry zakupów/prób; **V27** — zużycie kroku TOTP; **V28** — snapshot odmowy karty; **V29** — merchantId w księdze i jeden sukces pierwotnego zakupu `(merchant_id, payment_order_id)`. Zwroty są wyłączone z tego indeksu i pozostają powiązane z oryginałem.
2. Sklep **V13** — trwałe próby, UNKNOWN, merchantId; kontrola sprzecznych historycznych zwrotów przerywa migrację. Stary indeks jednego aktywnego zwrotu już istniał; nie dublowano go kolejną migracją.
3. Historyczne rekordy zachowują NULL merchantId. Nowa płatność na takim zamówieniu lub rozpoznana kolizja ze starą płatnością jest blokowana. To celowe zabezpieczenie, nie gotowy automatyczny backfill. Zmiana samego NULL na bieżące merchantId nie odtwarza prób, referencji i stanu finansowego.
4. Skrypty `docs/payment-migration-bank-preflight.sql` i `docs/payment-migration-shop-preflight.sql` są **tylko do odczytu i wymagają już nowego schematu** (bank V26/V28, sklep V13). Najpierw uruchamia się migracje i preflight na zatwierdzonej kopii. Nie są skryptem konwersji ani dowodem braku operacji w toku; nie uruchomiono ich na danych użytkownika.
5. Przed rzeczywistym backfillem operator musi zatwierdzić mapę sprzedawca → rachunki → zamówienia → próby → referencje banku/operatora. Niejednoznaczny FAILED nie oznacza braku obciążenia. Dwa sukcesy lub brak potwierdzenia oznaczają STOP i raport incydentu, bez automatycznego kasowania, scalania lub zwrotu.

## Ograniczenia i prace niewykonane

- Nie wykonano rzeczywistej rotacji TOTP, testu odtworzenia kopii z realnymi kluczami, migracji historycznych płatności ani żadnej operacji na istniejących saldach.
- K2: testy współdzielonego Redis używają dwóch instancji magazynu i odrębnych obiektów sesji; nie zastępują dwóch pełnych procesów HTTP z jednym cookie i load balancerem. Ten scenariusz pozostaje do wykonania.
- K3: nowy udany kod TOTP jest jednokrotny także pomiędzy logowaniem i potwierdzeniem przelewu. Szybkie przejście może wymagać odczekania na nowy kod. Akceptacja komunikatów/UX należy do właściciela.
- K4: zmieniono politykę i sprawdza się regresję; pełna uzgodniona macierz każdego endpointu, roli i klienta nie została zakończona.
- K5: brak kontroli rzeczywistego proxy, firewall, DNS/TLS i zewnętrznej ekspozycji. K6: testy lokalnej Centrali nie oznaczają gotowości do udostępnienia zdalnego bez RBAC.
- K7: nie wykonano pomiarów wolumenów/SLO ani testu obciążenia staging. Brakuje uzgodnionych progów i charakterystyki wdrożenia.
- Nie przeprowadzono kontraktowego testu Stripe TEST z rzeczywistym kontem providera. Stary UNKNOWN Stripe bez zachowanego session ID nie jest ponawiany POST-em; samo wygaśnięcie sesji nie jest w kodzie dowodem końcowego braku obciążenia. Takie próby wymagają autorytatywnego uzgodnienia intencji/operatora, nie wymuszenia nowego klucza.
- Brak świeżego checkoutu/uruchomienia pipeline w zdalnym CI; pracowano na istniejącym brudnym drzewie. Nowe pliki trzeba świadomie dołączyć do wydania, nie commitować bez przeglądu całego lokalnego drzewa.
- Nie wdrożono polityki retencji powiadomień A10 (nie ustalono czasu/eksportu/purge). Nie wykonano rzeczywistego dostarczenia SMTP. Test canary Docker wykonano na syntetycznym, oddzielnym kontekście — nie przez wysyłanie prawdziwych sekretów do buildera.
- Diagramy Mermaid i PNG świadomie pozostawiono bez zmian; obecne obrazki nie stanowią dokumentacji nowych granic transakcji i prób.

## Ocena gotowości

Zaimplementowany kod ma usuwać przyczyny A03/A13 dla nowych, poprawnie przypisanych płatności, a nie jedynie zmieniać ich etykietę statusu. Ostateczne zamknięcie wymaga pozytywnej regresji i zatwierdzonej migracji istniejących danych. Nie deklaruje się globalnego exactly-once przez dwie bazy i HTTP ani gotowości do prawdziwych środków. Niewykonane kontrole powyżej pozostają bramkami wydania.

## Pokrycie macierzy P01–P16 — nie mylić testu izolowanego z wdrożeniem

Główne źródła dowodów: `src/test/java/org/demo/bankdemo/infrastructure/AuditPaymentConcurrencyIT.java` (bankowa baza i Redis), `shop-app/src/test/java/org/demo/shopapp/infrastructure/PaymentAttemptConcurrencyIT.java` (baza sklepu), `shop-app/src/test/java/org/demo/shopapp/integration/ShopBankPaymentFlowIT.java` (HTTP do sklepu i kontrolowany bank), testy jednostkowe `PaymentResolutionServiceTest`, `RefundServiceTest`, `PaymentApiKeyFilterTest` i `ProfileSecurityWebTest`. Poniższa tabela określa zakres testów; ich wynik końcowy podaje protokół regresji.

| ID | Pokrycie automatyczne w tej implementacji | Co pozostaje poza tym dowodem |
|---|---|---|
| P01 | Brak środków; blokada nadawcy/odbiorcy i zamknięcie nadawcy; limity pojedyncze/dzienne użytkownika i banku; trwała odmowa bez dodatkowego ruchu sald/księgi | Nie pełna kombinatoryka statusów obu rachunków, walut i równoczesnych zmian limitów |
| P02 | Sukces i wymuszona awaria po zapisie księgi; pełny rollback sald/księgi/autoryzacji | Brak pełnego fault injection na każdym punkcie procesu |
| P03 | Sklep zachowuje UNKNOWN po utracie odpowiedzi; status po kluczu odtwarza wynik; bank odrzuca drugi klucz/rachunek tego samego sprzedawcy | Nie wykonano jednego wspólnego testu dwóch rzeczywistych aplikacji z zerwanym HTTP po commit banku |
| P04 | Trwała rezerwacja przed HTTP i odtworzenie statusu przez usługę | Bez zabicia i ponownego uruchomienia procesu w obu punktach awarii |
| P05 | Równoległe różne klucze i równoległe BANK/STRIPE w bazie sklepu; zmiana providera przy UNKNOWN odrzucona | Równoczesne dwa providery przez dwa procesy HTTP |
| P06 | Inna kwota, waluta lub provider przy tym samym kluczu; kontrola danych i właściciela w banku oraz snapshota karty; niespójne pola podpisanego webhooka | Nie pełna kombinatoryka każdego pola przez HTTP |
| P07 | approve/approve, approve/reject oraz approve/status dla kontrolowanie wygasłej autoryzacji równolegle; spójny wynik finansowy | Przejście przez granicę czasu podczas oczekiwania na blokadę i wszystkie porządki tego wyścigu |
| P08 | Ponowne approve/reject po terminie dla COMPLETED i REDEEMED zachowuje sukces | Brak testu odrębnych procesów z różnymi zegarami |
| P09 | Zużyty redeem odrzucony; autorytatywny status zachowuje REDEEMED i referencję | Test kontraktowy rzeczywistego wdrożenia i zerwanego transportu |
| P10 | Trzy błędy i zawieszenie w bazie; nowy klucz nadal zablokowany; log alarmu w regresji schedulera | Nie weryfikowano powiadomienia na działającym panelu operatora |
| P11 | Brak regresji PAID; kontrola merchantId/kwoty/danych Stripe i korelacji; HTTP callback; MockMvc + rzeczywista baza dla podpisanych webhooków, replay i błędnych danych; walidacja wieku i wielu podpisów | Pełna macierz wyścigów i spóźnionych zdarzeń; kontrakt z rzeczywistym Stripe, siecią i konfiguracją filtrów webhooka |
| P12 | Rzeczywisty commit banku, po nim sztucznie utracone potwierdzenie; status odtwarza sukces; błąd PostgreSQL przy zapisie odmowy pozostawia PENDING i rezerwację, ponowienie po naprawie zapisuje odmowę | Fizyczna awaria sieci do PostgreSQL w trakcie COMMIT i zabicie procesu w różnych oknach |
| P13 | Dispatch Stripe po providerze pomimo klucza `card-`; UNKNOWN Stripe nie powoduje nowego POST; wygaśnięcie sesji nie jest dowodem braku obciążenia | Zewnętrzny Stripe TEST oraz stara sesja po rzeczywistym okresie retencji |
| P14 | Pewna odmowa pozwala na nową próbę z zachowaniem starego rekordu; spóźniony sukces starej próby nie nadpisuje nowszego PROCESSING/PAID ani referencji; duplikaty sukcesu nie cofają PAID | Pełny transport HTTP późnego callbacku starej próby po późniejszym sukcesie i wszystkie porządki zdarzeń |
| P15 | Migracja nowej bazy; kontrolowana stara baza V12 z konfliktem zwrotów przerywa V13; nieprzypisany historyczny FAILED blokowany bez dopisywania właściciela | Rzeczywista zatwierdzona kopia danych i pełny backfill historycznych prób |
| P16 | Nowy klucz/rachunek tego samego sprzedawcy odrzucony; także bezpośrednie obejście orkiestratora zatrzymuje indeks księgi i rollback; powiązany zwrot i jego replay pozostają możliwe | Bez testu na prawdziwych środkach; takiego testu nie należy wykonywać jako regresji |

Kontrakt zwrotu sprawdzono dodatkowo między źródłami banku i sklepu: bankowy wynik ma `orderId=refund:<orderNumber>`. Sklep weryfikuje ten dokładny identyfikator, kwotę, walutę i referencję, zamiast pozostawiać wykonany zwrot jako niepotwierdzony wskutek porównania do nieprefiksowanego numeru. Test HTTP sklepu obejmuje zwrot, replay bez drugiego wywołania banku i odmowę nowego klucza.

## Protokół końcowej regresji — 17.10.2026

Środowisko: WSL Ubuntu-24.04, Java 25.0.4.1, Docker 29.4.3. PostgreSQL/Redis używane w integracji były kontenerami testowymi z losowymi portami i sztucznymi danymi. Nie korzystano z baz DEV/PROD. Wynik PASS poniżej oznacza konkretny wykonany zestaw, a nie automatyczne zamknięcie pełnej macierzy z planu.

| Zestaw | Wynik końcowy | Artefakt |
|---|---|---|
| Bank — jednostkowe/MVC | **135 testów, 0 failures, 0 errors, 0 skipped; PASS** | `target/surefire-reports/TEST-*.xml` dla klas `*Test`; `target/audit-unit.log` |
| Bank — integracyjne | **30 testów, 0 failures, 0 errors, 0 skipped; PASS** | `target/failsafe-reports/failsafe-summary.xml`, `target/audit-integration.log` |
| Nowy bankowy zestaw audytowy (podzbiór powyższych 30) | **23 scenariusze; PASS** | `target/failsafe-reports/org.demo.bankdemo.infrastructure.AuditPaymentConcurrencyIT.txt` |
| Sklep — jednostkowe | **29 testów, 0 failures, 0 errors, 0 skipped; PASS** | `shop-app/target/surefire-reports/TEST-*.xml` dla klas `*Test`; `shop-app/target/audit-unit.log` |
| Sklep — integracyjne | **12 testów, 0 failures, 0 errors, 0 skipped; PASS** | `shop-app/target/failsafe-reports/failsafe-summary.xml`, `shop-app/target/audit-integration.log` |
| Python Control Center | **230 testów: 218 wykonanych poprawnie, 12 świadomie pominiętych live; PASS zestawu izolowanego** | `target/audit-cc-python.log` |
| JavaScript Control Center | **Wszystkie 8 zestawów; PASS** | `scripts/control-center/web/test_*.js`, wynik wykonania w rozmowie |
| Docker context canary | **PASS** — zwykłe źródło skopiowano, syntetyczne `.novabank/env/dev.env` i `private.key` wykluczono | `target/audit-docker-context.log` |
| Kontrola białych znaków | **PASS** — brak zgłoszeń `git diff --check` | Polecenie poniżej |

W logach są oczekiwane WARN/ERROR wymuszanych awarii (np. limit blokady, zawieszone uzgadnianie) oraz ostrzeżenia przy zatrzymywaniu kontenerów po testach. O wyniku decydują aktualne raporty testowe i kod wyjścia Maven, nie samo wyszukanie słowa ERROR. Stare raporty `*IT` mogą pozostać w `surefire-reports` po wcześniejszych uruchomieniach; **nie należy dodawać ich do liczby testów jednostkowych**. Integrację ocenia się w `failsafe-reports`.

Polecenia odtwarzalne z katalogu repozytorium w WSL:

```bash
./mvnw -Punit-tests test
./mvnw -Pintegration-tests verify
./mvnw -f shop-app/pom.xml -Punit-tests test
./mvnw -f shop-app/pom.xml -Pintegration-tests verify
python3 -B -m unittest discover -s scripts/control-center/web -p 'test_*.py'
for test in scripts/control-center/web/test_*.js; do node "$test" || exit 1; done
git -c core.fsmonitor=false -c core.safecrlf=false diff --check
```

Canary wykonano na osobnym, nowo utworzonym katalogu syntetycznym ze skopiowanym `.dockerignore`, `FROM scratch` i `COPY . /included/`, przez `docker build --network=none --output type=local,...`. Sprawdzono obecność zwykłego pliku i brak obu canary w eksporcie. Po sprawdzeniu usunięto tylko własny kontekst i jego eksport; żadnych rzeczywistych sekretów nie odczytano, nie wysłano do buildera i nie usunięto. Ten test potwierdza działanie wskazanych reguł, nie zawartość już opublikowanych obrazów.

Artefakty `target` są wynikami lokalnymi i mogą zniknąć po `clean`; nie są commitem ani dowodem zdalnego CI. Zaktualizowano odsyłacze w audycie i historycznym raporcie wstrzymania. **Następny etap operacyjny wymaga decyzji i dowodów wymienionych w sekcji „Konfiguracja i migracja”, nie samodzielnego restartu istniejącego DEV.**

## Podsumowanie końcowe i zamknięcie etapu audytu — 23.10.2026

### Decyzja i znaczenie zamknięcia

Na wniosek właściciela kończymy etap analizy i implementacji napraw dla lokalnego **DEV/TEST**, z jawnymi odroczeniami poniżej. Właściciel zgłasza pozytywne wyniki wstępnych testów nowych funkcji Control Center. Nie jest to bezwarunkowe zaliczenie wszystkich bramek wydania, K5/K6 ani zgoda na produkcyjne przetwarzanie rzeczywistych środków.

**Ten rozdział określa aktualny stan.** Wcześniejsze checklisty i tabele z 17–21.10 pozostają historią prac. Ich stwierdzenia „brak importera”, „brak pełnej macierzy K4”, „nieuzgodnione progi DEV/TEST” czy „brak fizycznego przerwania połączenia z DB” nie są aktualną listą braków: odpowiednie mechanizmy i scenariusze dodano później. Nie zmieniano wstecz wyników wcześniejszych przebiegów.

Przegląd wykonano na gałęzi `Nova-bank-part3`, HEAD `180b9cddd8ea86186df97df47883320184f38770`, ze znaczną liczbą zmian poza commitem. Lokalne wyniki dotyczą kandydata źródeł z tego drzewa, **nie samego HEAD ani zdalnego pipeline GitHub**. W tej sesji zmieniono ten raport i uporządkowano opisane artefakty; nie zmieniano kodu aplikacji, diagramów banku, danych baz ani konfiguracji sekretów.

### Co zostało wdrożone

| Punkt | Stan implementacji na zakończenie etapu DEV/TEST |
|---|---|
| A01 | Wyłączono bankowe HTTP Basic jako alternatywną ścieżkę klienta omijającą formularz/MFA; techniczne uwierzytelnienie metryk jest oddzielone od dostępu klienta. |
| A02 | Przed ukończeniem 2FA ponownie sprawdzany jest status użytkownika. Testy obejmują blokadę między hasłem a kodem oraz unieważnienie sesji po blokadzie administracyjnej między dwoma procesami. |
| A03 | Sukces księgowania i autoryzacji jest atomowy, pewna odmowa zapisywana po rollbacku nieudanej próby; zakończony sukces nie jest degradowany przez późniejsze reject/expiry. |
| A04 | Uzgadnianie wybiera providera z trwałej próby, nie z prefiksu klucza. Stripe nie jest kierowany do bankowego statusu karty. |
| A05 | Zgodnie z decyzją właściciela: atomowy `UPDATE ... WHERE used_at IS NULL AND expires_at > :now`, a zużycie tokena i zmiana hasła/weryfikacja użytkownika w tej samej transakcji. |
| A06 | Uporządkowane blokady, świeży odczyt, ponowna kontrola idempotencji i bezpieczne retry. Błędy przejściowe/niepewny commit nie są zamieniane na pewną odmowę; API ma kontrolowaną odpowiedź o niepotwierdzonym wyniku. |
| A07 | Jawne timeouty klientów HTTP, trwałe próby i krótkie transakcje oddzielone od wywołań zewnętrznych, również dla zwrotów. |
| A08 | Walidowany tryb poczty i wymuszenie SMTP w PROD zamiast niejawnego logowania linków/tokenów. Rzeczywiste dostarczenie SMTP to nadal kontrola środowiska docelowego. |
| A09 | Atomowe operacje Redis łączą zapis/licznik z TTL; sesje, wyzwania i drafty nie opierają się wyłącznie na lokalnym monitorze JVM. |
| A10 | Plan retencji, eksport i zatwierdzane usuwanie starych zamkniętych powiadomień, z ochroną aktywnych problemów. Nie wykonywano purge historii operatora podczas porządkowania projektu. |
| A11 | Sekrety i runtime są wyłączone z kontekstu Docker. Wcześniejszy test canary sprawdził wykluczenie na sztucznych danych, bez wysyłania rzeczywistych sekretów do buildera. |
| A12 | CI obejmuje Python/JavaScript CC, kontrole sekretów/zależności, Mermaid, kompletność źródeł i K4. Konfiguracja pipeline jest wdrożona; wykonanie dla zatwierdzonego commitu pozostaje bramką wydania. |
| A13 / P15 | Trwałe próby, UNKNOWN, niezmienne referencje, blokada drugiego klucza/metody i bankowa ochrona tożsamości zakupu. Własny importer ma plan, potwierdzany zapis, checkpoint i wznowienie; przenosi tylko potwierdzone wyniki, bez kasowania historii i nowego obciążenia. |

Dodatkowo zakończono następujący zakres:

- **K1:** keyring TOTP, plan/wykonanie partii z trwałym checkpointem, dwufazową weryfikację odszyfrowania kopii i syntetyczny test kopia → odtworzenie → rotacja → logowanie HTTP. Właściciel wcześniej potwierdził kopie, zachowanie kluczy, rotację i poprawne logowanie w przygotowanym środowisku. To potwierdzenie operatora, nie ponowny odczyt jego kluczy lub rotacja na jego danych przez agenta.
- **K2/K3:** współdzielone sesje/wyzwania, atomową jednorazowość TOTP, równoległe potwierdzenia i odtwarzanie sesji/draftów po awarii. Są testy dwóch rzeczywistych JVM ze wspólnymi testowymi PostgreSQL/Redis; nie zastępują akceptacji dowolnego produkcyjnego load balancera.
- **K4:** jawny rejestr `scripts/ci/security-policy.json`: **94 operacje banku i 52 sklepu**, w tym wszystkie 85/34 operacje własnych kontrolerów oraz wskazane ścieżki frameworka/filtrów. Nowy kontroler bez zgodnej polityki lub jawnej metody HTTP blokuje test. Sprawdzane są role, niedokończone logowanie, nieaktywne/zablokowane konta, CSRF, zakresy kluczy, tożsamość sprzedawcy i podpisy Stripe. Testy prawdziwych filtrów z testowym interceptorem są oddzielone od obowiązkowych testów rzeczywistych kontrolerów/usług i właścicieli danych; samo przejście filtra nie dowodzi poprawności biznesowej. W CC jest kafelek **K4 — kontrola uprawnień i CSRF**, DEV/TEST, z potwierdzeniem i izolowanymi bazami.
- **K7:** uzgodniony profil DEV/TEST: **16 równoległych klientów, 120 s, p95 ≤ 2 s, p99 ≤ 5 s, zero błędów technicznych i naruszeń księgowania**. Raport obejmuje czasy, błędy, blokady/pulę DB, JVM, próbki hosta widocznego dla WSL/CI i kontenerów PostgreSQL/Redis oraz opóźnienie schedulera. Nie jest certyfikacją wydajności produkcyjnej.
- **Zakupy i sprawy w CC/sklepie:** dowody, audytowane decyzje, bieżące zakupy / do wyjaśnienia / historia, bezpieczną rezygnację i zamknięcie sprawy po potwierdzeniu braku możliwości obciążenia, idempotentne żądania i zachowanie historii. V14–V17 sklepu rozwijają te mechanizmy addytywnie. Zamknięcie bez rozstrzygnięcia zachowuje UNKNOWN; nie jest finansowym CANCELLED ani dowodem braku zapłaty.
- **Pozostałe usprawnienia CC:** graficzne operacje DEV/TEST, edycję plików/sekretów, konta banku i sklepu, kontrolowane naprawy konfliktów, historię/stronicowanie, automatyczne otwieranie nowego zadania, PL/EN i skróty do usług. Pod „Otwórz sklep” jest **Monitoring → Prometheus / Grafana / Alertmanager**; wybór otwiera nową kartę, nie uruchamia usług i zachowuje rozwinięcie po odświeżeniu pulpitu.
- **Czytelność kodu:** fasadę `CardService` nad wydzielonymi usługami odczytu, cyklu życia, płatności i walidacji, podział kontrolerów profilu oraz orkiestrację potwierdzania przelewu. Zachowano granice transakcji. Dalszy podział dużych modułów CC jest możliwym zadaniem utrzymaniowym, nie automatycznie brakującą naprawą bezpieczeństwa.

### Testy awarii i aktualne dowody

Nieaktualne jest ogólne stwierdzenie o braku testu rzeczywistego banku z utratą HTTP, zabicia procesu lub fizycznego przerwania połączenia PostgreSQL. Są zaimplementowane i mają zachowane wyniki PASS:

- `RealBankTransportFaultIT.committedRealBankDebitWithLostShopReplyBlocksNewKeyAndReconciles`: zapakowany bank, rzeczywiste księgowanie, utrata odpowiedzi przez kontrolowane proxy, UNKNOWN w sklepie, blokada nowej próby i odzyskanie wyniku bez drugiego obciążenia.
- `SharedSessionHttpIT.processKilledAfterLedgerInsertBeforeCommitLeavesNoPartialTransfer`: zabicie procesu przed commit w kontrolowanej granicy, rollback, restart, retry oraz kolejna utrata procesu po zatwierdzonym sukcesie bez podwójnej księgi.
- `AuditPaymentConcurrencyIT.physicallyTerminatedPostgresConnectionBeforeCommitRollsBackAndSameKeyRecovers`: zakończenie konkretnej sesji jednorazowego PostgreSQL przed `commit()`, brak częściowego księgowania i odzyskanie tym samym kluczem. Jest też osobny test utraty potwierdzenia po udanym commit.
- `authorizationExpiryRejectAndApprovalInEverySequentialOrderPreserveFinalLedger`: sześć kolejności approve/reject/expiry; osobno są wybrane przebiegi równoległe. Nie dowodzi to wszystkich możliwych zegarów i przeplotów.
- `PaymentAttemptConcurrencyIT.paidCheckoutWinsCancellationAndDelayedSignedWebhookIsIdempotent`: opłacony Checkout wygrywa z rezygnacją, późniejszy podpisany webhook i replay zachowują sukces, referencję i jedną próbę, bez nowego obciążenia. Provider jest kontrolowanym substytutem, nie rzeczywistym kontem Stripe TEST.

Macierz P01–P16 ma implementację i regresje kluczowych uzgodnionych scenariuszy, w tym migracji, współbieżności i powyższych awarii. **Nie oznacza to wyczerpania każdej kombinacji walut, statusów, procesów, zegarów i punktów awarii.** Dalsze warianty należy dodawać pod nowe funkcje/wymagania wdrożeniowe; niewykonanych przypadków nie oznacza się jako PASS.

| Dowód | Wynik i granica potwierdzenia |
|---|---|
| K4, ponownie wykonane 23.10 | **PASS: 53 testy banku + 36 sklepu, 817 + 437 kontroli granicy HTTP; 0 niesklasyfikowanych operacji i 0 pominiętych wymaganych testów.** `target/audit-extended/k4/run-79199a5997a748fb985ef3ad2de8e31b/k4-report.json`; log: `target/audit-closure-2026-10-23/k4.log`. |
| CC Python, ponownie wykonane 23.10 | 278 testów: **258 zaliczonych, 20 świadomie pominiętych opt-in**; pominięcie nie zalicza testu live. `target/audit-closure-2026-10-23/cc-python.log`. |
| CC JavaScript / helpery CI, ponownie wykonane 23.10 | **17 zestawów JS PASS / 9 testów Python PASS.** `target/audit-closure-2026-10-23/cc-js.log`, `ci-helpers.log`. |
| Wcześniejsze awarie/importer, niepowtarzane dziś w całości | `target/audit-extended/payment-bank/bank-failsafe-reports/`: 57 scenariuszy `AuditPaymentConcurrencyIT` i 10 importera. `security/`: 12 scenariuszy dwóch JVM i 12 rezygnacji; `transport/`: 1 rzeczywisty transport; `totp/`: 1 odtworzenie/rotacja/logowanie. Bez błędów/pominięć w tych raportach. Zestawy częściowo się pokrywają — nie sumować jako niezależnej pełnej regresji wykonanej dziś. |
| K7 — zachowany raport DEV/TEST | `target/audit-extended/load/cc-transfer-load-report.json`: `DEV_TEST_ACCEPTANCE_PASS`, integralność spełniona, p95 **203 ms**, p99 **255 ms**, błędy/timeouty/deadlocki **0**, opóźnienie schedulera **177 ms**. Generator współdzieli JVM z bankiem; host ma granice widoczności WSL/CI. Nie ponawiano obciążenia na działającym DEV. |
| Mermaid, odczytowo 23.10 | **23 poprawne bloki, 2 jawne wyjątki historyczne, 0 nowych błędów składni.** Wyjątki dotyczą `Bank-app2.md` i `docs/Bank-app.md` z `scripts/ci/mermaid/deferred.json`; nie są poprawnymi diagramami. Końcowy log: `target/audit-closure-2026-10-23/mermaid-node24.log`. Użyto dostępnego Node 24.19.0; Node 18 w WSL jest za stary dla obecnego walidatora. Bez instalacji zależności i zmian rysunków. |
| Bramka wydania, odczytowo 23.10 | **NOT_READY_UNCOMMITTED_SOURCE**, oczekiwany kod 2: 763 publiczne pliki kandydata, **212 nieobecnych w HEAD, 130 zmienionych**, 0 usuniętych śledzonych źródeł w zakresie bramki. `target/audit-closure-2026-10-23/release-report.json`. To otwarte wymaganie wydania, nie błąd testów CC. |
| Akceptacja właściciela | W rozmowie potwierdzono pozytywne testy unit/integration, Stripe TEST, kopie/DR, rotację, logowanie oraz późniejsze wstępne testy nowych funkcji CC. Wykonanie migracji historii zgłoszono z `completed:51`. Są to potwierdzenia operatora, nie ponowny odczyt baz/sekretów przez agenta i nie dowód rozstrzygnięcia każdej historycznej sprawy. |

W tej sesji nie wykonywano nowych operacji na kontach, rzeczywistego Stripe, migracji baz operatora, rotacji kluczy ani aktualizacji działających aplikacji. Nie wykonywano nowego pełnego SCA ani skanu całej historii Git; wcześniejsze wyniki i konfiguracja kontroli CI nie zastępują skanu zatwierdzonego wydania.

### Co pozostaje do wykonania lub wdrożenia po tym etapie

1. **K5/K6 — jawne TODO, poza zamkniętym zakresem DEV/TEST.** Sprawdzić rzeczywistą topologię proxy/firewall/DNS/TLS, filtrowanie Forwarded, brak bezpośredniego dostępu do backendów i uprawnienia do hosta/WSL. CC pozostaje lokalnym panelem; token z bootstrap nie jest logowaniem osoby. Nie wystawiać go zdalnie jako gotowego panelu wieloużytkownikowego. Jeżeli taki dostęp będzie wymagany, potrzebny jest osobny projekt uwierzytelnienia, RBAC, audytu i granicy sieci — nie wdrożono go przy okazji tego audytu.
2. **Zatwierdzony commit i świeże CI — otwarta bramka wydania.** Przejrzeć nowe/zmienione pliki i dołączyć potrzebne źródła, testy, migracje, skrypty i workflow, bez runtime/sekretów/lokalnych artefaktów. Z zatwierdzonego commitu uruchomić czysty checkout, buildy obu modułów oraz pełny pipeline z regresją, K4, skanami i kontrolą migracji. Zielony CC lub eksport źródeł nie potwierdza, że GitHub zawiera te pliki. Bez automatycznego stagingu, commitu i pusha.
3. **Końcowa synchronizacja dokumentacji.** Ustalić kanoniczne wersje repo/Obsidian i sprawdzić opis transakcji, prób, rezygnacji, V17, K4 i nowych operacji CC. Naprawić dwa historyczne bloki Mermaid i dopiero po walidacji usunąć ich wyjątki; odtworzyć potrzebne PNG z aktualnych źródeł. Wcześniejsze aktualizacje części diagramów nie usuwają tych wyjątków w repo. W tej sesji aktualizowano tylko niniejszy raport, nie plik `Bank-app` ani inne notatki banku.
4. **Akceptacja konkretnego wdrożenia i dostawców.** Przed kolejnym wydaniem potwierdzić faktyczne wersje JAR/schematów, odtwarzalność świeżych kopii i zachowane klucze. Na docelowym TEST powtórzyć potrzebne scenariusze bank/sklep/Stripe, w tym anulowanie i spóźnione zdarzenia, oraz rzeczywiste dostarczenie SMTP, jeśli będzie używane. Wcześniejsze pozytywne próby operatora nie akceptują automatycznie każdej późniejszej wersji. Nie ma potrzeby ponownie implementować istniejącego importera lub testu K1.
5. **Wydajność docelowa.** K7 jest zaliczone dla uzgodnionego DEV/TEST. Staging/PROD wymaga własnego wolumenu, czasu, zasobów i SLO; wtedy dostosować profil i wykonać pomiar na odpowiednim hoście, również dla ruchu innego niż syntetyczne przelewy bez PIN/2FA. Nie jest to brak obecnego kafelka K7.
6. **Nierozstrzygnięcia płatności — obsługa operacyjna, nie kasowanie historii.** Indywidualnie uzgadniać UNKNOWN/CONFLICT/REFUND_REVIEW z bankiem/operatorem. Brak próby lub znalezionego księgowania, dawny FAILED, wiek sprawy czy decyzja administracyjna nie dowodzą możliwości ustawienia CANCELLED. Zamknięcie bez rozstrzygnięcia i Historia są dopuszczalnym workflow z zachowaniem niepewności. Nie usuwać starych zamówień ani nie ponawiać ich nowym kluczem dla obejścia blokady.

Opcjonalne usprawnienia, a nie obowiązkowe brakujące naprawy tego etapu: klientowski podgląd/odtworzenie archiwalnego snapshota koszyka, dalsza refaktoryzacja dużych modułów CC i rozszerzanie fault injection pod nowe wymagania. Nie dodano ich jako niejawnych warunków ukończenia obecnego zlecenia.

### Porządkowanie projektu wykonane 23.10.2026

Sprawdzono dokładne cele wewnątrz repo, brak dowiązań/junctions w usuwanych katalogach, brak śledzonych plików i rodzaj zawartości. Dla omyłkowego cache Maven nie znaleziono odwołań w konfiguracji projektu poza regułą ignorowania Git; pliki pochodziły najpóźniej z 17.08.2026. Kontrola procesów nie wskazała używającej go aplikacji poza samym poleceniem kontrolnym.

| Usunięty element | Zakres i odzyskanie |
|---|---|
| Dosłowny katalog `${env.USERPROFILE}/.m2/repository` wraz z pustymi katalogami nadrzędnymi tego omyłkowego cache | **2505 plików, 166 673 696 B** zależności Maven. To nie był rzeczywisty profil użytkownika ani jego właściwy `~/.m2`; cache można ponownie pobrać. Nie zawierał plików spoza `repository`. |
| `scripts/ci/__pycache__`, `scripts/control-center/web/__pycache__` | **29 plików `.pyc`, 632 810 B**; odtwarzalny bytecode. Źródła, testy i historia pozostały. Regresję wykonano z `python -B`. |
| `tmp/control-center`, `tmp/maven-test`, `tmp/pdfs` | Wyłącznie puste katalogi testowe; mogą być odtworzone przez narzędzia. Nie usuwano całego `tmp`. |
| Rootowe `debug.log`, `replay_pid18424.log`, `replay_pid31300.log` | **3 logi, 1 709 113 B**. Najpierw archiwum `.novabank/cleanup-archive/2026-10-23/jvm-browser-diagnostics.zip`; potwierdzono liczbę wpisów, rozmiary, SHA-256 każdego wpisu i niezmienność oryginałów. Potem usunięto oryginały. Odzyskanie z ZIP; treści nie wypisywano. |

Łącznie usunięto **2537 plików odtwarzalnych lub zarchiwizowanych** oraz puste katalogi. ZIP zajmuje 198 434 B; odzyskano netto **168 817 185 B, około 161 MiB**. To bilans wymienionych artefaktów, bez odliczania nowych raportów weryfikacji.

**Celowo pozostawiono:** oba `target` i JAR-y, raporty regresji/skanów wraz z wcześniejszymi próbami, źródła i wszystkie migracje Flyway, `.novabank` z sekretami/kopiami/historią, kopie `.idea.backup-*`, dokumenty/szkice `Prompt 2.md` / `Bank-app*.md` (także pusty szkic do rozstrzygnięcia wersji kanonicznej), używane skrypty zaplecza terminalowego, podglądy dokumentacyjne i autorskie `tmp/vault-hsin`. Nazwa „tmp”, „preview”, „CLI” lub stan untracked nie dowodzą zbędności. Nie czyszczono Docker volumes, baz, Redis ani istniejących logów aplikacji w `.novabank`.

**Wniosek końcowy:** etap naprawczy DEV/TEST jest zamknięty z powyższym zakresem, dowodami i odroczeniami. Wyniki CC potwierdza lokalna regresja; K5/K6, zatwierdzone wydanie i końcowa dokumentacja pozostają jawną listą dalszych prac, a nie deklaracją wykonania ich przy zamknięciu audytu.
