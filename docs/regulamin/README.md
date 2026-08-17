# Generator PDF regulaminu

Plik `scripts/generate_regulamin_pdf.py` tworzy dokument:

```text
regulamin/regulamin_novabank.pdf
```

## NovaBank Control Center

Wybierz `TLS i dokumenty generowane -> Generuj PDF regulaminu`. Centrala:

1. tworzy w katalogu projektu lokalne środowisko `.novabank/pdf-venv`, jeśli go nie ma;
2. instaluje w nim `reportlab` z `scripts/requirements-pdf.txt`, jeśli biblioteka nie jest jeszcze dostępna;
3. uruchamia generator właściwym interpreterem Pythona;
4. zapisuje PDF w katalogu `regulamin`.

`.novabank/pdf-venv` jest lokalnym środowiskiem roboczym i nie powinno być dodawane do repozytorium.

Jeśli WSL nie ma modułu `venv`, wykonaj jednorazowo:

```bash
sudo apt update
sudo apt install -y python3-venv
```

Następnie ponów operację w Centrali. Instalacja `reportlab` wymaga dostępu WSL do Internetu.

## Uruchomienie ręczne

Z katalogu projektu w WSL:

```bash
python3 -m venv .novabank/pdf-venv
./.novabank/pdf-venv/bin/python -m pip install -r scripts/requirements-pdf.txt
./.novabank/pdf-venv/bin/python scripts/generate_regulamin_pdf.py
```

Generator rozpoznaje fonty Arial z Windows/WSL oraz popularne fonty Linux. Dzięki temu polskie znaki pozostają poprawne niezależnie od systemu.
