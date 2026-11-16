package org.demo.shopapp.infrastructure;

import org.demo.shopapp.order.*;
import org.demo.shopapp.payment.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@SpringBootTest(properties="shop.orders.merchant-id=synthetic-merchant")
@ActiveProfiles("test")
class PaymentAttemptConcurrencyIT {
    static final PostgreSQLContainer POSTGRES=new PostgreSQLContainer(DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));
    static { POSTGRES.start(); }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username",POSTGRES::getUsername);
        r.add("spring.datasource.password",POSTGRES::getPassword);
    }
    @AfterAll static void stop() { POSTGRES.stop(); }
    @Autowired OrderRepository orders;
    @Autowired PaymentAttemptRepository attempts;
    @Autowired PaymentAttemptService states;
    @Autowired OrderPaymentService payments;
    @Autowired PaymentResolutionService resolution;
    @Autowired PurchaseCancellationService cancellations;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean BankAuthorizationClient bank;
    @MockitoBean StripeCheckoutClient stripe;

    OrderRepository.OrderRow order() {
        return orders.create(UUID.randomUUID().toString(),"22222222222222222222222222",BigDecimal.TEN,"PLN",List.of());
    }
    @Test void historicalImportedSuccessDoesNotCleanCurrentCartOrEnterReconciliation(){
        var old=order();var live=order();
        var historical=states.begin(old.id(),old.customerId(),UUID.randomUUID().toString(),"BANK",null,null).attempt();
        var current=states.begin(live.id(),live.customerId(),UUID.randomUUID().toString(),"BANK",null,null).attempt();
        states.succeed(historical,"HISTORICAL-"+old.id(),"11111111111111111111111111");
        states.succeed(current,"CURRENT-"+live.id(),"11111111111111111111111111");
        jdbc.update("UPDATE shop_orders SET historical_imported_at=CURRENT_TIMESTAMP WHERE id=?",old.id());
        assertThat(orders.claimCartCleanup(old.id())).isFalse();
        assertThat(attempts.candidates(Instant.now().plusSeconds(10),1000)).extracting(PaymentAttempt::id)
                .doesNotContain(historical.id()).contains(current.id());
        assertThat(orders.claimCartCleanup(live.id())).isTrue();
        assertThat(orders.claimCartCleanup(live.id())).isFalse();
    }
    @Test void lostReplyBlocksNewKeyAndProviderButAuthoritativeStatusRecoversSuccess() {
        var order=order();var key=UUID.randomUUID().toString();
        when(bank.create(any())).thenThrow(new IllegalStateException("synthetic lost reply"));
        assertThat(payments.pay(order.id(),order.customerId(),key).status()).isEqualTo("UNKNOWN");
        var attempt=attempts.byKey(key).orElseThrow();
        assertThatThrownBy(()->payments.pay(order.id(),order.customerId(),UUID.randomUUID().toString()))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThatThrownBy(()->states.begin(order.id(),order.customerId(),UUID.randomUUID().toString(),"STRIPE",BigDecimal.TEN,"PLN"))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        when(bank.statusByKey(key)).thenReturn(new BankAuthorizationStatusResponse("auth-"+order.id(),order.orderNumber(),
                BigDecimal.TEN,"PLN","COMPLETED","REF-"+order.id(),"11111111111111111111111111",null,Instant.now(),"synthetic-merchant"));
        resolution.refresh(attempt);
        assertThat(attempts.byKey(key).orElseThrow().state()).isEqualTo("SUCCEEDED");
        states.decline(attempt,"late browser cancellation");
        assertThat(orders.findById(order.id(),order.customerId()).status()).isEqualTo(OrderStatus.PAID);
        assertThat(payments.pay(order.id(),order.customerId(),"other-key").replayed()).isTrue();
        verify(bank,times(1)).create(any());
    }
    @Test void durableAttemptSurvivesLossBeforeHttpWithoutBlindRedispatch() {
        var order=order(); var key=UUID.randomUUID().toString();
        var stored=states.begin(order.id(),order.customerId(),key,"BANK",null,null).attempt();
        var response=payments.pay(order.id(),order.customerId(),key);
        assertThat(response.replayed()).isTrue();
        assertThat(attempts.byKey(key).orElseThrow().id()).isEqualTo(stored.id());
        verifyNoInteractions(bank);
        assertThatThrownBy(()->states.begin(order.id(),order.customerId(),UUID.randomUUID().toString(),"CARD",BigDecimal.TEN,"PLN"))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }
    @Test void lateSuccessFromOldAttemptCannotOverwriteNewAttempt() {
        var order=order();
        var old=states.begin(order.id(),order.customerId(),UUID.randomUUID().toString(),"BANK",null,null).attempt();
        states.decline(old,"authoritative terminal refusal");
        var next=states.begin(order.id(),order.customerId(),UUID.randomUUID().toString(),"BANK",null,null).attempt();
        assertThatThrownBy(()->states.succeed(old,"LATE-REF","11111111111111111111111111"))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThat(orders.findById(order.id(),order.customerId()).paymentIdempotencyKey()).isEqualTo(next.key());
        assertThat(attempts.byId(old.id()).orElseThrow().state()).isEqualTo("DECLINED");
        assertThat(attempts.byId(next.id()).orElseThrow().state()).isEqualTo("PROCESSING");
    }
    @Test void lateOldSuccessCannotOverwriteAlreadyPaidNewerAttempt() {
        var order=order();
        var old=states.begin(order.id(),order.customerId(),UUID.randomUUID().toString(),"BANK",null,null).attempt();
        states.decline(old,"authoritative terminal refusal");
        var next=states.begin(order.id(),order.customerId(),UUID.randomUUID().toString(),"CARD",BigDecimal.TEN,"PLN").attempt();
        states.succeed(next,"NEW-REF","11111111111111111111111111");
        assertThatThrownBy(()->states.succeed(old,"OLD-LATE-REF","11111111111111111111111111"))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        var persisted=orders.findById(order.id(),order.customerId());
        assertThat(persisted.status()).isEqualTo(OrderStatus.PAID);
        assertThat(persisted.bankReference()).isEqualTo("NEW-REF");
        assertThat(persisted.paymentIdempotencyKey()).isEqualTo(next.key());
        assertThat(attempts.byId(old.id()).orElseThrow().state()).isEqualTo("DECLINED");
        assertThat(attempts.byId(next.id()).orElseThrow().state()).isEqualTo("SUCCEEDED");
    }
    @Test void concurrentDifferentProvidersReserveOneOrderOnly() throws Exception {
        var order=order();
        java.util.function.Function<String,Boolean> start=provider->{
            try { states.begin(order.id(),order.customerId(),UUID.randomUUID().toString(),provider,BigDecimal.TEN,"PLN");return true; }
            catch(org.springframework.web.server.ResponseStatusException exception) { return false; }
        };
        assertThat(parallel(()->start.apply("BANK"),()->start.apply("STRIPE")).stream().filter(Boolean::booleanValue).count()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM shop_payment_attempts WHERE order_id=?",Integer.class,order.id())).isEqualTo(1);
    }
    @Test void duplicateSuccessAndLateFailureKeepOriginalReferenceAndPaidState() throws Exception {
        var order=order();
        var attempt=states.begin(order.id(),order.customerId(),UUID.randomUUID().toString(),"BANK",null,null).attempt();
        parallel(()->states.succeed(attempt,"SAME-REF","11111111111111111111111111"),
                ()->states.succeed(attempt,"SAME-REF","11111111111111111111111111"));
        states.decline(attempt,"late cancellation"); states.unknown(attempt);
        assertThat(orders.findById(order.id(),order.customerId()).status()).isEqualTo(OrderStatus.PAID);
        assertThat(orders.findById(order.id(),order.customerId()).bankReference()).isEqualTo("SAME-REF");
        assertThat(attempts.byId(attempt.id()).orElseThrow().state()).isEqualTo("SUCCEEDED");
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"currency","provider"})
    void sameKeyCannotBeReusedWithChangedCurrencyOrProvider(String field) {
        var order=order();var key=UUID.randomUUID().toString();
        var first=states.begin(order.id(),order.customerId(),key,"CARD",BigDecimal.TEN,"PLN").attempt();
        assertThatThrownBy(()->states.begin(order.id(),order.customerId(),key,
                field.equals("provider")?"BANK":"CARD",BigDecimal.TEN,field.equals("currency")?"EUR":"PLN"))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThat(attempts.byKey(key).orElseThrow()).isEqualTo(first);
    }
    @Test void signedStripeWebhookReplayChangesFinancialStateOnlyOnce() throws Exception {
        var order=order();
        var attempt=states.begin(order.id(),order.customerId(),UUID.randomUUID().toString(),"STRIPE",BigDecimal.TEN,"PLN").attempt();
        var secret=UUID.randomUUID().toString();var mvc=webhookMvc(secret);
        var body=stripeEvent(order,attempt);
        var payload=new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(body);
        for(int i=0;i<2;i++) mvc.perform(signedWebhook(payload,secret))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
        var persisted=orders.findById(order.id(),order.customerId());
        assertThat(persisted.status()).isEqualTo(OrderStatus.PAID);
        assertThat(persisted.bankReference()).isEqualTo("synthetic-session-"+attempt.id());
        assertThat(attempts.byId(attempt.id()).orElseThrow().state()).isEqualTo("SUCCEEDED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM shop_payment_attempts WHERE order_id=?",Integer.class,order.id())).isEqualTo(1);
        states.decline(attempt,"late cancellation");
        assertThat(orders.findById(order.id(),order.customerId()).status()).isEqualTo(OrderStatus.PAID);
    }
    @Test void paidCheckoutWinsCancellationAndDelayedSignedWebhookIsIdempotent() throws Exception {
        var order=order();
        // Cancellation is only available for purchases with a durable identity (V15+).
        jdbc.update("UPDATE shop_orders SET purchase_id=? WHERE id=?",UUID.randomUUID(),order.id());
        var attempt=states.begin(order.id(),order.customerId(),UUID.randomUUID().toString(),"STRIPE",BigDecimal.TEN,"PLN").attempt();
        var event=stripeEvent(order,attempt);
        @SuppressWarnings("unchecked") var data=(Map<String,Object>)event.get("data");
        @SuppressWarnings("unchecked") var session=(Map<String,Object>)data.get("object");
        var sessionId=(String)session.get("id");
        states.attachExternal(attempt,sessionId,"https://checkout.stripe.com/synthetic");
        attempt=attempts.byId(attempt.id()).orElseThrow();

        // The customer asks to abandon checkout just after Stripe has completed payment.
        // The authoritative session read sees PAID before the delayed webhook is delivered.
        when(stripe.cancellationStatus(eq(sessionId),anyString())).thenReturn(session);
        var request=UUID.randomUUID();
        var cancellation=cancellations.cancel(order.id(),order.customerId(),request,"CUSTOMER",
                "Synthetic request to cancel a completed Checkout payment");
        assertThat(cancellation.status()).isEqualTo("PAID");
        assertThat(orders.findById(order.id(),order.customerId()).status()).isEqualTo(OrderStatus.PAID);
        assertThat(attempts.byId(attempt.id()).orElseThrow().state()).isEqualTo("SUCCEEDED");

        // Deliver the original provider event late, then replay it as Stripe may do on retry.
        var secret=UUID.randomUUID().toString();
        var payload=new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(event);
        for(int i=0;i<2;i++) webhookMvc(secret).perform(signedWebhook(payload,secret))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());

        var persisted=orders.findById(order.id(),order.customerId());
        assertThat(persisted.status()).isEqualTo(OrderStatus.PAID);
        assertThat(persisted.bankReference()).isEqualTo(sessionId);
        assertThat(attempts.byId(attempt.id()).orElseThrow().state()).isEqualTo("SUCCEEDED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM shop_payment_attempts WHERE order_id=?",Integer.class,order.id())).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT state FROM shop_purchase_cancellation_events WHERE request_id=? ORDER BY id",String.class,request))
                .containsExactly("REQUESTED","PAID");
        verify(stripe,times(1)).cancellationStatus(eq(sessionId),anyString());
        verify(stripe,never()).create(any(),any(),any(),any(),any(),any());
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"amount","currency","merchant","order","attempt","signature"})
    void invalidSignedStripeWebhookCannotOverwriteDurableAttempt(String mismatch) throws Exception {
        var order=order();var attempt=states.begin(order.id(),order.customerId(),UUID.randomUUID().toString(),"STRIPE",BigDecimal.TEN,"PLN").attempt();
        var body=stripeEvent(order,attempt);
        @SuppressWarnings("unchecked") var data=(Map<String,Object>)body.get("data");
        @SuppressWarnings("unchecked") var session=(Map<String,Object>)data.get("object");
        @SuppressWarnings("unchecked") var metadata=(Map<String,Object>)session.get("metadata");
        switch(mismatch) {
            case "amount" -> session.put("amount_total",999);
            case "currency" -> session.put("currency","eur");
            case "merchant" -> metadata.put("merchantId","other-merchant");
            case "order" -> metadata.put("orderNumber","other-order");
            case "attempt" -> metadata.put("attemptId","unknown-synthetic-attempt");
            case "signature" -> { }
            default -> throw new AssertionError(mismatch);
        }
        var payload=new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(body);
        var secret=UUID.randomUUID().toString();
        webhookMvc(secret).perform(signedWebhook(payload,mismatch.equals("signature")?UUID.randomUUID().toString():secret))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().is(mismatch.equals("signature")?400:409));
        assertThat(orders.findById(order.id(),order.customerId()).status()).isEqualTo(OrderStatus.PENDING);
        assertThat(orders.findById(order.id(),order.customerId()).bankReference()).isNull();
        assertThat(attempts.byId(attempt.id()).orElseThrow().state()).isEqualTo("PROCESSING");
        assertThat(attempts.byId(attempt.id()).orElseThrow().externalId()).isNull();
    }
    org.springframework.test.web.servlet.MockMvc webhookMvc(String secret) {
        var properties=new org.demo.shopapp.config.ShopPaymentProperties();properties.setProvider("stripe");properties.setWebhookSecret(secret);
        return org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(
                new org.demo.shopapp.web.StripeWebhookController(new com.fasterxml.jackson.databind.ObjectMapper(),properties,
                        mock(OrderStateService.class),attempts,resolution)).build();
    }
    static Map<String,Object> stripeEvent(OrderRepository.OrderRow order,PaymentAttempt attempt) {
        var metadata=new HashMap<String,Object>();metadata.put("attemptId",attempt.id());metadata.put("merchantId",attempt.merchantId());metadata.put("orderNumber",order.orderNumber());
        var session=new HashMap<String,Object>();session.put("id","synthetic-session-"+attempt.id());session.put("client_reference_id",order.orderNumber());
        session.put("amount_total",1000);session.put("currency","pln");session.put("payment_status","paid");session.put("status","complete");session.put("metadata",metadata);
        return new HashMap<>(Map.of("type","checkout.session.completed","data",Map.of("object",session)));
    }
    static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder signedWebhook(String payload,String secret) throws Exception {
        var timestamp=Instant.now().getEpochSecond();
        var mac=javax.crypto.Mac.getInstance("HmacSHA256");
        mac.init(new javax.crypto.spec.SecretKeySpec(secret.getBytes(java.nio.charset.StandardCharsets.UTF_8),"HmacSHA256"));
        var signature=HexFormat.of().formatHex(mac.doFinal((timestamp+"."+payload).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        return org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/payments/stripe/webhook")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON).header("Stripe-Signature","t="+timestamp+",v1="+signature).content(payload);
    }
    @Test void anotherCustomerCannotReadOrBeginPaymentForExistingOrder() {
        var order=order();
        assertThatThrownBy(()->states.begin(order.id(),"other-synthetic-customer",UUID.randomUUID().toString(),"BANK",null,null))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM shop_payment_attempts WHERE order_id=?",Integer.class,order.id())).isZero();
    }
    @Test void concurrentDifferentKeysCreateOneDurableAttempt() throws Exception {
        var order=order();
        Callable<Boolean> start=()->{try{states.begin(order.id(),order.customerId(),UUID.randomUUID().toString(),"BANK",null,null);return true;}
            catch(org.springframework.web.server.ResponseStatusException exception){return false;}};
        assertThat(parallel(start,start).stream().filter(Boolean::booleanValue).count()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM shop_payment_attempts WHERE order_id=?",Integer.class,order.id())).isEqualTo(1);
    }
    @Test void sameKeyDifferentPayloadIsRejectedAndOriginalAttemptRetained() {
        var order=order(); var key=UUID.randomUUID().toString();
        var first=states.begin(order.id(),order.customerId(),key,"CARD",BigDecimal.TEN,"PLN");
        assertThatThrownBy(()->states.begin(order.id(),order.customerId(),key,"CARD",new BigDecimal("11"),"PLN"))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThat(attempts.byKey(key).orElseThrow()).isEqualTo(first.attempt());
    }
    @Test void threeFailedChecksDoNotReleaseTheOrder() {
        var order=order(); var key=UUID.randomUUID().toString();
        var attempt=states.begin(order.id(),order.customerId(),key,"CARD",BigDecimal.TEN,"PLN").attempt();
        states.unknown(attempt);
        assertThat(states.failure(attempt)).isEqualTo(1);
        assertThat(states.failure(attempt)).isEqualTo(2);
        assertThat(states.failure(attempt)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT reconciliation_suspended_at IS NOT NULL FROM shop_payment_attempts WHERE id=?",Boolean.class,attempt.id())).isTrue();
        assertThatThrownBy(()->states.begin(order.id(),order.customerId(),"another-"+order.id(),"BANK",null,null))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }
    @Test void definitiveRefusalAllowsNewAttemptWithoutOverwritingOldRecord() {
        var order=order(); var key=UUID.randomUUID().toString();
        var first=states.begin(order.id(),order.customerId(),key,"BANK",null,null).attempt();
        states.decline(first,"authoritative terminal refusal");
        var next=states.begin(order.id(),order.customerId(),UUID.randomUUID().toString(),"CARD",BigDecimal.TEN,"PLN").attempt();
        assertThat(next.id()).isNotEqualTo(first.id());
        assertThat(attempts.byId(first.id()).orElseThrow().state()).isEqualTo("DECLINED");
    }
    @Test void legacyFailedOrderIsNotAssumedUnpaidOrAssignedToCurrentMerchant() {
        var order=order();
        jdbc.update("UPDATE shop_orders SET merchant_id=NULL,status='FAILED',payment_idempotency_key=? WHERE id=?",UUID.randomUUID().toString(),order.id());
        assertThatThrownBy(()->states.begin(order.id(),order.customerId(),UUID.randomUUID().toString(),"BANK",null,null))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThat(jdbc.queryForObject("SELECT merchant_id FROM shop_orders WHERE id=?",String.class,order.id())).isNull();
    }
    @Test void conflictingHistoricalRefundsFailMigrationWithoutDeletingThem() {
        var schema="conflict_"+UUID.randomUUID().toString().replace("-","");
        var old=org.flywaydb.core.Flyway.configure().dataSource(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword())
                .schemas(schema).defaultSchema(schema).target("12").load();
        old.migrate();
        // Simulate an inconsistent legacy import only inside this disposable test schema.
        jdbc.execute("DROP INDEX "+schema+".uk_shop_refunds_one_active_per_order");
        jdbc.update("INSERT INTO "+schema+".shop_orders(order_number,customer_id,merchant_account_number,total,currency,status) VALUES ('legacy-order','synthetic','22222222222222222222222222',10,'PLN','PAID')");
        var id=jdbc.queryForObject("SELECT id FROM "+schema+".shop_orders WHERE order_number='legacy-order'",Long.class);
        for(var key:List.of("legacy-a","legacy-b"))
            jdbc.update("INSERT INTO "+schema+".shop_refunds(order_id,order_number,idempotency_key,amount,currency,status,reason) VALUES (?,'legacy-order',?,10,'PLN','PENDING','synthetic')",id,key);
        assertThatThrownBy(()->org.flywaydb.core.Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword()).schemas(schema).defaultSchema(schema).load().migrate())
                .isInstanceOf(org.flywaydb.core.api.FlywayException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM "+schema+".shop_refunds",Integer.class)).isEqualTo(2);
    }
    static <T> List<T> parallel(Callable<T> a,Callable<T> b) throws Exception {
        var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var first=pool.submit(()->{start.await();return a.call();});
            var second=pool.submit(()->{start.await();return b.call();});start.countDown();
            return List.of(first.get(20,TimeUnit.SECONDS),second.get(20,TimeUnit.SECONDS));
        }
    }
}
