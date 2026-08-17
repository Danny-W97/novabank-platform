package org.demo.shopapp.catalog;

import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ProductService {

    private final ProductRepository productRepository;

    public ProductService(ProductRepository productRepository) {
        this.productRepository = productRepository;
    }

    public List<ProductResponse> listActive(String category, String search, String sort) {
        return productRepository.findActive(category, search, sort);
    }

    public ProductResponse getActiveById(long id) {
        return productRepository.findActiveById(id);
    }
}
