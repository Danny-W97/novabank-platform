package org.demo.shopapp.infrastructure;

import com.redis.testcontainers.RedisContainer;
import org.demo.shopapp.cart.CartService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("test")
class ShopContainersIT {

    private static final PostgreSQLContainer POSTGRES;
    private static final RedisContainer REDIS;

    static {
        var image = DockerImageName.parse("pgvector/pgvector:pg16")
                .asCompatibleSubstituteFor("postgres");
        POSTGRES = new PostgreSQLContainer(image);
        REDIS = new RedisContainer(DockerImageName.parse("redis:6.2.6"));
        POSTGRES.start();
        REDIS.start();
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private CartService cartService;

    @DynamicPropertySource
    static void registerContainerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("shop.test.redis.host", REDIS::getHost);
        registry.add("shop.test.redis.port", REDIS::getFirstMappedPort);
    }

    @AfterAll
    static void stopContainers() {
        REDIS.stop();
        POSTGRES.stop();
    }

    @Test
    void runsFlywayMigrationsAndStartsRedisContainer() {
        var migrationCount = jdbcTemplate.queryForObject(
                "select count(*) from flyway_schema_history", Integer.class);
        assertTrue(migrationCount != null && migrationCount > 0);
        assertTrue(REDIS.isRunning());
    }

    @Test
    void paidOrderCleanupPreservesNewQuantitiesAndOtherProducts() {
        var customerId = "cart-cleanup-test";
        var mouseId = productId("NOVATECH-101");
        var keyboardId = productId("NOVATECH-104");
        cartService.addItem(customerId, mouseId, 3);
        cartService.addItem(customerId, keyboardId, 2);

        cartService.removePurchasedQuantities(customerId, Map.of(mouseId, 2));

        var cart = cartService.getCart(customerId);
        assertEquals(2, cart.items().size());
        assertEquals(1, quantity(cart, mouseId));
        assertEquals(2, quantity(cart, keyboardId));
    }

    private long productId(String sku) {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM shop_products WHERE sku = ?", Long.class, sku
        );
    }

    private static int quantity(org.demo.shopapp.cart.CartResponse cart, long productId) {
        return cart.items().stream()
                .filter(item -> item.productId().equals(productId))
                .findFirst()
                .orElseThrow()
                .quantity();
    }
}
