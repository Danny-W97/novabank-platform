package org.demo.shopapp.catalog;

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
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Service
public class ProductCatalogPdfService {

    private static final float PAGE_WIDTH = PDRectangle.A4.getWidth();
    private static final float PAGE_HEIGHT = PDRectangle.A4.getHeight();
    private static final float LEFT = 48f;
    private static final float RIGHT = 48f;
    private static final float TABLE_RIGHT = PAGE_WIDTH - RIGHT;
    private static final float[] COLUMNS = {48f, 105f, 270f, 400f, 500f, 547f};
    private static final Color NAVY = new Color(11, 36, 66);
    private static final Color BLUE = new Color(31, 111, 235);
    private static final Color LIGHT_BLUE = new Color(236, 244, 255);
    private static final Color BORDER = new Color(205, 219, 238);
    private static final Color TEXT = new Color(20, 42, 72);

    private final ProductService productService;

    public ProductCatalogPdfService(ProductService productService) {
        this.productService = productService;
    }

    public byte[] create() {
        var products = productService.listActive(null, null, "name_asc");
        try (var document = new PDDocument(); var output = new ByteArrayOutputStream()) {
            var fonts = loadFonts(document);
            var page = new PDPage(PDRectangle.A4);
            document.addPage(page);
            drawPageChrome(document, page, fonts);

            PDPageContentStream content = new PDPageContentStream(document, page, AppendMode.APPEND, true, true);
            float y = drawHeading(content, fonts);
            y = drawTableHeader(content, fonts, y);

            try {
                for (int index = 0; index < products.size(); index++) {
                    var product = products.get(index);
                    var cells = cells(product);
                    var rowHeight = rowHeight(cells, fonts);
                    if (y - rowHeight < 42f) {
                        content.close();
                        page = new PDPage(PDRectangle.A4);
                        document.addPage(page);
                        drawPageChrome(document, page, fonts);
                        content = new PDPageContentStream(document, page, AppendMode.APPEND, true, true);
                        y = drawHeading(content, fonts);
                        y = drawTableHeader(content, fonts, y);
                    }
                    drawRow(content, fonts, y, rowHeight, cells, index % 2 == 0);
                    y -= rowHeight;
                }
            } finally {
                content.close();
            }

            document.save(output);
            return output.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("Nie udalo sie utworzyc katalogu PDF.", exception);
        }
    }

    private static List<String> cells(ProductResponse product) {
        return List.of(
                safe(product.sku()),
                safe(product.name()),
                safe(product.description()),
                formatPrice(product.price()),
                safe(product.currency())
        );
    }

    private static float drawHeading(PDPageContentStream content, FontSet fonts) throws IOException {
        content.setNonStrokingColor(TEXT);
        text(content, fonts.bold(), 18, LEFT, 775f, "NovaTech Shop - Katalog produktow");
        text(content, fonts.regular(), 9, LEFT, 758f,
                "Aktywne produkty dostepne w sklepie | stan katalogu: " + LocalDate.now());
        return 733f;
    }

    private static float drawTableHeader(PDPageContentStream content, FontSet fonts, float top) throws IOException {
        content.setNonStrokingColor(LIGHT_BLUE);
        content.addRect(LEFT, top - 22f, TABLE_RIGHT - LEFT, 22f);
        content.fill();
        content.setStrokingColor(BORDER);
        content.addRect(LEFT, top - 22f, TABLE_RIGHT - LEFT, 22f);
        content.stroke();
        for (int i = 1; i < COLUMNS.length - 1; i++) {
            content.moveTo(COLUMNS[i], top - 22f);
            content.lineTo(COLUMNS[i], top);
            content.stroke();
        }
        content.setNonStrokingColor(TEXT);
        text(content, fonts.bold(), 8, COLUMNS[0] + 6, top - 14f, "SKU");
        text(content, fonts.bold(), 8, COLUMNS[1] + 6, top - 14f, "Produkt");
        text(content, fonts.bold(), 8, COLUMNS[2] + 6, top - 14f, "Opis");
        text(content, fonts.bold(), 8, COLUMNS[3] + 6, top - 14f, "Cena");
        text(content, fonts.bold(), 8, COLUMNS[4] + 6, top - 14f, "Waluta");
        return top - 22f;
    }

    private static void drawRow(PDPageContentStream content, FontSet fonts, float top, float height,
                                List<String> cells, boolean shaded) throws IOException {
        if (shaded) {
            content.setNonStrokingColor(new Color(248, 251, 255));
            content.addRect(LEFT, top - height, TABLE_RIGHT - LEFT, height);
            content.fill();
        }
        content.setStrokingColor(BORDER);
        content.addRect(LEFT, top - height, TABLE_RIGHT - LEFT, height);
        content.stroke();
        for (int i = 1; i < COLUMNS.length - 1; i++) {
            content.moveTo(COLUMNS[i], top - height);
            content.lineTo(COLUMNS[i], top);
            content.stroke();
        }

        content.setNonStrokingColor(TEXT);
        for (int i = 0; i < cells.size(); i++) {
            var lines = wrap(cells.get(i), fonts.regular(), 8f, COLUMNS[i + 1] - COLUMNS[i] - 12f);
            float y = top - 13f;
            for (var line : lines) {
                text(content, fonts.regular(), 8, COLUMNS[i] + 6, y, line);
                y -= 10f;
            }
        }
    }

    private static float rowHeight(List<String> cells, FontSet fonts) throws IOException {
        int maxLines = 1;
        for (int i = 0; i < cells.size(); i++) {
            maxLines = Math.max(maxLines,
                    wrap(cells.get(i), fonts.regular(), 8f, COLUMNS[i + 1] - COLUMNS[i] - 12f).size());
        }
        return Math.max(28f, maxLines * 10f + 8f);
    }

    private static List<String> wrap(String value, PDFont font, float size, float maxWidth) throws IOException {
        var lines = new ArrayList<String>();
        var current = new StringBuilder();
        for (var word : safe(value).split("\\s+")) {
            if (word.isEmpty()) {
                continue;
            }
            var candidate = current.length() == 0 ? word : current + " " + word;
            if (width(font, size, candidate) <= maxWidth) {
                current.setLength(0);
                current.append(candidate);
                continue;
            }
            if (current.length() > 0) {
                lines.add(current.toString());
                current.setLength(0);
            }
            if (width(font, size, word) <= maxWidth) {
                current.append(word);
                continue;
            }
            for (var character : word.toCharArray()) {
                if (current.length() > 0 && width(font, size, current.toString() + character) > maxWidth) {
                    lines.add(current.toString());
                    current.setLength(0);
                }
                current.append(character);
            }
        }
        if (current.length() > 0) {
            lines.add(current.toString());
        }
        return lines.isEmpty() ? List.of("") : lines;
    }

    private static void drawPageChrome(PDDocument document, PDPage page, FontSet fonts) throws IOException {
        try (var content = new PDPageContentStream(document, page)) {
            content.setNonStrokingColor(NAVY);
            content.addRect(0, PAGE_HEIGHT - 36f, PAGE_WIDTH, 36f);
            content.fill();
            content.setNonStrokingColor(BLUE);
            content.addRect(0, 0, PAGE_WIDTH, 18f);
            content.fill();

            content.saveGraphicsState();
            content.setNonStrokingColor(new Color(226, 237, 252));
            var transparency = new PDExtendedGraphicsState();
            transparency.setNonStrokingAlphaConstant(0.55f);
            content.setGraphicsStateParameters(transparency);
            content.transform(Matrix.getRotateInstance((float) Math.toRadians(32), 138, 290));
            content.beginText();
            content.setFont(fonts.bold(), 78);
            content.newLineAtOffset(0, 0);
            content.showText("NovaBank");
            content.endText();
            content.restoreGraphicsState();

            content.setNonStrokingColor(Color.WHITE);
            text(content, fonts.bold(), 11, LEFT, PAGE_HEIGHT - 23f, "NovaBank");
            content.setNonStrokingColor(new Color(226, 237, 252));
            text(content, fonts.regular(), 8, PAGE_WIDTH - 162, PAGE_HEIGHT - 23f, "APLIKACJA EDUKACYJNA");
            text(content, fonts.regular(), 8, PAGE_WIDTH - 154, 5f, "NovaBank DEMO");
        }
    }

    private static FontSet loadFonts(PDDocument document) throws IOException {
        try (var regular = requiredFont("/fonts/DejaVuSans.ttf");
             var bold = requiredFont("/fonts/DejaVuSans-Bold.ttf")) {
            return new FontSet(
                    PDType0Font.load(document, regular, true),
                    PDType0Font.load(document, bold, true)
            );
        }
    }

    private static InputStream requiredFont(String resource) throws IOException {
        var stream = ProductCatalogPdfService.class.getResourceAsStream(resource);
        if (stream == null) {
            throw new IOException("Brak osadzonego fontu PDF: " + resource);
        }
        return stream;
    }

    private static void text(PDPageContentStream content, PDFont font, float size,
                             float x, float y, String value) throws IOException {
        content.beginText();
        content.setFont(font, size);
        content.newLineAtOffset(x, y);
        content.showText(safe(value));
        content.endText();
    }

    private static float width(PDFont font, float size, String value) throws IOException {
        return font.getStringWidth(safe(value)) / 1000f * size;
    }

    private static String formatPrice(BigDecimal price) {
        if (price == null) {
            return "-";
        }
        return price.setScale(2, RoundingMode.HALF_UP).toPlainString().replace('.', ',');
    }

    private static String safe(String value) {
        return value == null ? "" : value.replace('\n', ' ').replace('\r', ' ');
    }

    private record FontSet(PDFont regular, PDFont bold) {
    }
}
