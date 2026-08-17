package org.demo.bankdemo.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

@Controller
public class TermsController {

    private static final String DOWNLOAD_NAME = "regulamin_novabank.pdf";

    private final Path termsFile;

    TermsController(
            @Value("${app.terms.file:regulamin/regulamin_novabank.pdf}")
            String configuredPath
    ) {
        var path = Path.of(configuredPath);
        this.termsFile = (path.isAbsolute()
                ? path
                : Path.of(System.getProperty("user.dir")).resolve(path))
                .normalize();
    }

    @GetMapping(value = "/terms", produces = MediaType.APPLICATION_PDF_VALUE)
    ResponseEntity<Resource> downloadTerms() throws IOException {
        if (!Files.isRegularFile(termsFile)) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Plik regulaminu nie jest dostępny."
            );
        }

        return ResponseEntity.ok()
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment()
                                .filename(DOWNLOAD_NAME, StandardCharsets.UTF_8)
                                .build()
                                .toString()
                )
                .contentType(MediaType.APPLICATION_PDF)
                .contentLength(Files.size(termsFile))
                .body(new FileSystemResource(termsFile));
    }
}
