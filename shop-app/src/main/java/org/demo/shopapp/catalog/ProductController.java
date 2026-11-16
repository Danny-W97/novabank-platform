package org.demo.shopapp.catalog;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.List;

@RestController
@RequestMapping("/api/products")
public class ProductController {

    private final ProductService productService;
    private final ProductCatalogPdfService productCatalogPdfService;

    public ProductController(ProductService productService, ProductCatalogPdfService productCatalogPdfService) {
        this.productService = productService;
        this.productCatalogPdfService = productCatalogPdfService;
    }

    /**
     * GET /api/products?category=laptop&search=macbook&sort=price_asc
     *
     * @param category optional category filter (e.g. "laptop", "phone", "audio")
     * @param search   optional full-text search against name, brand, description
     * @param sort     optional sort: "price_asc" | "price_desc" | "name_asc" | "newest" (default: id asc)
     */
    @GetMapping
    List<ProductResponse> list(
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String sort
    ) {
        return productService.listActive(category, search, sort);
    }

    @GetMapping(value = "/catalog.pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    ResponseEntity<byte[]> catalogPdf() {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename("novatech-katalog.pdf").build().toString())
                .contentType(MediaType.APPLICATION_PDF)
                .body(productCatalogPdfService.create());
    }

    @GetMapping("/{id}")
    ProductResponse getById(@PathVariable long id) {
        return productService.getActiveById(id);
    }
}
