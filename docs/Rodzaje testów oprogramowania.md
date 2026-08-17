# Rodzaje testów oprogramowania

W testach bardzo łatwo pomieszać pojęcia, bo unit, integration i E2E
opisują przede wszystkim zakres testowanego systemu, natomiast
smoke, regression, sanity, performance itd. opisują cel albo sposób
zbudowania zestawu testów. Dlatego np. test E2E może być jednocześnie
testem smoke i testem regresyjnym. ISTQB również rozróżnia poziomy
testowania, takie jak component, integration, system i acceptance, od innych
klasyfikacji testów.

## 1. Ogólna mapa rodzajów testów

> Diagram Mermaid (etykiety odczytane z PDF):
TESTY OPROGRAMOWANIA
Poziom / zakres testu Cel testu Testy niefunkcjonalne
Unit / jednostkowe Component / Slice Integration System End-to-End Acceptance / UAT Smoke Sanity Regression Re-test / Confirmation Performance Load Stress Security Usability Reliability
To rozróżnienie jest kluczowe. Nie powinno się myśleć:
Unit → Integration → Smoke → E2E
bo smoke nie jest poziomem znajdującym się między integration i E2E.
Lepszy model to:
POZIOM:
Unit → Component → Integration → System → E2E
CEL / ZESTAW:
Smoke
Regression
Sanity
Performance
Security
...

## 2. Testy jednostkowe - Unit Tests

Test jednostkowy sprawdza mały fragment logiki programu w izolacji:
najczęściej pojedynczą klasę, metodę, funkcję albo niewielki komponent.
W typowej aplikacji Java/Spring Boot test jednostkowy nie powinien wymagać
uruchamiania całego Spring Context, bazy danych, Redis, HTTP czy
zewnętrznego API. Zależności można zastąpić test doubles, np. mockami lub
stubami. Spring sam rozdziela wsparcie dla unit testing i integration testing, a
JUnit zapewnia podstawową infrastrukturę wykonywania testów na JVM.

Przykład:

```java
class TransferServiceTest {
@Test
void shouldRejectTransferWhenBalanceIsTooLow() {
Account account = new Account(new BigDecimal("100.00"));
assertThrows(
InsufficientFundsException.class,
() -> account.transfer(new BigDecimal("150.00"))
);
}
}

```
Tutaj interesuje nas wyłącznie:
saldo = 100
przelew = 150
wynik:
InsufficientFundsException
Nie interesuje nas jeszcze:
HTTP
Spring MVC
PostgreSQL
Redis
autoryzacja
frontend
sieć

### Schemat

> Diagram Mermaid (etykiety odczytane z PDF):
Unit Test
Mock Repository TransferService Wynik
Mock NotificationService
Unit test odpowiada na pytanie
Czy ten fragment logiki działa poprawnie?

### Zalety

bardzo szybkie,
łatwe do uruchamiania,
łatwo wskazać źródło błędu,
mogą być wykonywane przy każdym commitcie,
dobrze sprawdzają przypadki brzegowe,
nie wymagają dużego środowiska.

### Wady

Największym problemem jest sytuacja:
AccountService ✓
TransferService ✓
Repository ✓
Notification ✓
ale po połączeniu:
AccountService
↓

TransferService
↓
Repository
✗
Każdy element osobno działa, ale współpraca między nimi jest błędna.
I właśnie tutaj zaczynają być potrzebne testy integracyjne.

## 3. Component / Slice Tests

To poziom pośredni, szczególnie popularny w Spring Boot.
Testujesz większą część aplikacji niż pojedynczą klasę, ale nie
uruchamiasz całego systemu.
Przykładowo:

```java
@WebMvcTest(TransferController.class)
class TransferControllerTest {
}

```
Spring Boot może w takim teście uruchomić infrastrukturę MVC potrzebną dla
kontrolera, ale pozostałe zależności zastąpić mockami. MockMvc pozwala
testować kontrolery bez uruchamiania pełnego serwera HTTP.

### Schemat:

> Diagram Mermaid (etykiety odczytane z PDF):
Test Spring MVC TransferController Mock TransferService

Możemy wtedy sprawdzić np.:
POST /api/transfers
↓
JSON request
↓
TransferController
↓
HTTP 201 Created
ale nie testujemy jeszcze:
Controller
↓
Service
↓
Repository
↓
PostgreSQL
Co można testować jako slice?
W Spring Boot bardzo często:
Controller
Repository
JSON serialization
Security
JPA
WebFlux
Slice tests są bardzo użyteczne, ponieważ dają większą pewność niż unit tests,
ale zwykle są szybsze niż pełne testy integracyjne.

## 4. Testy integracyjne - Integration Tests

Test integracyjny sprawdza:
Czy kilka elementów systemu poprawnie ze sobą współpracuje?
ISTQB opisuje integration testing właśnie w kontekście interakcji i interfejsów
pomiędzy komponentami, a system integration testing obejmuje również
interakcję z innymi systemami i usługami zewnętrznymi. IST…
Przykład:

TransferService
↓
TransferRepository
↓
Hibernate
↓
PostgreSQL
Możemy przetestować cały ten fragment.
> Diagram Mermaid (etykiety odczytane z PDF):
Integration Test TransferService TransferRepository Hibernate
PostgreSQL
W takim przypadku baza może być prawdziwa - uruchomiona np. w
Dockerze przez Testcontainers.
Spring Boot oficjalnie wspiera Testcontainers właśnie do przypadków, w których
test integracyjny komunikuje się z rzeczywistym backendem, takim jak MySQL,
MongoDB czy inne usługi uruchomione w kontenerach.
Przykładowo:

```java
@SpringBootTest
@Testcontainers
class TransferIntegrationTest {
@Test
void shouldPersistTransfer() {
// ...
}
}

```
Integration test odpowiada na pytanie
Czy te elementy działają poprawnie razem?
Nie sprawdzasz już tylko:
transfer.calculate()
lecz:
Service
↓
Repository
↓
Hibernate
↓
SQL
↓
PostgreSQL

## 5. Unit vs Integration

Najłatwiej zobaczyć różnicę na przykładzie repozytorium.
Unit
> Diagram Mermaid (etykiety odczytane z PDF):
Test
TransferService
Mock TransferRepository
Repozytorium jest udawane.
Integration

> Diagram Mermaid (etykiety odczytane z PDF):
Test TransferService TransferRepository Hibernate
PostgreSQL
Repozytorium i baza naprawdę działają.
Dlatego możesz mieć sytuację:
Unit Test: PASS
Integration Test: FAIL
Na przykład dlatego, że:
Java:
transferDate
SQL:
transfer_date
jest źle zmapowane.
Unit test tego nie wykryje.

## 6. API Tests

Test API sprawdza publiczny interfejs aplikacji.
Na przykład:

```http
POST /api/transfers

```
Test może wysłać:

```json
{
  "fromAccount": "123",
  "toAccount": "456",
  "amount": 500
}
```
i oczekiwać:

```http
HTTP 201 Created

```
oraz poprawnego JSON-a.

### Schemat:

> Diagram Mermaid (etykiety odczytane z PDF):
API Test HTTP REST Controller Service HTTP Response
API test może być:
integracyjny,
systemowy,
E2E na poziomie API.
To zależy od tego, ile prawdziwego systemu uruchomiliśmy.
Na przykład:
Mock Controller → raczej component test
Controller + Service + DB → integration test

Spring Boot pozwala również uruchamiać testy z rzeczywistym serwerem, np.
przez @SpringBootTest(webEnvironment = RANDOM_PORT) .

## 7. Contract Tests

Contract test sprawdza, czy dwie usługi zgadzają się co do sposobu
komunikacji.
Załóżmy:
BankService
↓
EmailService
BankService oczekuje:

```json
{
 "email": "test@example.com",
 "message": "Transfer completed"
}

```
ale ktoś zmienia EmailService:

```json
{
 "recipient": "test@example.com",
 "content": "Transfer completed"
}

```
Obie aplikacje osobno mogą przechodzić swoje testy.
Ale integracja:
BankService → EmailService
przestaje działać.
Contract test wykrywa właśnie takie problemy.
> Diagram Mermaid (etykiety odczytane z PDF):

Provider
EmailService
HTTP method
Endpoint
Consumer
API Contract
BankService
Request
Response
Schema
Contract testing jest szczególnie ważny przy:
mikroserwisach
zewnętrznych API
Kafka
REST
GraphQL
systemach rozproszonych

## 8. System Tests

System test sprawdza kompletnie zintegrowany system jako całość.
Selenium opisuje system testing jako testowanie kompletnego, zintegrowanego
produktu w środowisku zbliżonym do produkcyjnego.

Przykład:
Spring Boot
+
PostgreSQL
+
Redis
+
Security
+
REST API
wszystko działa razem.
> Diagram Mermaid (etykiety odczytane z PDF):
System Test
Uruchomiona aplikacja
Spring Security REST API Services
PostgreSQL Redis
Frontend nie musi być tutaj koniecznie używany.
Możemy np. testować cały backend przez HTTP.

## 9. E2E - End-to-End Tests

E2E sprawdza pełny scenariusz z punktu widzenia użytkownika lub
zewnętrznego klienta.
Przykład:
Użytkownik loguje się → wybiera rachunek → wykonuje przelew → przelew
zapisuje się w bazie → saldo zostaje zmienione → użytkownik widzi
potwierdzenie.

> Diagram Mermaid (etykiety odczytane z PDF):
Frontend Spring Boot API Security TransferService PostgreSQL NotificationService
Użytkownik
Wpisuje dane przelewu
POST /transfers
Sprawdź użytkownika
OK
createTransfer()
zapisz przelew
zapisano
wyślij powiadomienie
transfer created
HTTP 201
Przelew wykonany
Frontend Spring Boot API Security TransferService PostgreSQL NotificationService
Użytkownik
Test E2E może przejść przez:
Browser
↓
Frontend
↓
HTTP
↓
Backend
↓
Security
↓
Business logic
↓
Database
Selenium jest przykładem narzędzia automatyzującego przeglądarkę i jest
powszechnie używane do takich testów webowych. Jednocześnie jego
dokumentacja zwraca uwagę, że testy użytkownika wykonywane przez
przeglądarkę są stosunkowo kosztowne i warto część przypadków przenosić na
niższe poziomy testów.

## 10. Acceptance Tests / UAT

Acceptance Testing odpowiada na inne pytanie:

Czy system spełnia wymagania biznesowe i jest gotowy do
zaakceptowania przez użytkownika/biznes?
ISTQB definiuje acceptance testing jako poziom skupiony na ustaleniu, czy
system powinien zostać zaakceptowany; w aktualnym syllabusie wskazuje
również na potwierdzenie potrzeb biznesowych oraz gotowości do wdrożenia.

Przykład wymagania:
Klient posiadający 1000 zł może wykonać przelew 500 zł, a po operacji saldo
powinno wynosić 500 zł.
Acceptance test sprawdza właśnie to wymaganie.
Nie musi interesować go, czy wewnętrznie aplikacja używa:
Hibernate
JPA
Redis
Kafka
REST
Interesuje go:
Czy wymaganie biznesowe zostało spełnione?

## 11. Smoke Tests

I tutaj dochodzimy do testów, które często są błędnie umieszczane jako kolejny
poziom.
Smoke test oznacza mniej więcej:
Czy najważniejsze funkcje systemu w ogóle działają, czy nie ma sensu
kontynuować dalszych testów?
Przykładowy smoke suite aplikacji:
✓ aplikacja się uruchamia
✓ można się zalogować
✓ baza odpowiada
✓ GET /accounts działa

✓ można utworzyć przelew
✓ można się wylogować
Nie sprawdzamy wszystkich możliwych wariantów.
Smoke
> Diagram Mermaid (etykiety odczytane z PDF):
Deployment
Czy aplikacja startuje?
TAK
Czy działa logowanie?
TAK
NIE Czy główne API działa?
NIE TAK
NIE Czy działa DB?
NIE TAK
STOP
build/deployment Kontynuuj pełne testy
uszkodzony
Smoke tests świetnie nadają się jako quality gate po deploymentcie;
aktualne materiały ISTQB dotyczące DevOps również wskazują smoke tests jako
mechanizm szybkiej walidacji deploymentu przed dalszą regresją.

## 12. Sanity Tests

Sanity testing jest bardzo podobny do smoke testing, ale zwykle ma węższy
zakres.
Załóżmy, że poprawiono:
naliczanie prowizji za przelew
Nie testujesz całego systemu.
Sprawdzasz przede wszystkim:
przelew
↓
prowizja
↓
saldo
Czyli:
Smoke:
"Czy aplikacja zasadniczo żyje?"
Sanity:
"Czy zmieniony fragment zasadniczo działa?"
W praktyce nazewnictwo smoke/sanity różni się pomiędzy organizacjami,
dlatego ważniejsza od samej etykiety jest jasno zdefiniowana zawartość
konkretnego test suite.

## 13. Regression Tests

Regression testing sprawdza:
Czy nowa zmiana nie zepsuła czegoś, co wcześniej działało?
Przykład.
Dodajesz:
przelewy zagraniczne
Nowa funkcjonalność działa:

SEPA → PASS
ale przez zmianę w TransferService zepsułeś:
przelewy krajowe → FAIL
To właśnie regresja.
> Diagram Mermaid (etykiety odczytane z PDF):
Nowa funkcja ✓ działa
Działający system Nowa zmiana ✓ nadal działają
Stare funkcje Regression Tests
✗ regresja
Ważne
Regression test nie oznacza konkretnego poziomu.
Regression suite może zawierać:
Unit Tests
Integration Tests
API Tests
E2E Tests
Czyli:
> Diagram Mermaid (etykiety odczytane z PDF):

Regression Suite
Unit Integration API E2E

## 14. Re-test / Confirmation Test

To coś innego niż regression.
Masz bug:
BUG-123
Przelew 100 zł
powoduje pobranie 200 zł
Programista naprawia problem.
Confirmation
Sprawdzasz:
Czy BUG-123 rzeczywiście został naprawiony?
Regression
Sprawdzasz:
Czy naprawiając BUG-123 nie zepsuliśmy innych przelewów?
Czyli:
Re-test → czy poprawka działa?
Regression → czy poprawka czegoś innego nie zepsuła?

## 15. Performance Tests

Tutaj przestajemy przede wszystkim pytać:
Czy system zwraca poprawny wynik?
i zaczynamy pytać:
Jak system zachowuje się pod określonym obciążeniem?
Selenium samo odradza używanie WebDrivera jako głównego narzędzia
performance testing, ponieważ nie jest do tego zoptymalizowane.
Performance testing jest kategorią szerszą.

### Load Test

Test przewidywanego obciążenia.
Przykład:
100 użytkowników
500 użytkowników
1000 użytkowników
Sprawdzamy:
response time
throughput
CPU
RAM
DB connections
error rate

## 16. Stress Test

Tutaj celowo przekraczamy normalne granice.
100 users ✓
500 users ✓
1 000 users ✓
5 000 users ✓
10 000 users ✗
Pytanie brzmi:
Gdzie system się załamie?

oraz:
Jak zachowa się po przeciążeniu?

## 17. Spike Test

Nagły wzrost ruchu.
> Diagram Mermaid (etykiety odczytane z PDF):
100 użytkowników 110 120 5000 użytkowników 5100 150
Symuluje np. sytuację:
normalny ruch
↓
nagła promocja
↓
20x więcej użytkowników

## 18. Soak / Endurance Test

System działa przez długi czas:
1 godzina
6 godzin
24 godziny
72 godziny
Szukasz problemów takich jak:

memory leak
wyczerpywanie connection pool
narastające kolejki
problemy z cache
degradacja wydajności

## 19. Security Tests

Sprawdzają odporność systemu pod kątem bezpieczeństwa.
Przykładowe obszary:
Authentication
Authorization
Session management
Input validation
SQL Injection
XSS
CSRF
IDOR
rate limiting
ochrona danych
Przykład:
User A:
GET /accounts/123
→ 200 OK
ale:
User A:
GET /accounts/456
gdzie 456 należy do User B
→ powinno być 403
Tu logika biznesowa może działać idealnie, ale aplikacja nadal może mieć
poważny problem bezpieczeństwa.

## 20. Piramida testów

W praktyce bardzo użyteczną zasadą jest posiadanie dużej liczby tanich
testów niskopoziomowych i znacznie mniejszej liczby kosztownych
E2E. Dokumentacja Selenium również zaleca rozważenie lżejszych testów
zamiast realizowania wszystkiego przez testy użytkownika w przeglądarce.

> Diagram Mermaid (etykiety odczytane z PDF):
Unit Tests
bardzo dużo
bardzo szybkie
Integration / Component
średnia liczba
E2E
mało
najwolniejsze i najdroższe
W uproszczeniu:
/ E2E \
/ \
/ System \
/-----------\
/ Integration \
/---------------\
/ Unit Tests \
/___________________\
Nie chodzi jednak o matematyczną proporcję typu:
70% unit
20% integration
10% E2E

która pasowałaby do każdego projektu.
Chodzi o zasadę:
Błąd testuj na najniższym poziomie, który potrafi wiarygodnie go
wykryć.
Jeżeli regułę:

```java
amount > balance
```
można sprawdzić unit testem w 5 ms, nie ma sensu sprawdzać wszystkich jej
wariantów przez Selenium i pełny E2E.

## 21. Jak te testy mogą wyglądać w CI/CD

Przykładowy pipeline:
> Diagram Mermaid (etykiety odczytane z PDF):
PASS Release Production Post-deploy Smoke
PASS Regression Critical E2E
PASS Package Deploy TEST Smoke Tests FAIL STOP
PASS Build Integration Tests FAIL STOP
Commit Unit Tests FAIL STOP
FAIL STOP
To nie jest jedyny poprawny pipeline, ale dobrze pokazuje ideę: najpierw
szybkie i tanie testy, a kosztowniejsze zestawy dopiero później. Smoke może
być quality gate bezpośrednio po wdrożeniu.

## 22. Przykład jednego feature'u przez wszystkie

poziomy

Weźmy funkcję:
Wykonanie przelewu bankowego.
Unit
TransferService
1000 zł - 200 zł = 800 zł
Testujesz samą logikę.
Component
POST /transfers
JSON
↓
TransferController
↓
HTTP 201
Service może być mockiem.
Integration
TransferService
↓
TransferRepository
↓
Hibernate
↓
PostgreSQL
Sprawdzasz zapis prawdziwej encji.
API/System
HTTP POST
↓
Spring Boot

↓
Security
↓
Service
↓
PostgreSQL
↓
HTTP 201
Aplikacja naprawdę działa.
E2E
Login
↓
Strona przelewu
↓
Wprowadzenie danych
↓
Potwierdzenie
↓
Backend
↓
DB
↓
Nowe saldo widoczne w UI
Smoke
Sprawdzasz tylko:
login działa
przelew działa
saldo można odczytać
Regression
Po zmianie kodu uruchamiasz np.:
przelew krajowy
przelew zagraniczny
anulowanie przelewu
historia

saldo
limity
aby upewnić się, że nic starego nie zostało zepsute.
Performance
1000 równoczesnych zleceń przelewu
Security
Czy User A może wykonać przelew
z rachunku User B?

## 23. Najważniejsza mapa całego tematu

> Diagram Mermaid (etykiety odczytane z PDF):
REGRESSION
Kod
Czy niczego nie zepsuliśmy?
UNIT
Czy moja logika działa?
SANITY COMPONENT
Czy zmieniony obszar Czy fragment aplikacji
działa? działa?
SECURITY
PERFORMANCE INTEGRATION
SMOKE Czy system jest
Jak system działa pod Czy komponenty
Czy system w ogóle żyje? odpowiednio
obciążeniem? współpracują?
zabezpieczony?
SYSTEM
Czy cały system działa?
E2E
Czy pełny proces
użytkownika działa?
ACCEPTANCE
Czy biznes akceptuje
rozwiązanie?

To jest diagram, który najlepiej pokazuje dlaczego smoke/regression nie
należy wrzucać do jednego szeregu z unit/integration/E2E.

## 24. Ściąga - co właściwie sprawdza każdy test?

Rodzaj Główne pytanie
Unit Czy pojedyncza logika/klasa działa?
Component / Slice Czy konkretny fragment aplikacji działa?
Integration Czy komponenty poprawnie współpracują?
API Czy interfejs API zachowuje się poprawnie?
Contract Czy dwa systemy zgadzają się co do kontraktu
komunikacji?
System Czy cały zintegrowany system działa?
E2E Czy pełny proces użytkownika działa od początku
do końca?
Acceptance / UAT Czy rozwiązanie spełnia wymagania biznesowe?
Smoke Czy podstawowe funkcje w ogóle działają?
Sanity Czy zmieniony obszar działa po zmianie?
Regression Czy nowa zmiana nie zepsuła starej
funkcjonalności?
Re-test Czy konkretny naprawiony bug rzeczywiście
zniknął?
Load Czy system wytrzymuje spodziewane obciążenie?
Stress Gdzie znajduje się granica systemu?
Spike Jak system reaguje na nagły skok ruchu?
Soak Czy system jest stabilny przez długi czas?

Rodzaj Główne pytanie
Security Czy mechanizmy bezpieczeństwa działają
poprawnie?
Mermaid nadaje się bardzo dobrze do takich diagramów, ponieważ definiuje
diagram tekstowo i obsługuje m.in. flowcharty, diagramy sekwencji oraz state
diagrams. Powyższe diagramy używają standardowej składni flowchart i
sequenceDiagram opisanej w oficjalnej dokumentacji.
W kontekście Java + Spring Boot
Dla typowej aplikacji sensowny zestaw technologicznie wyglądałby mniej więcej
tak:
JUnit + Mockito
↓
UNIT
@WebMvcTest / MockMvc
↓
COMPONENT / API SLICE
@SpringBootTest
↓
INTEGRATION
Testcontainers
PostgreSQL / Redis / Kafka
↓
REAL INTEGRATION
@SpringBootTest + real HTTP
↓
SYSTEM / API
Selenium / Playwright
↓
E2E
Spring Boot oficjalnie wspiera zarówno testowanie warstw z MockMvc , testy z
pełnym serwerem, jak i integrację z Testcontainers dla prawdziwych usług
backendowych.
W kolejnym kroku mogę rozpisać ten temat już stricte pod architekturę
Java + Spring Boot: Controller → Service → Repository → PostgreSQL → Redis →
Security , pokazując dokładnie które klasy powinny mieć unit test, które

integration test, gdzie użyć @WebMvcTest , @SpringBootTest , Mockito i
Testcontainers oraz czego nie mockować. To byłoby praktyczne
przełożenie powyższej teorii na strukturę projektu.
