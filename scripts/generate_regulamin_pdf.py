from pathlib import Path

from reportlab.lib import colors
from reportlab.lib.enums import TA_CENTER, TA_JUSTIFY, TA_LEFT
from reportlab.lib.pagesizes import A4
from reportlab.lib.styles import ParagraphStyle, getSampleStyleSheet
from reportlab.lib.units import mm
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont
from reportlab.platypus import (
    KeepTogether,
    Paragraph,
    SimpleDocTemplate,
    Spacer,
    Table,
    TableStyle,
)


ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / "regulamin" / "regulamin_novabank.pdf"
FONT_PAIRS = (
    # Windows native and Windows fonts mounted in WSL.
    (Path(r"C:\Windows\Fonts\arial.ttf"), Path(r"C:\Windows\Fonts\arialbd.ttf")),
    (Path("/mnt/c/Windows/Fonts/arial.ttf"), Path("/mnt/c/Windows/Fonts/arialbd.ttf")),
    # Common Linux fonts available in WSL distributions.
    (
        Path("/usr/share/fonts/truetype/liberation2/LiberationSans-Regular.ttf"),
        Path("/usr/share/fonts/truetype/liberation2/LiberationSans-Bold.ttf"),
    ),
    (
        Path("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"),
        Path("/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"),
    ),
)

NAVY = colors.HexColor("#0B1F3A")
BLUE = colors.HexColor("#1769E0")
LIGHT_BLUE = colors.HexColor("#EAF2FF")
PALE = colors.HexColor("#F6F8FC")
MUTED = colors.HexColor("#5E6B82")
LINE = colors.HexColor("#D9E2EF")
WHITE = colors.white


def register_fonts():
    for regular, bold in FONT_PAIRS:
        if regular.is_file() and bold.is_file():
            pdfmetrics.registerFont(TTFont("Arial", str(regular)))
            pdfmetrics.registerFont(TTFont("Arial-Bold", str(bold)))
            return

    locations = ", ".join(str(regular.parent) for regular, _ in FONT_PAIRS)
    raise FileNotFoundError(
        "Nie znaleziono fontów z obsługą polskich znaków. Sprawdzono: " + locations
    )


def draw_page(canvas, doc):
    width, height = A4
    canvas.saveState()

    canvas.setFillColor(NAVY)
    canvas.rect(0, height - 18 * mm, width, 18 * mm, fill=1, stroke=0)
    canvas.setFont("Arial-Bold", 11)
    canvas.setFillColor(WHITE)
    canvas.drawString(20 * mm, height - 11.5 * mm, "NovaBank")
    canvas.setFont("Arial", 8.5)
    canvas.drawRightString(
        width - 20 * mm,
        height - 11.5 * mm,
        "Aplikacja edukacyjna - regulamin",
    )

    canvas.setStrokeColor(LINE)
    canvas.line(20 * mm, 15 * mm, width - 20 * mm, 15 * mm)
    canvas.setFont("Arial", 8)
    canvas.setFillColor(MUTED)
    canvas.drawString(20 * mm, 9.5 * mm, "Wersja 2.0 | obowiązuje od 27 lipca 2026 r.")
    canvas.drawRightString(
        width - 20 * mm,
        9.5 * mm,
        f"Strona {doc.page}",
    )
    canvas.restoreState()


def make_styles():
    base = getSampleStyleSheet()
    return {
        "title": ParagraphStyle(
            "Title",
            parent=base["Title"],
            fontName="Arial-Bold",
            fontSize=24,
            leading=29,
            textColor=NAVY,
            alignment=TA_CENTER,
            spaceAfter=5 * mm,
        ),
        "subtitle": ParagraphStyle(
            "Subtitle",
            parent=base["Normal"],
            fontName="Arial",
            fontSize=10,
            leading=15,
            textColor=MUTED,
            alignment=TA_CENTER,
            spaceAfter=8 * mm,
        ),
        "heading": ParagraphStyle(
            "Heading",
            parent=base["Heading2"],
            fontName="Arial-Bold",
            fontSize=13,
            leading=17,
            textColor=NAVY,
            spaceBefore=4.5 * mm,
            spaceAfter=2.5 * mm,
            keepWithNext=True,
        ),
        "body": ParagraphStyle(
            "Body",
            parent=base["BodyText"],
            fontName="Arial",
            fontSize=9.3,
            leading=14,
            textColor=colors.HexColor("#26344D"),
            alignment=TA_JUSTIFY,
            spaceAfter=2.5 * mm,
        ),
        "bullet": ParagraphStyle(
            "Bullet",
            parent=base["BodyText"],
            fontName="Arial",
            fontSize=9.3,
            leading=14,
            textColor=colors.HexColor("#26344D"),
            leftIndent=5 * mm,
            firstLineIndent=-3.5 * mm,
            bulletIndent=0,
            spaceAfter=1.8 * mm,
        ),
        "callout_title": ParagraphStyle(
            "CalloutTitle",
            parent=base["Heading3"],
            fontName="Arial-Bold",
            fontSize=11,
            leading=14,
            textColor=BLUE,
            alignment=TA_LEFT,
            spaceAfter=1.5 * mm,
        ),
        "callout_body": ParagraphStyle(
            "CalloutBody",
            parent=base["BodyText"],
            fontName="Arial",
            fontSize=9.2,
            leading=14,
            textColor=NAVY,
            alignment=TA_LEFT,
        ),
        "meta": ParagraphStyle(
            "Meta",
            parent=base["BodyText"],
            fontName="Arial",
            fontSize=8.5,
            leading=12,
            textColor=MUTED,
            alignment=TA_CENTER,
        ),
    }


def p(text, style):
    return Paragraph(text, style)


def section(number, title, paragraphs, styles):
    items = [p(f"{number}. {title}", styles["heading"])]
    for paragraph in paragraphs:
        if isinstance(paragraph, tuple):
            items.append(p(f"• {paragraph[0]}", styles["bullet"]))
        else:
            items.append(p(paragraph, styles["body"]))
    return items


def build_pdf():
    register_fonts()
    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    styles = make_styles()

    doc = SimpleDocTemplate(
        str(OUTPUT),
        pagesize=A4,
        leftMargin=20 * mm,
        rightMargin=20 * mm,
        topMargin=26 * mm,
        bottomMargin=22 * mm,
        title="Regulamin aplikacji edukacyjnej NovaBank",
        author="Projekt NovaBank",
        subject="Regulamin korzystania z demonstracyjnej aplikacji bankowej",
    )
    story = [
        Spacer(1, 8 * mm),
        p("Regulamin aplikacji NovaBank", styles["title"]),
        p(
            "Zasady korzystania z edukacyjnej symulacji bankowości internetowej",
            styles["subtitle"],
        ),
    ]

    warning = Table(
        [
            [
                p("WAŻNA INFORMACJA", styles["callout_title"]),
                p(
                    "NovaBank nie jest bankiem ani instytucją płatniczą. Aplikacja "
                    "służy wyłącznie do nauki i demonstracji. Salda, rachunki i "
                    "transakcje są wirtualne i nie przedstawiają realnej wartości.",
                    styles["callout_body"],
                ),
            ]
        ],
        colWidths=[38 * mm, 117 * mm],
        hAlign="CENTER",
    )
    warning.setStyle(
        TableStyle(
            [
                ("BACKGROUND", (0, 0), (-1, -1), LIGHT_BLUE),
                ("BOX", (0, 0), (-1, -1), 0.8, colors.HexColor("#B9D2FA")),
                ("VALIGN", (0, 0), (-1, -1), "MIDDLE"),
                ("LEFTPADDING", (0, 0), (-1, -1), 5 * mm),
                ("RIGHTPADDING", (0, 0), (-1, -1), 5 * mm),
                ("TOPPADDING", (0, 0), (-1, -1), 4 * mm),
                ("BOTTOMPADDING", (0, 0), (-1, -1), 4 * mm),
            ]
        )
    )
    story.extend([warning, Spacer(1, 5 * mm)])

    story.extend(
        section(
            "1",
            "Postanowienia ogólne",
            [
                "Niniejszy regulamin określa zasady korzystania z aplikacji "
                "NovaBank dostępnej lokalnie pod adresem "
                "<b>http://localhost:8080</b>.",
                "Operatorem aplikacji jest administrator projektu edukacyjnego "
                "NovaBank. W sprawach dotyczących działania aplikacji użytkownik "
                "kontaktuje się bezpośrednio z administratorem projektu.",
                "Rozpoczęcie korzystania z aplikacji, w tym zalogowanie się, "
                "oznacza zapoznanie się z regulaminem i akceptację jego treści.",
            ],
            styles,
        )
    )
    story.extend(
        section(
            "2",
            "Edukacyjny charakter usługi",
            [
                "Aplikacja odwzorowuje wybrane funkcje bankowości internetowej "
                "wyłącznie w celu nauki programowania, testowania i prezentacji.",
                ("NovaBank nie prowadzi rachunków bankowych i nie przechowuje pieniędzy."),
                ("Wirtualnych środków nie można wpłacać, wypłacać, wymieniać ani zamieniać na pieniądze lub inne dobra."),
                ("Numery rachunków generowane w aplikacji są fikcyjne i służą wyłącznie do operacji wewnątrz środowiska demonstracyjnego."),
                ("Eksporty CSV i PDF nie są wyciągami bankowymi ani potwierdzeniami wykonania rzeczywistej płatności."),
            ],
            styles,
        )
    )
    story.extend(
        section(
            "3",
            "Konto użytkownika i bezpieczeństwo dostępu",
            [
                "Do korzystania z funkcji aplikacji może być wymagane założenie "
                "konta i zalogowanie się. Użytkownik powinien podać dane wymagane "
                "przez formularz i chronić swoje dane logowania.",
                ("Nie należy używać w aplikacji hasła stosowanego w prawdziwym banku, poczcie lub innym ważnym serwisie."),
                ("Nie należy udostępniać hasła, kodu PIN ani aktywnej sesji innym osobom."),
                ("Użytkownik powinien wylogować się po zakończeniu pracy, szczególnie na współdzielonym komputerze."),
                "Hasła oraz kod PIN są przechowywane w postaci skrótu "
                "kryptograficznego. Aplikacja korzysta z sesji serwerowej; "
                "identyfikator sesji znajduje się w ciasteczku przeglądarki, a "
                "sesja wygasa po 30 minutach bezczynności. Użytkownik może "
                "włączyć Google Authenticator (TOTP), kody odzyskiwania oraz "
                "PIN transakcyjny. Kod TOTP służy do dodatkowego potwierdzania "
                "logowania, nowego urządzenia, nowego odbiorcy lub operacji "
                "przekraczającej ustalony próg.",
            ],
            styles,
        )
    )
    story.extend(
        section(
            "4",
            "Funkcje i operacje demonstracyjne",
            [
                "Po zalogowaniu użytkownik może korzystać z funkcji udostępnionych "
                "w aktualnej wersji aplikacji, w szczególności z:",
                ("podglądu wirtualnego salda, numeru rachunku i historii operacji;"),
                ("wykonywania symulowanych przelewów między rachunkami istniejącymi w aplikacji;"),
                ("zapisywania odbiorców i zarządzania ich danymi;"),
                ("odbierania komunikatów i powiadomień systemowych;"),
                ("edycji profilu, zmiany hasła oraz konfiguracji kodu PIN;"),
                ("pobierania demonstracyjnej historii operacji w formatach CSV i PDF;"),
                ("wymiany walut po kursach symulacyjnych oraz obsługi subkont walutowych;"),
                ("obsługi demonstracyjnych kart, limitów dziennych i historii operacji kartą;"),
                ("ustawiania własnych limitów przelewów i płatności w granicach limitów banku;"),
                ("korzystania z dodatkowego serwisu NovaTech Shop: katalogu, koszyka, zamówień i refundacji;"),
                ("wyboru płatności przez bank demonstracyjny albo testowego operatora Stripe."),
                "Każda operacja wpływa wyłącznie na dane demonstracyjne. "
                "Administrator może udostępnić konto z rolą administracyjną do "
                "zarządzania środowiskiem, przeglądania zdarzeń audytowych i "
                "kontroli retencji danych technicznych.",
            ],
            styles,
        )
    )
    story.extend(
        section(
            "5",
            "Zasady korzystania",
            [
                "Użytkownik zobowiązuje się korzystać z aplikacji zgodnie z jej "
                "edukacyjnym przeznaczeniem, prawem oraz zasadami bezpieczeństwa.",
                ("Zabronione jest podejmowanie prób uzyskania nieuprawnionego dostępu do kont, sesji, bazy danych lub infrastruktury."),
                ("Zabronione jest celowe zakłócanie działania aplikacji, obchodzenie zabezpieczeń i wprowadzanie szkodliwego kodu."),
                ("Danych i ekranów aplikacji nie wolno przedstawiać jako dowodu posiadania środków lub wykonania prawdziwej płatności."),
                ("Nie wolno obchodzić limitów, ochrony przed wielokrotnymi żądaniami ani dodatkowych potwierdzeń TOTP/PIN."),
                "W przypadku naruszenia zasad administrator może zablokować konto, "
                "zamknąć rachunek, unieważnić sesje albo wyłączyć dostęp do aplikacji.",
            ],
            styles,
        )
    )
    story.extend(
        section(
            "6",
            "Dane i prywatność",
            [
                "Aplikacja może przechowywać dane podane podczas rejestracji, dane "
                "profilu, historię operacji, odbiorców, powiadomienia i zdarzenia "
                "związane z bezpieczeństwem. Dane trwałe są przechowywane w bazie "
                "PostgreSQL, a aktywne sesje w Redisie.",
                "Ze względu na demonstracyjny charakter projektu użytkownik nie "
                "powinien podawać prawdziwych, poufnych ani szczególnie chronionych "
                "danych osobowych. Zalecane jest używanie danych testowych.",
                "Rejestracja w NovaTech Shop korzysta z osobnego konta sklepu i "
                "nie wymaga podawania numeru rachunku bankowego. Numer rachunku "
                "może być wymagany dopiero podczas finalizacji płatności przez bank.",
                "W trybie płatności bank-demo wolno używać wyłącznie demonstracyjnych "
                "danych kart. W trybie Stripe płatność odbywa się w testowym "
                "formularzu operatora; używaj wyłącznie kart testowych Stripe. "
                "Nie używaj prawdziwego numeru karty, CVV ani daty ważności.",
                "Aplikacja może rejestrować metryki, logi, historię logowań i audyt "
                "operacji administracyjnych. Dane techniczne podlegają konfiguracji "
                "retencji i mogą być usuwane w kontrolowanym procesie, natomiast "
                "retencja nie służy do automatycznego usuwania rachunków, sald, "
                "transakcji, zamówień, refundacji ani danych modelu karty.",
                "Dane mogą zostać usunięte lub zresetowane w związku z rozwojem, "
                "testami, konserwacją albo odtworzeniem środowiska.",
            ],
            styles,
        )
    )
    story.extend(
        section(
            "7",
            "Dostępność i odpowiedzialność",
            [
                "Aplikacja jest udostępniana w stanie bieżącym, bez gwarancji "
                "ciągłej dostępności, bezbłędności lub zachowania danych. Może być "
                "czasowo wyłączana, aktualizowana albo modyfikowana.",
                "Administrator nie odpowiada za decyzje finansowe, szkody ani "
                "oczekiwania oparte na fikcyjnych saldach, transakcjach, "
                "powiadomieniach lub dokumentach wygenerowanych przez aplikację.",
                "Użytkownik odpowiada za bezpieczeństwo własnego urządzenia, "
                "przeglądarki i danych dostępowych użytych w środowisku demonstracyjnym.",
            ],
            styles,
        )
    )
    story.extend(
        section(
            "8",
            "Zakończenie korzystania",
            [
                "Użytkownik może w dowolnym momencie zakończyć korzystanie z "
                "aplikacji przez wylogowanie. Usunięcie konta lub danych może "
                "wymagać kontaktu z administratorem projektu.",
                "Administrator może zakończyć działanie całego środowiska, "
                "zablokować dostęp albo zamknąć konto, zwłaszcza po zakończeniu "
                "ćwiczeń lub prezentacji. Zamknięcie rachunku nie oznacza "
                "automatycznego usunięcia historii ani danych audytowych.",
            ],
            styles,
        )
    )
    story.extend(
        section(
            "9",
            "Zmiany regulaminu",
            [
                "Regulamin może być aktualizowany wraz z rozwojem funkcji "
                "aplikacji. Aktualna wersja jest udostępniana do pobrania na "
                "stronie logowania.",
                "Dalsze korzystanie z aplikacji po udostępnieniu nowej wersji "
                "regulaminu oznacza akceptację zaktualizowanych zasad.",
            ],
            styles,
        )
    )
    story.extend(
        section(
            "10",
            "Postanowienia końcowe",
            [
                "W sprawach nieopisanych w regulaminie decyzje dotyczące "
                "organizacji środowiska edukacyjnego podejmuje administrator "
                "projektu z uwzględnieniem celu aplikacji i bezpieczeństwa jej "
                "użytkowników.",
                "Regulamin w wersji 2.0 obowiązuje od 27 lipca 2026 r.",
            ],
            styles,
        )
    )

    closing = Table(
        [
            [
                p(
                    "<b>Pamiętaj:</b> NovaBank to symulacja. Nie wprowadzaj danych "
                    "prawdziwego rachunku, karty płatniczej ani danych logowania "
                    "do rzeczywistych usług finansowych.",
                    styles["callout_body"],
                )
            ]
        ],
        colWidths=[155 * mm],
    )
    closing.setStyle(
        TableStyle(
            [
                ("BACKGROUND", (0, 0), (-1, -1), PALE),
                ("BOX", (0, 0), (-1, -1), 0.7, LINE),
                ("LEFTPADDING", (0, 0), (-1, -1), 5 * mm),
                ("RIGHTPADDING", (0, 0), (-1, -1), 5 * mm),
                ("TOPPADDING", (0, 0), (-1, -1), 4 * mm),
                ("BOTTOMPADDING", (0, 0), (-1, -1), 4 * mm),
            ]
        )
    )
    story.extend([Spacer(1, 4 * mm), KeepTogether([closing])])

    doc.build(story, onFirstPage=draw_page, onLaterPages=draw_page)
    print(OUTPUT)


if __name__ == "__main__":
    build_pdf()
