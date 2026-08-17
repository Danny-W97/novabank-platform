package org.demo.bankdemo.web;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TermsControllerTest {

    @TempDir
    Path tempDirectory;

    @Test
    void returnsPdfAsAttachment() throws Exception {
        var pdf = tempDirectory.resolve("regulamin.pdf");
        Files.write(pdf, "%PDF-1.4 test".getBytes());
        var controller = new TermsController(pdf.toString());

        var response = controller.downloadTerms();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getContentType())
                .isEqualTo(MediaType.APPLICATION_PDF);
        assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
                .contains("attachment")
                .contains("regulamin_novabank.pdf");
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().exists()).isTrue();
    }

    @Test
    void returnsNotFoundWhenPdfDoesNotExist() {
        var missingPdf = tempDirectory.resolve("brak.pdf");
        var controller = new TermsController(missingPdf.toString());

        assertThatThrownBy(controller::downloadTerms)
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(exception -> assertThat(
                        ((ResponseStatusException) exception).getStatusCode()
                ).isEqualTo(HttpStatus.NOT_FOUND));
    }
}
