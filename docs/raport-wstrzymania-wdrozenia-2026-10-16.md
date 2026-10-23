# Wstrzymanie wdrożenia — decyzja o tożsamości sprzedawcy w A13

Data zdarzenia: 16.10.2026. Aktualizacja: 17.10.2026. Status: **rozstrzygnięte — użytkownik wybrał A (trwały merchantId)**.

Poniższa treść zachowuje historyczny stan z chwili wstrzymania. Po odpowiedzi „A” wznowiono implementację oraz testy na danych syntetycznych. Aktualne wyniki i ograniczenia są w [raporcie wdrożenia](wdrozenie-audytu-2026-10-16.md). Nie przypisano automatycznie istniejących danych do sprzedawcy; rzeczywisty backfill wymaga jawnej mapy właścicieli i osobnego zatwierdzenia. Ten raport nie oznacza już oczekiwania na wybór A/B.

## Historyczny zakres zlecenia i stan prac przed decyzją

Zlecono wdrożenie [planu kontroli i napraw](<C:/Dysk D/stuff/Bank-demo/docs/plan-kontroli-przed-wdrozeniem-2026-10-16.md>), z dodatkowym wymaganiem A05: atomowy `UPDATE ... WHERE used_at IS NULL` w tej samej transakcji. Diagramy mają pozostać bez zmian. W razie wątpliwości prace mają zostać zatrzymane i przekazane do rozstrzygnięcia.

Przejrzano bieżące źródła i plan. Sprawdzono dostępność JDK i Dockera w WSL; nie uruchamiano testów, kontenerów ani migracji. Nie modyfikowano kodu aplikacji, diagramów, sekretów ani danych. Istniejące lokalne zmiany pozostawiono nienaruszone. Jedyny nowy plik tej próby wdrożenia to ten raport.

## Zdarzenie wymagające decyzji

Plan w etapie 0 wymaga ustalenia niezmiennej tożsamości zakupu i sprzedawcy. A13 ma chronić przed ponownym obciążeniem tego samego zakupu również przy innym kluczu idempotencji i zmianie metody płatności.

W aktualnym kontrakcie autoryzacji `PaymentAuthorizationRequest` występują `shopOrderId`, `shopCustomerId` i `merchantAccountNumber`, ale nie odrębny `merchantId`: [PaymentAuthorizationRequest.java](<C:/Dysk D/stuff/Bank-demo/src/main/java/org/demo/bankdemo/payment/authorization/PaymentAuthorizationRequest.java:12>). Również kontrakt księgowania używa numeru rachunku sprzedawcy: [PaymentRequest.java](<C:/Dysk D/stuff/Bank-demo/src/main/java/org/demo/bankdemo/payment/PaymentRequest.java:19>). Sklep zapisuje rachunek przy utworzeniu zamówienia: [OrderRepository.java](<C:/Dysk D/stuff/Bank-demo/shop-app/src/main/java/org/demo/shopapp/order/OrderRepository.java:40>).

Nie można bez decyzji uznać za równoważne dwóch reguł:

1. „Ten sam rachunek sprzedawcy i numer zamówienia oznaczają ten sam zakup”.
2. „Ten sam sprzedawca i numer zamówienia oznaczają ten sam zakup, także po zmianie jego rachunku”.

Różnica wpływa na ograniczenia unikalności w banku, migrację istniejących operacji i kontrakt bank–sklep. Nie jest wyłącznie wyborem nazwy klasy.

## Warianty do rozstrzygnięcia

| Wariant | Zasada | Konsekwencja |
|---|---|---|
| **A — zalecany docelowo** | Trwały `merchantId`, niezmienny przy rotacji klucza API lub zmianie rachunku; tożsamość zakupu: sprzedawca + numer zamówienia + rodzaj operacji | Potrzebne powiązanie uwierzytelnionego klienta API z tym sprzedawcą oraz jawne przypisanie dotychczasowych rekordów. `merchantId` nie może być dowolnie zaufaną wartością podaną przez klienta. Nie wolno użyć zmiennego ID klucza API jako tożsamości biznesowej |
| **B — ograniczony do obecnego modelu** | Tożsamość zakupu: rachunek sprzedawcy + numer zamówienia + rodzaj operacji | Mniejsza zmiana kontraktu, ale inny rachunek oznacza inną tożsamość w tej ochronie bankowej. Przepięcie rachunku/historycznych operacji wymaga osobnej kontrolowanej procedury; nie można deklarować pełnej ochrony przy dowolnej zmianie rachunku |

W obu wariantach sklep ma blokować nową próbę dla nierozstrzygniętego zamówienia, zachowywać historię i obsługiwać UNKNOWN. Zwrot pozostaje osobną operacją powiązaną z pierwotnym sukcesem. Żaden wariant nie pozwala automatycznie usuwać lub scalać historycznych kolizji.

## Pytanie zadane przed rozstrzygnięciem (odpowiedź: A)

Czy wdrożyć **A: trwały merchantId i jego powiązanie z klientem API**, czy **B: deduplikację w zakresie obecnego rachunku sprzedawcy z opisanym ograniczeniem**?

Dla wariantu A trzeba następnie jawnie określić przypisanie istniejącego sklepu i operacji do sprzedawcy. Przy niejednoznacznych danych migracja ma przerwać się z raportem, bez zgadywania właściciela.

## Pozostałe ustalenia

- A05 jest jednoznaczne i nie wymaga zmiany wybranej techniki: warunkowy atomowy UPDATE z kontrolą liczby zmienionych wierszy, razem ze zmianą hasła w jednej transakcji. Jeszcze nie zaimplementowano.
- Diagramy Mermaid oraz PNG pozostają bez zmian.
- Wstrzymanie następuje przed pierwszą zmianą implementacji, zgodnie z instrukcją użytkownika. Nie oznacza zakończenia A03/A05/A13 ani pozostałych kontroli.
