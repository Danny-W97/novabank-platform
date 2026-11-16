package org.demo.shopapp.infrastructure;

import com.redis.testcontainers.RedisContainer;
import org.demo.shopapp.cart.CartService;
import org.demo.shopapp.order.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.math.BigDecimal;
import static org.assertj.core.api.Assertions.*;

/** Real packaged bank + real shop services and databases; the proxy drops only the committed HTTP reply. */
@EnabledIfSystemProperty(named="novabank.real-payment-faults",matches="true")
@SpringBootTest(properties={"shop.orders.reconciliation-enabled=false","shop.orders.merchant-id=synthetic-merchant"})
@ActiveProfiles("test")
class RealBankTransportFaultIT {
    static PostgreSQLContainer bankDb,shopDb;static RedisContainer redis;static Process bank;
    static com.sun.net.httpserver.HttpServer proxy;static int bankPort;static JdbcTemplate bankSql;
    static final String MERCHANT=accountNumber(7002),SOURCE=accountNumber(7001);
    static final java.util.concurrent.atomic.AtomicBoolean dropped=new java.util.concurrent.atomic.AtomicBoolean();
    @DynamicPropertySource static void setup(DynamicPropertyRegistry properties)throws Exception{
        bankDb=new PostgreSQLContainer(DockerImageName.parse("postgres:18"));shopDb=new PostgreSQLContainer(DockerImageName.parse("postgres:18"));redis=new RedisContainer(DockerImageName.parse("redis:7.4"));
        try{
            bankDb.start();shopDb.start();redis.start();try(var socket=new java.net.ServerSocket(0)){bankPort=socket.getLocalPort();}
            var jar=Path.of("../target/Bank-demo-0.0.1-SNAPSHOT.jar").toAbsolutePath();
            if(!Files.isRegularFile(jar))throw new IllegalStateException("Build both isolated modules before this opt-in test");
            var command=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-Xmx256m","-XX:ActiveProcessorCount=2","-jar",jar.toString(),
                "--spring.profiles.active=test","--server.address=127.0.0.1","--server.port="+bankPort,"--management.server.port=-1","--app.retention.enabled=false","--app.transfer.scheduler-delay-ms=3600000");
            var env=command.environment();env.clear();env.put("PATH",System.getenv().getOrDefault("PATH",""));
            env.put("TEST_POSTGRES_URL",bankDb.getJdbcUrl());env.put("TEST_POSTGRES_USER",bankDb.getUsername());env.put("TEST_POSTGRES_PASSWORD",bankDb.getPassword());
            env.put("TEST_REDIS_HOST",redis.getHost());env.put("TEST_REDIS_PORT",redis.getFirstMappedPort().toString());env.put("TEST_SHOP_MERCHANT_ACCOUNT",MERCHANT);
            env.put("TEST_TOTP_ENCRYPTION_KEY",Base64.getEncoder().encodeToString(new java.security.SecureRandom().generateSeed(32)));
            bank=command.redirectErrorStream(true).redirectOutput(Path.of("target/real-bank-transport.log").toFile()).start();
            var http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();boolean ready=false;var until=Instant.now().plusSeconds(100);
            while(Instant.now().isBefore(until)&&bank.isAlive()){
                try{ready=http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+bankPort+"/login")).timeout(Duration.ofSeconds(2)).GET().build(),HttpResponse.BodyHandlers.discarding()).statusCode()==200;}catch(java.io.IOException ignored){}
                if(ready)break;Thread.sleep(200);
            }
            if(!ready)throw new IllegalStateException("Isolated bank did not start");
            bankSql=new JdbcTemplate(new DriverManagerDataSource(bankDb.getJdbcUrl(),bankDb.getUsername(),bankDb.getPassword()));
            long source=seed("source",SOURCE,1000),merchant=seed("merchant",MERCHANT,0);
            bankSql.update("INSERT INTO demo_cards(account_id,masked_number,online_number,demo_cvv,demo_expires_at,status,daily_limit,created_at,updated_at) VALUES (?,'**** 4242','4242424242424242','123','2034-12-31','ACTIVE',5000,now(),now())",source);
            proxy=com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            proxy.createContext("/",exchange->{
                try{
                    var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+bankPort+exchange.getRequestURI())).timeout(Duration.ofSeconds(15));
                    for(var header:List.of("X-API-Key","X-API-Key-Id","Content-Type","Idempotency-Key")){var value=exchange.getRequestHeaders().getFirst(header);if(value!=null)request.header(header,value);}
                    var response=http.send(request.method(exchange.getRequestMethod(),HttpRequest.BodyPublishers.ofByteArray(exchange.getRequestBody().readAllBytes())).build(),HttpResponse.BodyHandlers.ofByteArray());
                    if(exchange.getRequestMethod().equals("POST")&&exchange.getRequestURI().getPath().equals("/api/card-payments")&&response.statusCode()==200&&new String(response.body(),java.nio.charset.StandardCharsets.UTF_8).contains("COMPLETED")){dropped.set(true);return;}
                    exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(response.statusCode(),response.body().length);exchange.getResponseBody().write(response.body());
                }catch(Exception ignored){}finally{exchange.close();}
            });proxy.start();
            properties.add("spring.datasource.url",shopDb::getJdbcUrl);properties.add("spring.datasource.username",shopDb::getUsername);properties.add("spring.datasource.password",shopDb::getPassword);
            properties.add("shop.bank.base-url",()->"http://127.0.0.1:"+proxy.getAddress().getPort());properties.add("shop.orders.merchant-account-number",()->MERCHANT);
        }catch(Exception failure){cleanup();throw failure;}
    }
    static long seed(String name,String number,int balance){
        var owner=bankSql.queryForObject("INSERT INTO users(email,password_hash,first_name,last_name,role,status,created_at,updated_at,password_changed_at,email_verified) VALUES (?,'unused','Synthetic','Transport','USER','ACTIVE',now(),now(),now(),true) RETURNING id",Long.class,name+"@example.invalid");
        return bankSql.queryForObject("INSERT INTO accounts(account_number,owner_id,balance,currency,status,created_at,updated_at) VALUES (?,?,?,'PLN','ACTIVE',now(),now()) RETURNING id",Long.class,number,owner,balance);
    }
    static String accountNumber(long id){var body=String.format("%024d",id);return String.format("%02d",98-new java.math.BigInteger(body+"252100").mod(java.math.BigInteger.valueOf(97)).intValue())+body;}
    @AfterAll static void cleanup()throws Exception{if(proxy!=null)proxy.stop(0);if(bank!=null){bank.destroy();if(!bank.waitFor(10,TimeUnit.SECONDS))bank.destroyForcibly().waitFor();}if(redis!=null)redis.stop();if(shopDb!=null)shopDb.stop();if(bankDb!=null)bankDb.stop();}
    @Autowired JdbcTemplate jdbc;@Autowired CartService carts;@Autowired OrderService orders;@Autowired CardOrderPaymentService cards;
    @Autowired PaymentAttemptService states;@Autowired PaymentAttemptRepository attempts;@Autowired PaymentResolutionService resolution;
    @Test @Timeout(60) void committedRealBankDebitWithLostShopReplyBlocksNewKeyAndReconciles()throws Exception{
        var customer=UUID.randomUUID().toString();long product=jdbc.queryForObject("INSERT INTO shop_products(sku,name,description,price,currency,active) VALUES (?,'Synthetic transport','Isolated',10,'PLN',true) RETURNING id",Long.class,UUID.randomUUID().toString());
        carts.addItem(customer,product,1);var order=orders.create(customer);var key=UUID.randomUUID().toString();
        var result=cards.pay(order.id(),customer,key,new CardOrderPaymentRequest("4242424242424242","123","12/34","PLN",BigDecimal.TEN));
        assertThat(dropped).isTrue();assertThat(result.status()).isEqualTo("UNKNOWN");
        assertThat(bankSql.queryForObject("SELECT balance FROM accounts WHERE account_number=?",BigDecimal.class,SOURCE)).isEqualByComparingTo("990");
        assertThat(bankSql.queryForObject("SELECT balance FROM accounts WHERE account_number=?",BigDecimal.class,MERCHANT)).isEqualByComparingTo("10");
        assertThatThrownBy(()->states.begin(order.id(),customer,UUID.randomUUID().toString(),"BANK",null,null)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        var attempt=attempts.byKey(key).orElseThrow();resolution.refresh(attempt);
        assertThat(jdbc.queryForObject("SELECT status FROM shop_orders WHERE id=?",String.class,order.id())).isEqualTo("PAID");
        assertThat(cards.pay(order.id(),customer,key,new CardOrderPaymentRequest("4242424242424242","123","12/34","PLN",BigDecimal.TEN)).status()).isEqualTo("PAID");
        assertThat(bankSql.queryForObject("SELECT count(*) FROM card_transactions WHERE idempotency_key=?",Integer.class,key)).isEqualTo(1);
        assertThat(bankSql.queryForObject("SELECT balance FROM accounts WHERE account_number=?",BigDecimal.class,SOURCE)).isEqualByComparingTo("990");
        assertThat(carts.getCart(customer).items()).isEmpty();
    }
}
