package org.demo.shopapp.catalog;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProductCatalogPdfServiceTest {

    @Test
    void createsReadablePdfForActiveProducts() throws Exception {
        var productService = mock(ProductService.class);
        when(productService.listActive(null, null, "name_asc")).thenReturn(List.of(
                new ProductResponse(
                        1L,
                        "NOVATECH-TEST",
                        "Telefon z polskimi znakami: żółć",
                        "Opis produktu z ę, ł i ś.",
                        new BigDecimal("1299.00"),
                        null,
                        "PLN",
                        true,
                        "phone",
                        "",
                        "NovaTech",
                        "new",
                        5,
                        12
                )
        ));

        var pdf = new ProductCatalogPdfService(productService).create();

        assertThat(pdf).startsWith("%PDF-".getBytes());
        try (PDDocument document = Loader.loadPDF(pdf)) {
            assertThat(document.getNumberOfPages()).isEqualTo(1);
            var text = new PDFTextStripper().getText(document);
            assertThat(text).contains("Telefon z polskimi znakami: żółć");
            assertThat(text).doesNotContain("Telefon z polskimi znakami: żółćTelefon");
        }
    }
}
