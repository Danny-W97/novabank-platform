# Domknięcie migracji CLI → WWW — 2026-10-16

Aktualizacja po wdrożeniu operacyjnym: poprawiono ścieżki systemd i zaktualizowano obie usługi WSL. Rzeczywisty backup oraz izolowany DR zakończyły się sukcesem. Szczegóły, kopie, daty i aktualne ograniczenia: [raport naprawy harmonogramów](control-center-schedule-repair.md). Poniższa weryfikacja opisuje wcześniejszy etap implementacji przed tym odbiorem.

Zakres: konfiguracja katalogu kopii i retencji, godziny backupu/DR, planowana migracja timerów CLI, osobna historia CLI, diagnostyka Vault/CA. Bez PowerShell/Windows JDK, automatycznej migracji danych, zmiany CA ani operacji na żywych usługach w trakcie wdrożenia.

## Postęp

- [x] Ustawienia operacyjne i bezpieczny katalog backupów.
- [x] Przekazanie ustawień do operacji i timerów.
- [x] Wykrywanie, plan i kontrolowana migracja starych timerów.
- [x] Import i podgląd historii CLI bez możliwości wykonywania wpisów.
- [x] Diagnostyka Vault/CA bez zmiany zaufania TLS.
- [x] Interfejs, kontrakt API i instrukcje.
- [x] Testy bezpieczeństwa, regresji i weryfikacja interfejsu.

## Wynik

- **Ustawienia operacji:** DEV — katalog, retencja i godzina kopii, dzień/godzina DR oraz retencje artefaktów/tmp. TEST — dwie retencje, bez odczytu katalogu kopii DEV. Pierwszy odczyt niczego nie tworzy; zapis z rewizją i potwierdzeniem nie instaluje timerów ani nie przenosi dumpów.
- **Bezpieczny katalog:** zamknięte reguły ścieżek, brak dowiązań/hardlinków, znacznik projektu przed retencją. Nie przejmujemy obcego niepustego folderu ani starego archiwum CLI. Dotychczasowa domyślna lokalizacja WWW pozostaje zgodna.
- **Harmonogramy:** kopia starych jednostek, plan związany z rewizją, blokada obcych/drop-in/niepełnych jednostek i aktywnych usług, wyłączenie CLI przed instalacją WWW. Po błędzie próba cofnięcia tylko własnych zmian; aktywna nowa usługa blokuje bezwarunkowe ponowne włączenie CLI.
- **Historia:** osobny maskowany import, deduplikacja, do 500 wpisów; bez zmiany źródła i bez możliwości wykonania wpisu jako zadania WWW.
- **Vault/CA:** odczyt publicznych certyfikatów/stanu i lokalny TLS przez skonfigurowane CA; bez prywatnych kluczy, bootstrapu/KV/tokenów, kopiowania CA lub restartów. Zachowano potwierdzenie PROD.
- **Dokumentacja:** [instrukcja panelu](control-center-web.md), [procedury problemów](control-center-troubleshooting.md#operator-settings), OpenAPI — 26 operacji HTTP, 90 akcji.

## Weryfikacja

- Python/API/Bash: **219 testów, 208 zaliczonych, 11 opcjonalnych SQL pominiętych**. Nowe testy używają tymczasowych plików, atrap systemd, rejestrujących adapterów Bash i dry-run renderowania jednostek. Obejmują plan/rewizję, własność, rollback oraz brak ponownego włączenia CLI przy aktywnej nowej usłudze, historię Unicode i rozdzielenie DEV/TEST.
- Wszystkie **7 zestawów JS**, składnia app/operator-settings i modele/przykładowe odpowiedzi OpenAPI przez lokalny JSON Schema — OK.
- Przeglądarka, osobny serwer i fikcyjne dane: zapis ustawień po potwierdzeniu, nowy termin w planie, nieaktywny przycisk migracji bez potwierdzenia, TEST z dwoma polami i PROD z diagnostyką/ostrzeżeniem — OK. Przy 390 px jedna kolumna i przewijanie tabeli w jej kontenerze; konsola bez ostrzeżeń/błędów. Zmieniano wyłącznie ustawienia podglądu w jego katalogu tymczasowym.
- Podglądy: [ustawienia](control-center-operator-settings-preview.jpg), [plan migracji](control-center-cli-migration-plan-preview.jpg), [mobilny](control-center-operator-settings-mobile-preview.jpg).

## Świadome ograniczenia / niewykonane działania operacyjne

Nie przeprowadzono rzeczywistej migracji systemd, backupu, retencji, połączenia Vault ani restartu usług użytkownika. Nie odczytywano rzeczywistych sekretów/archiwów/kont na potrzeby tego wdrożenia. Testy Java/SQL nie były ponawiane — nie zmieniano ich implementacji. Wymagany osobny test integracyjny na skonfigurowanym WSL przed operacyjnym użyciem migracji.

Migracja nie jest transakcją odporną na zabicie procesu: w razie niepotwierdzonego cofnięcia operator sprawdza oba timery i kopię jednostek. Nie uruchamiaj równoległych zmian CLI/IDE. Istniejące harmonogramy nie zmieniają się po samym zapisie formularza; wymagają osobnej aktualizacji. Stare kopie baz pozostają w poprzedniej lokalizacji.

CLI pozostaje trybem zgodności; nie wyłączono go automatycznie. Nie przeniesiono fallbacku Windows/PowerShell/JDK, automatycznego przejmowania CA ani szerokiego porządkowania dowolnych plików. To świadome granice wersji WSL, nie ukryta pełna kopia nieograniczonych operacji CLI.
