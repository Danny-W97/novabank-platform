package org.demo.shopapp.payment;

import com.sun.net.httpserver.HttpServer;
import org.demo.shopapp.config.ShopPaymentProperties;
import org.junit.jupiter.api.*;
import org.springframework.web.client.RestClient;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.*;
import static org.assertj.core.api.Assertions.*;

/** Local synthetic provider; no actual Stripe account, secret or payment. */
class StripeCancellationClientTest {
    HttpServer server;StripeCheckoutClient client;
    AtomicReference<String> state=new AtomicReference<>("open");
    AtomicReference<String> afterPost=new AtomicReference<>("expired");
    AtomicReference<String> idempotency=new AtomicReference<>();
    AtomicInteger posts=new AtomicInteger(),gets=new AtomicInteger(),postStatus=new AtomicInteger(200);
    @BeforeEach void start()throws Exception{
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/v1/checkout/sessions/cs_test_fixture",exchange->{
            int code=200;String body;
            if("POST".equals(exchange.getRequestMethod())){
                posts.incrementAndGet();idempotency.set(exchange.getRequestHeaders().getFirst("Idempotency-Key"));
                code=postStatus.get();state.set(afterPost.get());body=code==200?"{}":"{\"error\":{\"message\":\"synthetic provider race\"}}";
            }else{gets.incrementAndGet();body="{\"id\":\"cs_test_fixture\",\"status\":\""+state.get()+"\",\"payment_intent\":null}";}
            byte[] bytes=body.getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(code,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
        });server.start();
        var properties=new ShopPaymentProperties();properties.setProvider("stripe");properties.setApiKey("sk_test_synthetic_fixture");properties.setApiBaseUrl("http://127.0.0.1:"+server.getAddress().getPort());client=new StripeCheckoutClient(RestClient.builder(),properties);
    }
    @AfterEach void stop(){server.stop(0);}
    @Test void openSessionIsExpiredAndRereadWithStableOperationKey(){
        var result=client.cancellationStatus("cs_test_fixture","synthetic-request");
        assertThat(result.get("status")).isEqualTo("expired");assertThat(posts).hasValue(1);assertThat(gets).hasValue(2);assertThat(idempotency.get()).isEqualTo("cancel-synthetic-request");
    }
    @Test void completedSessionIsNeverExpired(){
        state.set("complete");assertThat(client.cancellationStatus("cs_test_fixture","synthetic-request").get("status")).isEqualTo("complete");assertThat(posts).hasValue(0);
    }
    @Test void completionWinningExpireRaceIsRereadRatherThanDeclaredCancelled(){
        postStatus.set(400);afterPost.set("complete");assertThat(client.cancellationStatus("cs_test_fixture","synthetic-request").get("status")).isEqualTo("complete");assertThat(gets).hasValue(2);
    }
    @Test void providerFailureIsNotAConfirmedCancellation(){
        postStatus.set(503);assertThatThrownBy(()->client.cancellationStatus("cs_test_fixture","synthetic-request")).isInstanceOf(org.springframework.web.client.RestClientResponseException.class);assertThat(gets).hasValue(1);
    }
}
