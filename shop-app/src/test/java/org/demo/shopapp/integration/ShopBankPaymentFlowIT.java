package org.demo.shopapp.integration;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class ShopBankPaymentFlowIT {

    private static final String API_KEY = "test-api-key";
    private static final String API_KEY_ID = "test";
    private static final String MERCHANT_ACCOUNT = "00000000000000000000000000";
    private static final String TEST_EMAIL = "payment-flow@example.com";
    private static final String TEST_PASSWORD = "Payment-flow@1";
    private static final String FAILURE_EMAIL = "payment-failure@example.com";

    private static final PostgreSQLContainer POSTGRES;
    private static final HttpServer BANK_SERVER;
    private static final AtomicReference<String> CREATED_ORDER_ID = new AtomicReference<>();
    private static final AtomicReference<String> CREATED_AMOUNT = new AtomicReference<>();
    private static final AtomicReference<String> CREATED_PAYMENT_KEY = new AtomicReference<>();
    private static final AtomicReference<String> CREATED_AUTHORIZATION_ID = new AtomicReference<>();
    private static final AtomicInteger AUTHORIZATION_SEQUENCE = new AtomicInteger();
    private static final AtomicInteger CREATE_REQUEST_COUNT = new AtomicInteger();
    private static final AtomicBoolean CREATE_REQUEST_RECEIVED = new AtomicBoolean();
    private static final AtomicBoolean REDEEM_REQUEST_RECEIVED = new AtomicBoolean();

    static {
        var image = DockerImageName.parse("pgvector/pgvector:pg16")
                .asCompatibleSubstituteFor("postgres");
        POSTGRES = new PostgreSQLContainer(image);
        POSTGRES.start();

        try {
            BANK_SERVER = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            BANK_SERVER.createContext("/api/payment-authorizations",
                    ShopBankPaymentFlowIT::handleBankRequest);
            BANK_SERVER.start();
        } catch (IOException exception) {
            POSTGRES.stop();
            throw new ExceptionInInitializerError(exception);
        }
    }

    @LocalServerPort
    private int shopPort;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @DynamicPropertySource
    static void registerContainerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("shop.bank.base-url",
                () -> "http://127.0.0.1:" + BANK_SERVER.getAddress().getPort());
        registry.add("shop.bank.api-key", () -> API_KEY);
        registry.add("shop.bank.api-key-id", () -> API_KEY_ID);
        registry.add("shop.orders.merchant-account-number", () -> MERCHANT_ACCOUNT);
    }

    @BeforeEach
    void seedProduct() {
        jdbcTemplate.update(
                """
                INSERT INTO shop_products (sku, name, description, price, currency, active)
                VALUES ('HTTP-IT-001', 'HTTP integration product', 'Test product', 50.00, 'PLN', TRUE)
                ON CONFLICT (sku) DO UPDATE SET active = TRUE, price = 50.00
                """
        );
        CREATED_ORDER_ID.set(null);
        CREATED_AMOUNT.set(null);
        CREATED_PAYMENT_KEY.set(null);
        CREATED_AUTHORIZATION_ID.set(null);
        CREATE_REQUEST_COUNT.set(0);
        CREATE_REQUEST_RECEIVED.set(false);
        REDEEM_REQUEST_RECEIVED.set(false);
    }

    @AfterAll
    static void stopInfrastructure() {
        BANK_SERVER.stop(0);
        POSTGRES.stop();
    }

    @Test
    void completesPaymentOverHttpAndClearsCart() throws Exception {
        var client = new ShopHttpClient("http://127.0.0.1:" + shopPort, TEST_EMAIL, TEST_PASSWORD);
        var productId = jdbcTemplate.queryForObject(
                "SELECT id FROM shop_products WHERE sku = 'HTTP-IT-001'", Long.class);

        var csrf = client.csrf();
        assertEquals(201, client.register(csrf));
        assertTrue(client.login(csrf) / 100 == 3);
        csrf = client.csrf();

        assertEquals(204, client.addToCart(csrf, productId));
        var cartBeforePayment = client.get("/api/cart");
        assertEquals(200, cartBeforePayment.statusCode());
        assertTrue(cartBeforePayment.body().contains("HTTP integration product"));

        var orderResponse = client.createOrder(csrf);
        assertEquals(201, orderResponse.statusCode());
        var orderId = jsonLong(orderResponse.body(), "id");
        assertEquals("PENDING", jsonString(orderResponse.body(), "status"));

        var paymentResponse = client.pay(csrf, orderId, "payment-flow-idempotency-key");
        assertEquals(200, paymentResponse.statusCode());
        assertEquals("PENDING", jsonString(paymentResponse.body(), "status"));
        assertTrue(jsonString(paymentResponse.body(), "authorizationUrl").contains("bank"));
        assertTrue(CREATE_REQUEST_RECEIVED.get());

        var repeatedPaymentResponse = client.pay(csrf, orderId, "payment-flow-idempotency-key");
        assertEquals(200, repeatedPaymentResponse.statusCode());
        assertEquals(
                jsonString(paymentResponse.body(), "authorizationUrl"),
                jsonString(repeatedPaymentResponse.body(), "authorizationUrl")
        );
        assertEquals(2, CREATE_REQUEST_COUNT.get());

        var authorization = jdbcTemplate.queryForMap(
                "SELECT bank_authorization_id, payment_state FROM shop_orders WHERE id = ?", orderId);
        var authorizationId = (String) authorization.get("bank_authorization_id");
        var state = (String) authorization.get("payment_state");

        var callback = client.get("/payment/callback?authorizationId="
                + encode(authorizationId)
                + "&state=" + encode(state)
                + "&status=approved&code=bank-confirmation-code");
        assertEquals(302, callback.statusCode());
        assertTrue(callback.headers().firstValue("location").orElse("").contains("payment=paid"));
        assertTrue(REDEEM_REQUEST_RECEIVED.get());

        var paidOrder = client.get("/api/orders/" + orderId);
        assertEquals(200, paidOrder.statusCode());
        assertEquals("PAID", jsonString(paidOrder.body(), "status"));
        assertEquals("BANK-IT-REFERENCE", jsonString(paidOrder.body(), "bankReference"));

        var cartAfterPayment = client.get("/api/cart");
        assertEquals(200, cartAfterPayment.statusCode());
        assertTrue(jsonArrayIsEmpty(cartAfterPayment.body(), "items"));
    }

    @Test
    void marksOrderFailedWhenBankReportsInsufficientFunds() throws Exception {
        var client = new ShopHttpClient(
                "http://127.0.0.1:" + shopPort,
                FAILURE_EMAIL,
                TEST_PASSWORD
        );
        var productId = jdbcTemplate.queryForObject(
                "SELECT id FROM shop_products WHERE sku = 'HTTP-IT-001'", Long.class);

        var csrf = client.csrf();
        assertEquals(201, client.register(csrf));
        assertTrue(client.login(csrf) / 100 == 3);
        csrf = client.csrf();
        assertEquals(204, client.addToCart(csrf, productId));

        var orderResponse = client.createOrder(csrf);
        var orderId = jsonLong(orderResponse.body(), "id");
        var paymentResponse = client.pay(csrf, orderId, "payment-failure-idempotency-key");
        assertEquals(200, paymentResponse.statusCode());
        assertEquals("PENDING", jsonString(paymentResponse.body(), "status"));

        var authorization = jdbcTemplate.queryForMap(
                "SELECT bank_authorization_id, payment_state FROM shop_orders WHERE id = ?", orderId);
        var callback = client.get("/payment/callback?authorizationId="
                + encode((String) authorization.get("bank_authorization_id"))
                + "&state=" + encode((String) authorization.get("payment_state"))
                + "&status=failed&reason=insufficient_funds");

        assertEquals(302, callback.statusCode());
        assertTrue(callback.headers().firstValue("location").orElse("").contains("payment=failed"));
        var failedOrder = client.get("/api/orders/" + orderId);
        assertEquals("FAILED", jsonString(failedOrder.body(), "status"));
        assertTrue(jsonString(failedOrder.body(), "failureReason").contains("rodk"));
        assertTrue(!REDEEM_REQUEST_RECEIVED.get());
    }

    private static void handleBankRequest(HttpExchange exchange) throws IOException {
        try (exchange) {
            if (!API_KEY.equals(exchange.getRequestHeaders().getFirst("X-API-Key"))
                    || !API_KEY_ID.equals(exchange.getRequestHeaders().getFirst("X-API-Key-Id"))) {
                respond(exchange, 401, "{\"error\":\"invalid api key\"}");
                return;
            }

            var body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            var path = exchange.getRequestURI().getPath();
            if ("/api/payment-authorizations".equals(path)) {
                CREATED_ORDER_ID.set(jsonString(body, "shopOrderId"));
                CREATED_AMOUNT.set(jsonNumber(body, "amount"));
                var paymentKey = jsonString(body, "paymentIdempotencyKey");
                if (!paymentKey.equals(CREATED_PAYMENT_KEY.get())) {
                    CREATED_PAYMENT_KEY.set(paymentKey);
                    CREATED_AUTHORIZATION_ID.set("auth-http-it-" + AUTHORIZATION_SEQUENCE.incrementAndGet());
                    CREATE_REQUEST_COUNT.incrementAndGet();
                } else {
                    CREATE_REQUEST_COUNT.incrementAndGet();
                }
                CREATE_REQUEST_RECEIVED.set(true);
                respond(exchange, 200, """
                        {"authorizationId":"%s","authorizationUrl":"http://bank.test/authorize/%s","state":"bank-state","expiresAt":"2099-01-01T00:00:00Z"}
                        """.formatted(CREATED_AUTHORIZATION_ID.get(), CREATED_AUTHORIZATION_ID.get()));
                return;
            }

            if (("/api/payment-authorizations/" + CREATED_AUTHORIZATION_ID.get() + "/redeem").equals(path)) {
                REDEEM_REQUEST_RECEIVED.set(true);
                respond(exchange, 200, "{" +
                        "\"orderId\":\"" + CREATED_ORDER_ID.get() + "\"," +
                        "\"amount\":" + CREATED_AMOUNT.get() + "," +
                        "\"currency\":\"PLN\",\"status\":\"COMPLETED\",\"referenceNumber\":\"BANK-IT-REFERENCE\"," +
                        "\"customerAccountNumber\":\"11111111111111111111111111\"}");
                return;
            }

            respond(exchange, 404, "{\"error\":\"not found\"}");
        }
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        var bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    private static String jsonString(String json, String field) {
        Matcher matcher = Pattern.compile("\\\"" + Pattern.quote(field)
                + "\\\"\\s*:\\s*\\\"([^\\\"]*)\\\"").matcher(json);
        if (!matcher.find()) {
            throw new AssertionError("Missing JSON field: " + field + " in " + json);
        }
        return matcher.group(1);
    }

    private static String jsonNumber(String json, String field) {
        Matcher matcher = Pattern.compile("\\\"" + Pattern.quote(field)
                + "\\\"\\s*:\\s*([0-9]+(?:\\.[0-9]+)?)").matcher(json);
        if (!matcher.find()) {
            throw new AssertionError("Missing JSON number: " + field + " in " + json);
        }
        return new BigDecimal(matcher.group(1)).toPlainString();
    }

    private static long jsonLong(String json, String field) {
        return Long.parseLong(jsonStringOrNumber(json, field));
    }

    private static String jsonStringOrNumber(String json, String field) {
        Matcher matcher = Pattern.compile("\\\"" + Pattern.quote(field)
                + "\\\"\\s*:\\s*(?:\\\"([^\\\"]*)\\\"|([0-9]+))").matcher(json);
        if (!matcher.find()) {
            throw new AssertionError("Missing JSON value: " + field + " in " + json);
        }
        return matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
    }

    private static boolean jsonArrayIsEmpty(String json, String field) {
        return Pattern.compile("\\\"" + Pattern.quote(field) + "\\\"\\s*:\\s*\\[\\s*\\]")
                .matcher(json)
                .find();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static final class ShopHttpClient {

        private final String baseUrl;
        private final String email;
        private final String password;
        private final HttpClient client = HttpClient.newBuilder()
                .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        private String csrfToken;

        private ShopHttpClient(String baseUrl, String email, String password) {
            this.baseUrl = baseUrl;
            this.email = email;
            this.password = password;
        }

        private String csrf() throws Exception {
            var response = send("GET", "/api/auth/csrf", null, null);
            assertEquals(200, response.statusCode());
            csrfToken = jsonString(response.body(), "token");
            return csrfToken;
        }

        private int register(String csrf) throws Exception {
            return send("POST", "/api/auth/register",
                    "{\"email\":\"" + email + "\",\"password\":\""
                            + password + "\",\"passwordConfirmation\":\""
                            + password + "\"}", csrf).statusCode();
        }

        private int login(String csrf) throws Exception {
            var form = "username=" + encode(email) + "&password=" + encode(password);
            return send("POST", "/login", form, csrf, "application/x-www-form-urlencoded").statusCode();
        }

        private int addToCart(String csrf, long productId) throws Exception {
            return send("POST", "/api/cart/items", "{\"productId\":" + productId + ",\"quantity\":1}", csrf)
                    .statusCode();
        }

        private HttpResponse<String> createOrder(String csrf) throws Exception {
            return send("POST", "/api/orders", "{}", csrf);
        }

        private HttpResponse<String> pay(String csrf, long orderId, String idempotencyKey) throws Exception {
            return send("POST", "/api/orders/" + orderId + "/pay", null, csrf, null, idempotencyKey);
        }

        private HttpResponse<String> get(String path) throws Exception {
            return send("GET", path, null, null);
        }

        private HttpResponse<String> send(
                String method,
                String path,
                String body,
                String csrf
        ) throws Exception {
            return send(method, path, body, csrf, "application/json");
        }

        private HttpResponse<String> send(
                String method,
                String path,
                String body,
                String csrf,
                String contentType
        ) throws Exception {
            return send(method, path, body, csrf, contentType, null);
        }

        private HttpResponse<String> send(
                String method,
                String path,
                String body,
                String csrf,
                String contentType,
                String idempotencyKey
        ) throws Exception {
            var builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
                    .timeout(Duration.ofSeconds(10));
            if (contentType != null) {
                builder.header("Content-Type", contentType);
            }
            if (csrf != null) {
                builder.header("X-XSRF-TOKEN", csrf);
            }
            if (idempotencyKey != null) {
                builder.header("Idempotency-Key", idempotencyKey);
            }
            var publisher = body == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body);
            builder.method(method, publisher);
            return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        }
    }
}
