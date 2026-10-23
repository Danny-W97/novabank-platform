# Naprawa harmonogramów WWW — 2026-10-16

Zakres: poprawny WorkingDirectory przy spacjach w ścieżce, weryfikacja konfiguracji i ostatniego wykonania, bezpieczna aktualizacja dwóch jednostek DEV w WSL, test backupu i izolowanego DR. Bez zmiany linger, włączania CLI, danych aplikacji lub usług PROD.

## Postęp

- [x] Poprawka generatora i testy regresji.
- [x] Weryfikacja systemd, bezpieczna aktualizacja i cofnięcie zmian.
- [x] Statusy konfiguracji i wykonania we froncie/API.
- [x] Test izolowany z prawdziwym systemd.
- [x] Kopie jednostek i aktualizacja obu usług WSL.
- [x] Przegląd retencji, backup przez systemd i kontrola wyniku.
- [x] Weryfikacja izolacji DR, wykonanie i kontrola raportu.
- [x] Dokumentacja, regresja i odbiór.

## Wyniki

Kod: oba renderery zachowują spacje w WorkingDirectory, pozostawiając osobne kodowanie argumentów i Environment. Backend odczytuje WorkingDirectory przez D-Bus, weryfikuje własność/powiązanie jednostek, rozdziela instalację, konfigurację i wykonanie. Aktualizacja WWW ma prywatną kopię, atomową podmianę poszczególnych plików, zatrzymanie wyłącznie timera i próbę cofnięcia. Nie jest transakcją odporną na kill/awarię systemu.

Regresja wstępna: 225 testów Python/API/Bash — 213 zaliczonych, 12 opcjonalnych pominiętych (11 SQL + 1 live systemd). Osobny opt-in test z rzeczywistym systemd: oba renderery przeszły próbę pwd w ścieżce ze spacjami i %. Własne jednostki testowe usunięto. Siedem zestawów JS i składnia — OK.

Przegląd przed wdrożeniem: retencja 30 dni, dwie kopie z 2026-10-16, zero katalogów kwalifikujących się do usunięcia. Panel bez aktywnych zadań i procesów aplikacji. Obrazy PostgreSQL/Redis obecne; obrazy Maven/JRE dla pełnego DR wymagają pobrania. Nie odczytywano treści dumpów ani nie wypisywano sekretów.

## Wdrożenie WSL

Backend WWW został łagodnie przeładowany po potwierdzeniu braku zadań i procesów aplikacji. Nie zatrzymywano banku/sklepu z IDE ani baz. Zaktualizowano przez API Centrali:

- Backup: zadanie `16de1e71f7c64b7ab2f7709d4c9e2cd0`; kopia jednostek `.novabank/control-center/web/timer-updates/09fd6e8b092045a081db3d2cb3159b32`.
- DR: zadanie `35f463aed0f7453e97042553247896de`; kopia jednostek `.novabank/control-center/web/timer-updates/208fc514e4a54067a8f026f2bb141a83`.
- Obie konfiguracje po aktualizacji: `VALID`, timery włączone. Nadal backup 02:00, DR niedziela 04:00, katalog `.novabank/backups/dev`, retencja 30 dni. Nie włączano starego CLI, nie zmieniano linger.

## Rzeczywiste wykonanie

Backup przez systemd: zadanie `f61bbc4e477d4dedac72b8756f83079b`, start 2026-10-16 12:38:47 CEST, koniec 12:38:51 CEST, kod 0, stan `SUCCESS`. Próba wykonała nową kopię i test odtworzenia zgodnie z istniejącą procedurą `prune`; przegląd retencji nie wykazał starych kopii do usunięcia.

DR: zadanie `6264219939294255b9ddc39c031ac223`, raport `.novabank/backups/dev/disaster-recovery/20261016T103854Z`. Start 2026-10-16 12:38:53 CEST, koniec 12:43:27 CEST; kod 0, stan `SUCCESS`. Potwierdzono pliki `migrations.status` i `http-tests.status`, oba z wynikiem OK oraz sukces końcowy raportu. Zbudowano obrazy aplikacji, uruchomiono je i testy HTTP na odtworzonych bazach w zasobach z prefiksem `novabank-dr-` i etykietą `novabank.dr-test=true`, bez zastępowania aktualnych baz aplikacji. Po teście brak pozostałych kontenerów i sieci z tą etykietą. Nie wykonywano ogólnego czyszczenia obrazów/wolumenów Docker.

Limity oczekiwania WWW dopasowano do usług: backup 30 min + 1 min bufora; DR 90 min + 1 min bufora. Nie można anulować aktualizacji jednostek ani próby systemd w połowie. Odczyt wykonania podczas próby jest dozwolony; plany migracji pozostają wtedy zablokowane.

## Stan operacyjny po próbach

| Element | Potwierdzony stan |
|---|---|
| Backup WWW | `VALID`, `SUCCESS`, timer enabled/active; następny termin 2026-10-17 02:00 CEST |
| DR WWW | `VALID`, `SUCCESS`, timer enabled/active; następny termin 2026-10-18 04:00 CEST |
| Stary backup CLI | not-found/inactive |
| Stary DR CLI | disabled/inactive |
| Linger | `no`, bez zmiany |

Można zrezygnować z interaktywnego menu CLI. Nie usuwać współdzielonych skryptów; GUI nadal ich używa. Timery są niezależne od otwartej przeglądarki/menu, ale potrzebują działającego WSL i menedżera użytkownika. Nie potwierdzano wykonania po zakończeniu sesji, zamknięciu WSL lub restarcie Windows; to osobny zakres przy `Linger=no`. Serwera WWW nie przenoszono do systemowej usługi/automatycznego startu.

## Odbiór końcowy

- Regresja: **229 testów Python/API/Bash, 217 zaliczonych i 12 opcjonalnych pominiętych**. Osobny rzeczywisty test systemd pwd — OK dla obu rendererów. Nowe testy sprawdzają częściowy błąd drugiego pliku, cofnięcie własnych zmian, odmowę uruchomienia poprzedniego błędnego katalogu, statusy wykonania, alerty bez duplikacji i odczyt podczas próby bez planów migracji.
- **7 zestawów JS**, składnia JS/Bash użyta w przebiegach i kontrola diff — OK. OpenAPI/JSON Schema: wszystkie modele oraz rzeczywista odpowiedź `LegacyState` po wdrożeniu — OK, obie usługi `VALID/SUCCESS`.
- Przeglądarka: podgląd fikcyjny rozróżnia sukces od „Jeszcze nie wykonano”; rzeczywista Centrala pokazuje dwa sukcesy z datami/kodem 0. Układ 390 px ma jedną kolumnę, bez poziomego rozjechania strony. Konsola bez ostrzeżeń/błędów. Zachowano lokalny bordowo-złoty motyw, bez publikacji.
- [Potwierdzenie rzeczywistych wyników](control-center-schedule-verified.jpg), [podgląd statusów demonstracyjnych](control-center-schedule-status-preview.jpg).
- Nie zmieniano kodu biznesowego Java, sekretów, sald, profili PROD ani aktualnych baz banku/sklepu. Pełnych zestawów Java/SQL nie ponawiano; rzeczywisty DR zbudował aplikacje, przeprowadził migracje i testy HTTP na kopiach. Nie wykonywano ogólnego prune Docker ani usuwania poprzednich kopii jednostek.

Control Center pozostawiono uruchomiony pod http://127.0.0.1:18110. Do wczytania nowego frontendu w już otwartej karcie wystarczy odświeżenie. Pomocniczy podgląd i jego fikcyjne dane są sprzątane niezależnie od serwera operacyjnego.
