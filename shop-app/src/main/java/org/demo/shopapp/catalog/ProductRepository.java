package org.demo.shopapp.catalog;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Repository;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;

@Repository
public class ProductRepository {

    private final JdbcTemplate jdbcTemplate;

    public ProductRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private static final String SELECT_COLS = """
            SELECT id, sku, name, description, price, old_price, currency, active,
                   category, emoji, brand, tag, stars, reviews
            FROM shop_products
            """;

    private ProductResponse mapRow(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new ProductResponse(
                rs.getLong("id"),
                rs.getString("sku"),
                rs.getString("name"),
                rs.getString("description"),
                rs.getBigDecimal("price"),
                rs.getBigDecimal("old_price"),
                rs.getString("currency"),
                rs.getBoolean("active"),
                rs.getString("category"),
                rs.getString("emoji"),
                rs.getString("brand"),
                rs.getString("tag"),
                rs.getObject("stars", Integer.class),
                rs.getObject("reviews", Integer.class)
        );
    }

    public List<ProductResponse> findActive(String category, String search, String sort) {
        StringBuilder sql = new StringBuilder(SELECT_COLS).append("WHERE active = TRUE");
        List<Object> params = new ArrayList<>();

        if (category != null && !category.isBlank() && !category.equals("all")) {
            sql.append(" AND category = ?");
            params.add(category.strip());
        }
        if (search != null && !search.isBlank()) {
            sql.append(" AND (LOWER(name) LIKE ? OR LOWER(brand) LIKE ? OR LOWER(description) LIKE ?)");
            String like = "%" + search.strip().toLowerCase() + "%";
            params.add(like);
            params.add(like);
            params.add(like);
        }

        sql.append(switch (sort == null ? "" : sort) {
            case "price_asc"  -> " ORDER BY price ASC";
            case "price_desc" -> " ORDER BY price DESC";
            case "name_asc"   -> " ORDER BY name ASC";
            case "newest"     -> " ORDER BY id DESC";
            default           -> " ORDER BY id ASC";
        });

        return jdbcTemplate.query(sql.toString(), this::mapRow, params.toArray());
    }

    public ProductResponse findActiveById(long productId) {
        return jdbcTemplate.query(
                        SELECT_COLS + "WHERE id = ? AND active = TRUE",
                        this::mapRow,
                        productId
                ).stream()
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Product not found."
                ));
    }
}
