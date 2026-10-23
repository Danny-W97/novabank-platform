package org.demo.shopapp.infrastructure;

import org.demo.shopapp.cart.*;
import org.demo.shopapp.order.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties={"shop.orders.merchant-id=synthetic-merchant","shop.orders.reconciliation-enabled=false"})
@ActiveProfiles("test")
class PurchaseIdentityIT {
    static final PostgreSQLContainer POSTGRES=new PostgreSQLContainer(DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));
    static {POSTGRES.start();}
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r){r.add("spring.datasource.url",POSTGRES::getJdbcUrl);r.add("spring.datasource.username",POSTGRES::getUsername);r.add("spring.datasource.password",POSTGRES::getPassword);}
    @AfterAll static void stop(){POSTGRES.stop();}
    @Autowired JdbcTemplate jdbc;
    @Autowired PurchaseIdentityService purchases;
    @Autowired CartService carts;
    @Autowired OrderService orders;
    @Autowired OrderRepository repository;
    @Autowired PaymentAttemptService payments;
    @Autowired OrderCartCleanupService cleanup;
    String customer;long product;
    @BeforeEach void customer(){
        customer=UUID.randomUUID().toString();
        product=jdbc.queryForObject("INSERT INTO shop_products(sku,name,description,price,currency,active) VALUES (?,'Synthetic product','Isolated',10,'PLN',true) RETURNING id",Long.class,UUID.randomUUID().toString());
        carts.addItem(customer,product,2);
    }
    long legacy(){return jdbc.queryForObject("INSERT INTO shop_orders(order_number,customer_id,merchant_account_number,total,currency,status,payment_idempotency_key) VALUES (?,?,'22222222222222222222222222',20,'PLN','FAILED',?) RETURNING id",Long.class,UUID.randomUUID().toString(),customer,UUID.randomUUID().toString());}
    void allow(long old){jdbc.update("INSERT INTO shop_payment_cases(order_id,allow_new_purchase_at) VALUES (?,clock_timestamp())",old);}
    @Test void closingCaseDoesNotUnblockButReviewedIndependentPurchaseDoes(){
        long old=legacy();jdbc.update("INSERT INTO shop_payment_cases(order_id,workflow_status) VALUES (?,'CLOSED_UNRESOLVED')",old);
        assertThatThrownBy(()->orders.create(customer)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        var before=(String)purchases.status(customer).get("purchaseId");
        assertThatThrownBy(()->purchases.startIndependent(customer,before,true)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        jdbc.update("UPDATE shop_payment_cases SET allow_new_purchase_at=clock_timestamp() WHERE order_id=?",old);
        assertThatThrownBy(()->orders.create(customer)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        var next=purchases.startIndependent(customer,before,true);
        assertThat(next.get("purchaseId")).isNotEqualTo(before);assertThat(carts.getCart(customer).items()).isEmpty();
        assertThat(jdbc.queryForObject("SELECT status FROM shop_orders WHERE id=?",String.class,old)).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM shop_purchase_snapshots WHERE customer_id=?",Integer.class,customer)).isEqualTo(1);
        carts.addItem(customer,product,1);var created=orders.create(customer);
        assertThat(created.id()).isNotEqualTo(old);
        assertThatThrownBy(()->payments.begin(old,customer,UUID.randomUUID().toString(),"BANK",null,null)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }
    @Test void sameCheckoutHasOneOrderEvenConcurrentlyAndCartIsFrozen()throws Exception{
        try(var pool=Executors.newFixedThreadPool(2)){
            var barrier=new CyclicBarrier(2);
            var a=pool.submit(()->{barrier.await();return orders.create(customer);});
            var b=pool.submit(()->{barrier.await();return orders.create(customer);});
            assertThat(a.get(20,TimeUnit.SECONDS).id()).isEqualTo(b.get(20,TimeUnit.SECONDS).id());
        }
        assertThatThrownBy(()->carts.addItem(customer,product,1)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThatThrownBy(()->carts.removeItem(customer,product)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }
    @Test void lateSuccessCannotRemoveProductsFromExplicitlyNewPurchase(){
        var old=orders.create(customer);var attempt=payments.begin(old.id(),customer,UUID.randomUUID().toString(),"BANK",null,null).attempt();
        allow(old.id());var generation=(String)purchases.status(customer).get("purchaseId");
        purchases.startIndependent(customer,generation,true);carts.addItem(customer,product,5);
        var paid=payments.succeed(attempt,"SYNTHETIC-LATE-"+old.id(),"11111111111111111111111111");
        cleanup.cleanupPaidOrder(paid);cleanup.cleanupPaidOrder(paid);
        assertThat(carts.getCart(customer).items().getFirst().quantity()).isEqualTo(5);
        assertThat(repository.hasUnresolvedCardPayment(customer)).isFalse();
    }
    @Test void normalSuccessCleansOnceAndNextCheckoutHasDifferentIdentity(){
        var old=orders.create(customer);var attempt=payments.begin(old.id(),customer,UUID.randomUUID().toString(),"BANK",null,null).attempt();
        var paid=payments.succeed(attempt,"SYNTHETIC-PAID-"+old.id(),"11111111111111111111111111");
        cleanup.cleanupPaidOrder(paid);assertThat(carts.getCart(customer).items()).isEmpty();
        carts.addItem(customer,product,3);cleanup.cleanupPaidOrder(paid);
        assertThat(carts.getCart(customer).items().getFirst().quantity()).isEqualTo(3);
        assertThat(orders.create(customer).id()).isNotEqualTo(old.id());
    }
    @Test void independentTransitionIsOwnedConfirmedAndIdempotent(){
        var generation=(String)purchases.status(customer).get("purchaseId");
        assertThatThrownBy(()->purchases.startIndependent(customer,generation,false)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        var next=purchases.startIndependent(customer,generation,true);
        assertThat(purchases.startIndependent(customer,generation,true).get("purchaseId")).isEqualTo(next.get("purchaseId"));
        assertThatThrownBy(()->purchases.startIndependent("other-"+customer,generation,true)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }
    @Test void preUpgradeCartCannotBeSilentlyResubmitted(){
        jdbc.update("UPDATE shop_carts SET requires_independent_ack=true WHERE customer_id=?",customer);
        assertThatThrownBy(()->orders.create(customer)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        var state=purchases.status(customer);assertThat(state.get("canStartIndependent")).isEqualTo(true);
        purchases.startIndependent(customer,(String)state.get("purchaseId"),true);
        assertThat(carts.getCart(customer).items()).isEmpty();
        assertThat(purchases.status(customer).get("requiresIndependentAcknowledgement")).isEqualTo(false);
    }
    @Test void permissionDoesNotCoverFutureUnknownPurchasesAndRevocationIsEffective(){
        var first=legacy();allow(first);purchases.startIndependent(customer,(String)purchases.status(customer).get("purchaseId"),true);
        carts.addItem(customer,product,1);var fresh=orders.create(customer);
        payments.begin(fresh.id(),customer,UUID.randomUUID().toString(),"BANK",null,null);
        assertThat(purchases.status(customer).get("canStartIndependent")).isEqualTo(false);
        jdbc.update("UPDATE shop_payment_cases SET allow_new_purchase_at=NULL WHERE order_id=?",first);
        assertThat(repository.hasUnresolvedCardPayment(customer)).isTrue();
    }
}
