package org.demo.bankdemo.history;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDPageContentStream.AppendMode;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState;
import org.apache.pdfbox.util.Matrix;
import org.springframework.stereotype.Service;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

@Service
public class HistoryExportService {

    private static final float PAGE_WIDTH = PDRectangle.A4.getWidth();
    private static final float PAGE_HEIGHT = PDRectangle.A4.getHeight();
    private static final float CONTENT_LEFT = 48f;
    private static final float CONTENT_RIGHT = 48f;
    private static final Color NAVY = new Color(11, 36, 66);
    private static final Color BLUE = new Color(31, 111, 235);
    private static final Color LIGHT_BLUE = new Color(236, 244, 255);
    private static final Color BORDER = new Color(205, 219, 238);
    private static final Color TEXT = new Color(20, 42, 72);

    private final HistoryService historyService;

    public HistoryExportService(HistoryService historyService) {
        this.historyService = historyService;
    }

    public byte[] csv(String email, HistoryFilter filter) {
        var csv = new StringBuilder("\uFEFF")
                .append("Data;Typ;Kierunek;Kontrahent;Tytul;Kwota;Waluta;Status;Numer referencyjny\r\n");
        historyService.exportRowsFor(email, filter).forEach(row -> csv
                .append(cell(row.createdAt())).append(';')
                .append(cell(row.type())).append(';')
                .append(cell(row.direction())).append(';')
                .append(cell(row.counterparty())).append(';')
                .append(cell(row.title())).append(';')
                .append(cell(row.amount())).append(';')
                .append(cell(row.currency())).append(';')
                .append(cell(row.status())).append(';')
                .append(cell(row.referenceNumber())).append("\r\n"));
        return csv.toString().getBytes(StandardCharsets.UTF_8);
    }

    public byte[] pdf(String email, HistoryFilter filter) {
        var rows = historyService.exportRowsFor(email, filter);
        try (var document = new PDDocument();
             var output = new ByteArrayOutputStream()) {
            var fonts = loadFonts(document);
            var page = new PDPage(PDRectangle.A4);
            document.addPage(page);
            drawPageChrome(document, page, fonts, false);
            var content = new PDPageContentStream(document, page, AppendMode.APPEND, true, true);
            writeHistoryHeading(content, fonts, false);
            var y = drawHistoryTableHeader(content, fonts, 700f);
            for (var rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
                var row = rows.get(rowIndex);
                var values = historyCells(row);
                var rowHeight = historyRowHeight(values, fonts);
                if (y - rowHeight < 42) {
                    drawWatermark(content, fonts);
                    content.close();
                    page = new PDPage(PDRectangle.A4);
                    document.addPage(page);
                    drawPageChrome(document, page, fonts, false);
                    content = new PDPageContentStream(document, page, AppendMode.APPEND, true, true);
                    writeHistoryHeading(content, fonts, true);
                    y = drawHistoryTableHeader(content, fonts, 700f);
                }
                drawHistoryRow(content, fonts, y, values, rowIndex % 2 == 0);
                y -= rowHeight;
            }
            drawWatermark(content, fonts);
            content.close();
            document.save(output);
            return output.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("Nie udalo sie utworzyc eksportu PDF historii.", exception);
        }
    }

    public byte[] confirmationPdf(String email, Long transactionId) {
        var detail = historyService.detailFor(email, transactionId);
        try (var document = new PDDocument();
             var output = new ByteArrayOutputStream()) {
            var fonts = loadFonts(document);
            var page = new PDPage(PDRectangle.A4);
            document.addPage(page);
            drawPageChrome(document, page, fonts);
            try (var content = new PDPageContentStream(document, page, AppendMode.APPEND, true, true)) {
                content.setNonStrokingColor(TEXT);
                writeHeader(content, fonts);

                var y = 688f;
                drawSummaryBand(content, fonts, y, detail.status(), detail.amount());
                y -= 72;

                var rows = List.of(
                        new DetailRow("Numer referencyjny", detail.referenceNumber()),
                        new DetailRow("Typ operacji", detail.type()),
                        new DetailRow("Status", detail.status()),
                        new DetailRow("Kierunek", detail.direction()),
                        new DetailRow("Kontrahent", detail.counterparty()),
                        new DetailRow("Rachunek kontrahenta", detail.counterpartyAccount()),
                        new DetailRow("Twój rachunek", detail.ownAccount()),
                        new DetailRow("Kwota", detail.amount()),
                        new DetailRow("Tytuł", detail.title()),
                        new DetailRow("Utworzono", detail.createdAt()),
                        new DetailRow("Zrealizowano", detail.completedAt())
                );
                y = drawDetailsTable(content, fonts, y, rows);
                drawWatermark(content, fonts);

                content.setNonStrokingColor(new Color(84, 105, 132));
                text(content, fonts.regular(), 8, CONTENT_LEFT, Math.max(66f, y - 24),
                        "Dokument edukacyjny - nie jest potwierdzeniem prawdziwej operacji bankowej.");
                text(content, fonts.regular(), 8, CONTENT_LEFT, Math.max(52f, y - 38),
                        "NovaBank DEMO | wygenerowano na podstawie historii zalogowanego użytkownika");
            }
            document.save(output);
            return output.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("Nie udalo sie utworzyc potwierdzenia PDF.", exception);
        }
    }

    private static String cell(String value) {
        var safe = value == null ? "" : value;
        if (!safe.isEmpty() && "=+-@".indexOf(safe.charAt(0)) >= 0) {
            safe = "'" + safe;
        }
        return "\"" + safe.replace("\"", "\"\"") + "\"";
    }

    private static List<String> wrap(String value, PDFont font, float fontSize, float maxWidth) throws IOException {
        var words = value.split(" ");
        var lines = new ArrayList<String>();
        var current = new StringBuilder();
        for (var word : words) {
            if (width(font, fontSize, word) > maxWidth) {
                if (current.length() > 0) {
                    lines.add(current.toString());
                    current.setLength(0);
                }
                var chunk = new StringBuilder();
                for (var character : word.toCharArray()) {
                    if (chunk.length() > 0 && width(font, fontSize, chunk + String.valueOf(character)) > maxWidth) {
                        lines.add(chunk.toString());
                        chunk.setLength(0);
                    }
                    chunk.append(character);
                }
                current.append(chunk);
                continue;
            }
            var candidate = current.length() == 0 ? word : current + " " + word;
            if (current.length() > 0 && width(font, fontSize, candidate) > maxWidth) {
                lines.add(current.toString());
                current.setLength(0);
            }
            if (current.length() > 0) {
                current.append(' ');
            }
            current.append(word);
        }
        if (current.length() > 0) {
            lines.add(current.toString());
        }
        return lines.isEmpty() ? List.of("") : lines;
    }

    private static FontSet loadFonts(PDDocument document) throws IOException {
        try (var regularStream = requiredFont("/fonts/DejaVuSans.ttf");
             var boldStream = requiredFont("/fonts/DejaVuSans-Bold.ttf")) {
            return new FontSet(
                    PDType0Font.load(document, regularStream, true),
                    PDType0Font.load(document, boldStream, true)
            );
        }
    }

    private static InputStream requiredFont(String resource) throws IOException {
        var stream = HistoryExportService.class.getResourceAsStream(resource);
        if (stream == null) {
            throw new IOException("Brak osadzonego fontu PDF: " + resource);
        }
        return stream;
    }

    private static void drawPageChrome(PDDocument document, PDPage page, FontSet fonts) throws IOException {
        drawPageChrome(document, page, fonts, true);
    }

    private static void drawPageChrome(PDDocument document, PDPage page, FontSet fonts,
                                       boolean includeWatermark) throws IOException {
        try (var content = new PDPageContentStream(document, page)) {
            content.setNonStrokingColor(NAVY);
            content.addRect(0, PAGE_HEIGHT - 36, PAGE_WIDTH, 36);
            content.fill();
            content.setNonStrokingColor(BLUE);
            content.addRect(0, 0, PAGE_WIDTH, 18);
            content.fill();

            if (includeWatermark) {
                content.saveGraphicsState();
                content.setNonStrokingColor(new Color(226, 237, 252));
                var transparency = new PDExtendedGraphicsState();
                transparency.setNonStrokingAlphaConstant(0.65f);
                content.setGraphicsStateParameters(transparency);
                content.transform(Matrix.getRotateInstance((float) Math.toRadians(32), 138, 290));
                content.beginText();
                content.setFont(fonts.bold(), 78);
                content.newLineAtOffset(0, 0);
                content.showText("NovaBank");
                content.endText();
                content.restoreGraphicsState();
            }

            content.setNonStrokingColor(Color.WHITE);
            text(content, fonts.bold(), 11, CONTENT_LEFT, PAGE_HEIGHT - 23, "NovaBank");
            content.setNonStrokingColor(new Color(226, 237, 252));
            text(content, fonts.regular(), 8, PAGE_WIDTH - 162, PAGE_HEIGHT - 23, "APLIKACJA EDUKACYJNA");
            text(content, fonts.regular(), 8, PAGE_WIDTH - 154, 5, "NovaBank DEMO");
        }
    }

    private static void drawWatermark(PDPageContentStream content, FontSet fonts) throws IOException {
        content.saveGraphicsState();
        content.setNonStrokingColor(new Color(152, 190, 238));
        var transparency = new PDExtendedGraphicsState();
        transparency.setNonStrokingAlphaConstant(0.16f);
        content.setGraphicsStateParameters(transparency);
        content.beginText();
        content.setFont(fonts.bold(), 76);
        content.setTextMatrix(Matrix.getRotateInstance((float) Math.toRadians(32), 88, 252));
        content.showText("NovaBank");
        content.endText();
        content.restoreGraphicsState();
    }

    private static void writeHistoryHeading(PDPageContentStream content, FontSet fonts,
                                             boolean continuation) throws IOException {
        content.setNonStrokingColor(TEXT);
        text(content, fonts.bold(), 16, CONTENT_LEFT, 770,
                continuation ? "NovaBank DEMO - historia operacji (ciąg dalszy)"
                        : "NovaBank DEMO - historia operacji");
        text(content, fonts.regular(), 8, CONTENT_LEFT, 748,
                "Eksport obejmuje dane widoczne dla zalogowanego właściciela rachunku.");
    }

    private static float drawHistoryTableHeader(PDPageContentStream content, FontSet fonts,
                                                 float top) throws IOException {
        var labels = List.of("Data", "Operacja", "Kontrahent / tytuł", "Kwota", "Status / referencja");
        var widths = historyColumnWidths();
        var height = 28f;
        var x = CONTENT_LEFT;
        for (var index = 0; index < labels.size(); index++) {
            drawCellBackground(content, x, top, widths.get(index), height,
                    new Color(226, 237, 252));
            text(content, fonts.bold(), 7, x + 6, top - 18, labels.get(index));
            x += widths.get(index);
        }
        return top - height;
    }

    private static void drawHistoryRow(PDPageContentStream content, FontSet fonts, float top,
                                       List<String> values, boolean alternate) throws IOException {
        var widths = historyColumnWidths();
        var height = historyRowHeight(values, fonts);
        var x = CONTENT_LEFT;
        for (var index = 0; index < values.size(); index++) {
            drawCellBackground(content, x, top, widths.get(index), height,
                    alternate ? Color.WHITE : new Color(248, 251, 255));
            var lines = wrapCell(values.get(index), fonts.regular(), 7, widths.get(index) - 12);
            for (var lineIndex = 0; lineIndex < lines.size(); lineIndex++) {
                text(content, fonts.regular(), 7, x + 6,
                        top - 14 - lineIndex * 10, lines.get(lineIndex));
            }
            x += widths.get(index);
        }
    }

    private static void drawCellBackground(PDPageContentStream content, float x, float top,
                                            float width, float height, Color fill) throws IOException {
        content.setNonStrokingColor(fill);
        content.addRect(x, top - height, width, height);
        content.fill();
        content.setStrokingColor(BORDER);
        content.addRect(x, top - height, width, height);
        content.stroke();
        content.setNonStrokingColor(TEXT);
    }

    private static float historyRowHeight(List<String> values, FontSet fonts) throws IOException {
        var widths = historyColumnWidths();
        var maxLines = 1;
        for (var index = 0; index < values.size(); index++) {
            maxLines = Math.max(maxLines,
                    wrapCell(values.get(index), fonts.regular(), 7, widths.get(index) - 12).size());
        }
        return Math.max(28f, maxLines * 10f + 10f);
    }

    private static List<String> historyCells(HistoryService.ExportRow row) {
        return List.of(
                safe(row.createdAt()),
                safe(row.type()) + "\n" + safe(row.direction()),
                safe(row.counterparty()) + "\n" + safe(row.title()),
                safe(row.amount()) + " " + safe(row.currency()),
                safe(row.status()) + "\n" + safe(row.referenceNumber())
        );
    }

    private static List<Float> historyColumnWidths() {
        return List.of(70f, 76f, 142f, 72f, 139f);
    }

    private static List<String> wrapCell(String value, PDFont font, float fontSize,
                                          float maxWidth) throws IOException {
        var lines = new ArrayList<String>();
        for (var paragraph : safe(value).split("\\R", -1)) {
            lines.addAll(wrap(paragraph, font, fontSize, maxWidth));
        }
        return lines.isEmpty() ? List.of("") : lines;
    }

    private static void writeHeader(PDPageContentStream content, FontSet fonts) throws IOException {
        text(content, fonts.bold(), 10, CONTENT_LEFT, 770, "NovaBank DEMO");
        text(content, fonts.bold(), 24, CONTENT_LEFT, 738, "Potwierdzenie operacji");
        text(content, fonts.regular(), 10, CONTENT_LEFT, 715,
                "Szczegóły operacji zapisane w historii rachunku.");
    }

    private static void drawSummaryBand(PDPageContentStream content, FontSet fonts, float y,
                                         String status, String amount) throws IOException {
        content.setNonStrokingColor(LIGHT_BLUE);
        content.addRect(CONTENT_LEFT, y - 56, PAGE_WIDTH - CONTENT_LEFT - CONTENT_RIGHT, 56);
        content.fill();
        content.setStrokingColor(BORDER);
        content.addRect(CONTENT_LEFT, y - 56, PAGE_WIDTH - CONTENT_LEFT - CONTENT_RIGHT, 56);
        content.stroke();
        content.setNonStrokingColor(TEXT);
        text(content, fonts.regular(), 8, CONTENT_LEFT + 16, y - 19, "STATUS OPERACJI");
        text(content, fonts.bold(), 12, CONTENT_LEFT + 16, y - 38, safe(status));
        text(content, fonts.regular(), 8, PAGE_WIDTH - 190, y - 19, "KWOTA");
        text(content, fonts.bold(), 16, PAGE_WIDTH - 190, y - 41, safe(amount));
    }

    private static float drawDetailsTable(PDPageContentStream content, FontSet fonts, float y,
                                           List<DetailRow> rows) throws IOException {
        var tableWidth = PAGE_WIDTH - CONTENT_LEFT - CONTENT_RIGHT;
        var labelWidth = 150f;
        var valueWidth = tableWidth - labelWidth - 24;
        for (var index = 0; index < rows.size(); index++) {
            var row = rows.get(index);
            var valueLines = wrap(safe(row.value()), fonts.regular(), 9, valueWidth);
            var rowHeight = Math.max(28f, valueLines.size() * 13f + 14f);
            content.setNonStrokingColor(index % 2 == 0 ? Color.WHITE : new Color(248, 251, 255));
            content.addRect(CONTENT_LEFT, y - rowHeight, tableWidth, rowHeight);
            content.fill();
            content.setStrokingColor(BORDER);
            content.addRect(CONTENT_LEFT, y - rowHeight, tableWidth, rowHeight);
            content.stroke();
            content.setStrokingColor(BORDER);
            content.moveTo(CONTENT_LEFT + labelWidth, y - rowHeight);
            content.lineTo(CONTENT_LEFT + labelWidth, y);
            content.stroke();
            content.setNonStrokingColor(TEXT);
            text(content, fonts.bold(), 8, CONTENT_LEFT + 12, y - 18, row.label());
            for (var lineIndex = 0; lineIndex < valueLines.size(); lineIndex++) {
                text(content, fonts.regular(), 9, CONTENT_LEFT + labelWidth + 12,
                        y - 17 - (lineIndex * 13), valueLines.get(lineIndex));
            }
            y -= rowHeight;
        }
        return y;
    }

    private static void text(PDPageContentStream content, PDFont font, float size, float x, float y,
                             String value) throws IOException {
        content.beginText();
        content.setFont(font, size);
        content.newLineAtOffset(x, y);
        content.showText(safe(value));
        content.endText();
    }

    private static float width(PDFont font, float size, String value) throws IOException {
        return font.getStringWidth(value) / 1000f * size;
    }

    private static String safe(String value) {
        return value == null ? "-" : value;
    }

    private record FontSet(PDFont regular, PDFont bold) {
    }

    private record DetailRow(String label, String value) {
    }
}
