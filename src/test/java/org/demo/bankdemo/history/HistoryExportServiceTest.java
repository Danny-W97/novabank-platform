package org.demo.bankdemo.history;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HistoryExportServiceTest {

    @Test
    void createsUtf8CsvAndNeutralizesSpreadsheetFormula() {
        var historyService = mock(HistoryService.class);
        var filter = new HistoryFilter();
        when(historyService.exportRowsFor("user@example.pl", filter)).thenReturn(List.of(
                new HistoryService.ExportRow(
                        "27 July 2026, 08:00",
                        "Transfer",
                        "Outgoing",
                        "Anna",
                        "=HYPERLINK(\"bad\")",
                        "-10.00 PLN",
                        "PLN",
                        "Completed",
                        "TRX-1"
                )
        ));
        var service = new HistoryExportService(historyService);

        var csv = new String(service.csv("user@example.pl", filter), StandardCharsets.UTF_8);

        assertThat(csv).startsWith("\uFEFFData;");
        assertThat(csv).contains("\"'=HYPERLINK(\"\"bad\"\")\"");
        assertThat(csv).contains(";\"PLN\";");
    }

    @Test
    void createsPdfConfirmation() throws Exception {
        var historyService = mock(HistoryService.class);
        when(historyService.detailFor("user@example.pl", 9L)).thenReturn(new HistoryDetail(
                9L,
                "TRX-123",
                "Transfer",
                "Completed",
                "Outgoing",
                "Łukasz",
                "61 1090 1014 0000 0712 1981 2874",
                "12 1279 4313 8124 6631 4704 2900",
                "-10.00 PLN",
                "Test PDF",
                "27 July 2026, 08:00",
                "27 July 2026, 08:00"
        ));
        var service = new HistoryExportService(historyService);

        var pdf = service.confirmationPdf("user@example.pl", 9L);

        assertThat(pdf).startsWith("%PDF-".getBytes(StandardCharsets.US_ASCII));
        assertThat(pdf.length).isGreaterThan(500);
        try (var document = Loader.loadPDF(pdf)) {
            var text = new PDFTextStripper().getText(document);
            assertThat(text).contains("Potwierdzenie operacji", "Łukasz", "Twój rachunek");
            assertThat(text).doesNotContain("?");
        }
    }

    @Test
    void createsPdfForFilteredHistory() {
        var historyService = mock(HistoryService.class);
        var filter = new HistoryFilter();
        when(historyService.exportRowsFor("user@example.pl", filter)).thenReturn(List.of(
                new HistoryService.ExportRow(
                        "27 July 2026, 08:00",
                        "Transfer",
                        "Outgoing",
                        "Shop",
                        "Purchase",
                        "-10.00 EUR",
                        "EUR",
                        "Completed",
                        "TRX-2"
                )
        ));
        var service = new HistoryExportService(historyService);

        var pdf = service.pdf("user@example.pl", filter);

        assertThat(pdf).startsWith("%PDF-".getBytes(StandardCharsets.US_ASCII));
        assertThat(pdf.length).isGreaterThan(500);
    }
}
