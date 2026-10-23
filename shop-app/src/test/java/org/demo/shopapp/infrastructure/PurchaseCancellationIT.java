package org.demo.shopapp.infrastructure;

import org.demo.shopapp.cart.*;
import org.demo.shopapp.order.*;
import org.demo.shopapp.payment.*;
import org.demo.shopapp.auth.ShopUserDetails;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;

@SpringBootTest(properties={"shop.orders.merchant-id=synthetic-merchant","shop.orders.reconciliation-enabled=false"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PurchaseCancellationIT {
    static final PostgreSQLContainer POSTGRES=new PostgreSQLContainer(DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));
    static{POSTGRES.start();}
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r){r.add("spring.datasource.url",POSTGRES::getJdbcUrl);r.add("spring.datasource.username",POSTGRES::getUsername);r.add("spring.datasource.password",POSTGRES::getPassword);}
    @AfterAll static void stop(){POSTGRES.stop();}
    @Autowired JdbcTemplate jdbc;@Autowired PurchaseCancellationService cancellations;@Autowired CartService carts;
    @Autowired OrderService orders;@Autowired PaymentAttemptService payments;@Autowired PurchaseIdentityService purchases;@Autowired MockMvc mvc;
    @MockitoBean BankAuthorizationClient bank;@MockitoBean StripeCheckoutClient stripe;
    @Autowired @org.springframework.beans.factory.annotation.Qualifier("requestMappingHandlerMapping") org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping mappings;
    String customer;long product;static final String REASON="Synthetic customer cancellation confirmed";
    @BeforeEach void fixture(){customer=UUID.randomUUID().toString();product=jdbc.queryForObject("INSERT INTO shop_products(sku,name,description,price,currency,active) VALUES (?,'Synthetic cancellation product','Isolated',10,'PLN',true) RETURNING id",Long.class,UUID.randomUUID().toString());carts.addItem(customer,product,2);}
    PurchaseCancellationService.Result cancel(long id,UUID request){return cancellations.cancel(id,customer,request,"CUSTOMER",REASON);}
    @Test void allRegisteredShopWritesRequireCsrfAndOrderOwnershipForEverySessionRole()throws Exception{
        var order=orders.create(customer);var covered=new ArrayList<String>();
        for(var entry:mappings.getHandlerMethods().entrySet()){
            if(!entry.getValue().getBeanType().getPackageName().startsWith("org.demo.shopapp"))continue;
            for(var path:entry.getKey().getPatternValues())for(var method:entry.getKey().getMethodsCondition().getMethods()){
                if(method.name().equals("GET") && (path.startsWith("/api/cart")||path.startsWith("/api/orders")||path.startsWith("/api/account")||path.startsWith("/api/checkout")))
                    mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path.replaceAll("\\{[^}]+}","1")))
                        .andExpect(result->assertThat(result.getResponse().getStatus()).describedAs("Anonymous "+path).isIn(302,401,403));
                if(!Set.of("POST","PUT","DELETE","PATCH").contains(method.name()))continue;
                if(path.equals("/api/payments/stripe/webhook"))continue; // separately verified signature, not cookie authentication
                var uri=path.replaceAll("\\{[^}]+}","1");covered.add(method+" "+path);
                for(var role:List.of("CUSTOMER","ADMIN","METRICS")){
                    var principal=new ShopUserDetails(991L,"matrix@example.invalid","unused","matrix-foreign",true,role);
                    mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request(org.springframework.http.HttpMethod.valueOf(method.name()),uri)
                        .with(user(principal)).with(csrf().useInvalidToken()).contentType("application/json").content("{}"))
                        .andExpect(status().isForbidden());
                }
            }
        }
        assertThat(covered).contains("POST /api/orders/{orderId}/cancel-purchase","POST /api/orders/{orderId}/pay","PUT /api/cart/items/{productId}","PUT /api/account/profile");
        for(var role:List.of("CUSTOMER","ADMIN")){
            var foreign=new ShopUserDetails(991L,"matrix@example.invalid","unused","matrix-foreign",true,role);
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/orders/"+order.id()).with(user(foreign))).andExpect(status().isNotFound());
            for(var operation:Map.of("pay","{}","refresh-payment","{}","refund","{\"reason\":\"Synthetic owner check\"}").entrySet())
                mvc.perform(post("/api/orders/"+order.id()+"/"+operation.getKey()).with(user(foreign)).with(csrf()).header("Idempotency-Key",UUID.randomUUID().toString()).contentType("application/json").content(operation.getValue())).andExpect(status().isNotFound());
            mvc.perform(post("/api/orders/"+order.id()+"/cancel-purchase").with(user(foreign)).with(csrf()).contentType("application/json")
                .content("{\"requestId\":\""+UUID.randomUUID()+"\",\"confirmed\":true}")).andExpect(status().isNotFound());
        }
        assertThat(jdbc.queryForObject("SELECT cancel_requested_at IS NULL FROM shop_orders WHERE id=?",Boolean.class,order.id())).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM shop_payment_attempts WHERE order_id=?",Integer.class,order.id())).isZero();
        verifyNoInteractions(bank,stripe);
        java.nio.file.Files.writeString(java.nio.file.Path.of("target/shop-security-matrix.json"),new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(Map.of("csrfWrites",covered,"roles",List.of("CUSTOMER","ADMIN","METRICS"),"foreignOrderReadAndCancellation","DENIED","limitations","not full domain-owner matrix for every resource")));
    }
    @Test void k4AllOrderAndCardEntryPointsKeepForeignOrdersUnchanged()throws Exception{
        var order=orders.create(customer);var before=jdbc.queryForMap("SELECT * FROM shop_orders WHERE id=?",order.id());
        for(var role:List.of("CUSTOMER","ADMIN")){
            var foreign=new ShopUserDetails(991L,"k4-foreign@example.invalid","unused","k4-foreign",true,role);
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/orders/"+order.id()).with(user(foreign))).andExpect(status().isNotFound());
            var requests=Map.of("pay","{}","refresh-payment","{}","refund","{\"reason\":\"K4 valid refund reason\"}",
                "cancel-purchase","{\"requestId\":\""+UUID.randomUUID()+"\",\"confirmed\":true}",
                "card-pay","{\"cardNumber\":\"4000000011112222\",\"cvv\":\"123\",\"expiry\":\"12/34\",\"currency\":\"PLN\",\"amount\":20}",
                "card-checkout","{\"currency\":\"PLN\",\"amount\":20}");
            for(var operation:requests.entrySet())mvc.perform(post("/api/orders/"+order.id()+"/"+operation.getKey()).with(user(foreign)).with(csrf())
                .header("Idempotency-Key",UUID.randomUUID().toString()).contentType("application/json").content(operation.getValue())).andExpect(status().isNotFound());
            mvc.perform(post("/api/checkout/card-session").with(user(foreign)).with(csrf()).contentType("application/json")
                .content("{\"orderId\":"+order.id()+",\"currency\":\"PLN\",\"amount\":20}")).andExpect(status().isNotFound());
        }
        assertThat(jdbc.queryForMap("SELECT * FROM shop_orders WHERE id=?",order.id())).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM shop_payment_attempts WHERE order_id=?",Integer.class,order.id())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM shop_refunds WHERE order_id=?",Integer.class,order.id())).isZero();
        var owner=new ShopUserDetails(1L,"k4-owner@example.invalid","unused",customer,true);
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/orders/"+order.id()).with(user(owner))).andExpect(status().isOk());
        verifyNoInteractions(bank,stripe);
    }
    @Test void k4ProfileAddressCartAndPurchaseIdentityAreBoundToPrincipal()throws Exception{
        long ownerId=jdbc.queryForObject("INSERT INTO shop_users(email,password_hash,customer_id,first_name) VALUES (?,'unused',?,'Owner') RETURNING id",Long.class,customer+"@example.invalid",customer);
        String other=UUID.randomUUID().toString();long otherId=jdbc.queryForObject("INSERT INTO shop_users(email,password_hash,customer_id,first_name) VALUES (?,'unused',?,'Other') RETURNING id",Long.class,other+"@example.invalid",other);
        for(var role:List.of("CUSTOMER","ADMIN")){
            var foreign=new ShopUserDetails(otherId,other+"@example.invalid","unused",other,true,role);
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/account/profile").param("customerId",customer).param("userId",String.valueOf(ownerId)).with(user(foreign)).with(csrf()).contentType("application/json")
                .content("{\"firstName\":\"ChangedOwnProfile\",\"lastName\":\"K4\",\"phone\":\"123\"}")).andExpect(status().isOk());
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/account/address").param("userId",String.valueOf(ownerId)).with(user(foreign)).with(csrf()).contentType("application/json")
                .content("{\"recipientName\":\"K4\",\"street\":\"Test\",\"houseNumber\":\"1\",\"postalCode\":\"00-001\",\"city\":\"Test\",\"country\":\"PL\"}")).andExpect(status().isOk());
            mvc.perform(post("/api/cart/items").param("customerId",customer).with(user(foreign)).with(csrf()).contentType("application/json").content("{\"productId\":"+product+",\"quantity\":3}")).andExpect(status().isNoContent());
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/cart/items/"+product).param("customerId",customer).with(user(foreign)).with(csrf()).contentType("application/json").content("{\"productId\":"+product+",\"quantity\":4}")).andExpect(status().isNoContent());
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/cart/items/"+product).param("customerId",customer).with(user(foreign)).with(csrf())).andExpect(status().isNoContent());
            mvc.perform(post("/api/cart/independent-purchase").with(user(foreign)).with(csrf()).contentType("application/json").content("{\"purchaseId\":\""+purchases.status(customer).get("purchaseId")+"\",\"acknowledged\":true}")).andExpect(status().isConflict());
        }
        assertThat(jdbc.queryForObject("SELECT first_name FROM shop_users WHERE id=?",String.class,ownerId)).isEqualTo("Owner");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM shop_user_addresses WHERE user_id=?",Integer.class,ownerId)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM shop_user_addresses WHERE user_id=?",Integer.class,otherId)).isEqualTo(1);
        assertThat(carts.getCart(customer).items().getFirst().quantity()).isEqualTo(2);
        verifyNoInteractions(bank,stripe);
    }
    @Test void k4BrowserReturnAndWrongCallbackStateCannotInventPayment()throws Exception{
        var order=orders.create(customer);var attempt=payments.begin(order.id(),customer,UUID.randomUUID().toString(),"BANK",new BigDecimal("20"),"PLN").attempt();
        var authorization=UUID.randomUUID().toString();payments.attachAuthorization(attempt,authorization,"http://localhost/synthetic");
        var before=jdbc.queryForMap("SELECT * FROM shop_orders WHERE id=?",order.id());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/payment/callback").param("authorizationId",authorization).param("state","wrong-correlation").param("status","approved").param("code","synthetic"))
            .andExpect(status().isBadRequest());
        for(var suffix:List.of("success","cancel"))mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/payments/stripe/"+suffix).param("order",order.orderNumber()).param("session_id","cs_test_forged"))
            .andExpect(status().isFound());
        assertThat(jdbc.queryForMap("SELECT * FROM shop_orders WHERE id=?",order.id())).isEqualTo(before);
        assertThat(carts.getCart(customer).items().getFirst().quantity()).isEqualTo(2);
        verifyNoInteractions(bank,stripe);
    }
    @Test void noDispatchCancelsOncePreservesProductsAndOldTabCannotPay(){
        var order=orders.create(customer);var old=purchases.status(customer).get("purchaseId");var request=UUID.randomUUID();
        assertThat(cancel(order.id(),request).status()).isEqualTo("CANCELLED");
        var next=purchases.status(customer).get("purchaseId");assertThat(next).isNotEqualTo(old);
        assertThat(cancel(order.id(),request).status()).isEqualTo("CANCELLED");assertThat(purchases.status(customer).get("purchaseId")).isEqualTo(next);
        assertThat(carts.getCart(customer).items().getFirst().quantity()).isEqualTo(2);carts.addItem(customer,product,1);
        assertThat(orders.create(customer).id()).isNotEqualTo(order.id());
        assertThatThrownBy(()->payments.begin(order.id(),customer,UUID.randomUUID().toString(),"CARD",BigDecimal.TEN,"PLN")).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM shop_purchase_cancellation_events WHERE request_id=?",Integer.class,request)).isEqualTo(2);
        verifyNoInteractions(bank,stripe);
    }
    @Test void ownershipAndConfirmationAreEnforcedByHttp()throws Exception{
        var order=orders.create(customer);var user=new ShopUserDetails(1L,"synthetic@example.com","unused",customer,true);
        var body="{\"requestId\":\""+UUID.randomUUID()+"\",\"confirmed\":true}";
        mvc.perform(post("/api/orders/"+order.id()+"/cancel-purchase").with(user(user)).contentType("application/json").content(body)).andExpect(status().isForbidden());
        mvc.perform(post("/api/orders/"+order.id()+"/cancel-purchase").with(user(new ShopUserDetails(2L,"other@example.com","unused","other",true))).with(csrf()).contentType("application/json").content(body)).andExpect(status().isNotFound());
        mvc.perform(post("/api/orders/"+order.id()+"/cancel-purchase").with(user(user)).with(csrf()).contentType("application/json").content(body.replace("true","false"))).andExpect(status().isBadRequest());
        mvc.perform(post("/api/orders/"+order.id()+"/cancel-purchase").with(user(user)).with(csrf()).contentType("application/json").content(body)).andExpect(status().isOk());
        verifyNoInteractions(bank,stripe);
    }
    @Test void dispatchVsCancellationIsSerialized()throws Exception{
        var order=orders.create(customer);var barrier=new CyclicBarrier(2);
        try(var pool=Executors.newFixedThreadPool(2)){
            var a=pool.submit(()->{barrier.await();try{return payments.begin(order.id(),customer,UUID.randomUUID().toString(),"CARD",new BigDecimal("20"),"PLN").dispatch();}catch(org.springframework.web.server.ResponseStatusException rejected){return false;}});
            var b=pool.submit(()->{barrier.await();return cancel(order.id(),UUID.randomUUID()).status();});
            boolean dispatched=a.get(20,TimeUnit.SECONDS);String result=b.get(20,TimeUnit.SECONDS);
            if(dispatched)assertThat(result).isEqualTo("REVIEW");else assertThat(result).isEqualTo("CANCELLED");
        }
        assertThat(carts.getCart(customer).items().getFirst().quantity()).isEqualTo(2);
        verify(bank,never()).payCard(any(),any());
    }
    @Test void missingCardResultIsReviewAndSameRequestCanResumeOnDefinitiveRefusal(){
        var order=orders.create(customer);var attempt=payments.begin(order.id(),customer,"card-"+UUID.randomUUID(),"CARD",new BigDecimal("20"),"PLN").attempt();var request=UUID.randomUUID();
        assertThat(cancel(order.id(),request).status()).isEqualTo("REVIEW");
        assertThatThrownBy(()->payments.begin(order.id(),customer,attempt.key(),"CARD",new BigDecimal("20"),"PLN")).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        when(bank.cardStatus(any(),any(),any())).thenReturn(new BankCardPaymentResponse(null,order.orderNumber(),new BigDecimal("20"),"PLN",new BigDecimal("20"),"DECLINED",null,null,false,"synthetic-merchant"));
        assertThat(cancel(order.id(),request).status()).isEqualTo("CANCELLED");assertThat(carts.getCart(customer).items()).hasSize(1);
        verify(bank,never()).payCard(any(),any());
    }
    @Test void bankAuthorizationCancellationIsConfirmedAndSuccessWinsTheRace(){
        for(boolean paid:List.of(false,true)){
            if(paid){carts.addItem(customer,product,1);}
            var order=orders.create(customer);var attempt=payments.begin(order.id(),customer,UUID.randomUUID().toString(),"BANK",null,null).attempt();
            var result=new BankAuthorizationStatusResponse(UUID.randomUUID().toString(),order.orderNumber(),order.total(),"PLN",paid?"COMPLETED":"FAILED",paid?"synthetic-reference-"+order.id():null,paid?"11111111111111111111111111":null,null,Instant.now(),"synthetic-merchant");
            when(bank.cancelByKey(attempt.key())).thenReturn(result);when(bank.statusByKey(attempt.key())).thenReturn(result);
            assertThat(cancel(order.id(),UUID.randomUUID()).status()).isEqualTo(paid?"PAID":"CANCELLED");
        }
        verify(bank,never()).create(any());verify(bank,never()).refund(any(),any());
    }
    Map<String,Object> expired(PaymentAttempt attempt,OrderResponse order){
        var response=new HashMap<String,Object>();response.put("id",attempt.externalId());response.put("status","expired");response.put("payment_status","unpaid");response.put("amount_total",2000);response.put("currency","pln");response.put("client_reference_id",order.orderNumber());response.put("metadata",Map.of("attemptId",attempt.id(),"merchantId",attempt.merchantId(),"orderNumber",order.orderNumber()));response.put("payment_intent",null);return response;
    }
    @Test void stripeRequiresAuthoritativeCancellationNotMerelyExpiredOrMissingIntent(){
        var order=orders.create(customer);var attempt=payments.begin(order.id(),customer,UUID.randomUUID().toString(),"STRIPE",new BigDecimal("20"),"PLN").attempt();
        payments.attachExternal(attempt,"cs_test_synthetic_"+order.id(),"https://checkout.stripe.com/synthetic");
        attempt=jdbcAttempt(attempt.id());var response=expired(attempt,order);var request=UUID.randomUUID();
        response.remove("payment_intent");when(stripe.cancellationStatus(any(),any())).thenReturn(response);assertThat(cancel(order.id(),request).status()).isEqualTo("REVIEW");
        response.put("payment_intent",Map.of("status","processing","amount_received",0));assertThat(cancel(order.id(),request).status()).isEqualTo("REVIEW");
        response.put("payment_intent",null);assertThat(cancel(order.id(),request).status()).isEqualTo("CANCELLED");assertThat(jdbcAttempt(attempt.id()).state()).isEqualTo("CANCELLED");
        verify(stripe,never()).create(any(),any(),any(),any(),any(),any());
    }
    PaymentAttempt jdbcAttempt(String id){return new PaymentAttemptRepository(jdbc).byId(id).orElseThrow();}
    @Test void historicalUnknownCannotBeCancelledByAbsenceOfAnAttempt(){
        long old=jdbc.queryForObject("INSERT INTO shop_orders(order_number,customer_id,merchant_account_number,total,currency,status,payment_idempotency_key) VALUES (?,?,'22222222222222222222222222',20,'PLN','FAILED',?) RETURNING id",Long.class,UUID.randomUUID().toString(),customer,UUID.randomUUID().toString());
        assertThat(cancel(old,UUID.randomUUID()).status()).isEqualTo("REVIEW");assertThat(jdbc.queryForObject("SELECT status FROM shop_orders WHERE id=?",String.class,old)).isEqualTo("FAILED");verifyNoInteractions(bank,stripe);
    }
    @Test void cancellationOfOlderPurchaseDoesNotModifyIndependentBasket(){
        var old=orders.create(customer);purchases.startIndependent(customer,(String)purchases.status(customer).get("purchaseId"),true);carts.addItem(customer,product,5);
        var identity=purchases.status(customer).get("purchaseId");assertThat(cancel(old.id(),UUID.randomUUID()).cartReleased()).isFalse();
        assertThat(purchases.status(customer).get("purchaseId")).isEqualTo(identity);assertThat(carts.getCart(customer).items().getFirst().quantity()).isEqualTo(5);
    }
    @Test void schedulerClosesOnlyOldProvablyUndispatchedPurchases(){
        var order=orders.create(customer);var job=new AbandonedPurchaseJob(jdbc,cancellations,true,30);job.run();assertThat(orders.get(order.id(),customer).status()).isEqualTo("PENDING");
        jdbc.update("UPDATE shop_orders SET created_at=CURRENT_TIMESTAMP-INTERVAL '31 minutes' WHERE id=?",order.id());job.run();
        assertThat(orders.get(order.id(),customer).status()).isEqualTo("CANCELLED");assertThat(carts.getCart(customer).items()).hasSize(1);
    }
    @Test void knownFinancialConflictPreventsLocalCancellationEvenWithoutAttempt(){
        var order=orders.create(customer);var identity=purchases.status(customer).get("purchaseId");
        jdbc.update("INSERT INTO shop_payment_cases(order_id,financial_outcome) VALUES (?,'CONFLICT')",order.id());
        assertThat(cancel(order.id(),UUID.randomUUID()).status()).isEqualTo("REVIEW");
        assertThat(purchases.status(customer).get("purchaseId")).isEqualTo(identity);assertThat(carts.getCart(customer).items()).hasSize(1);verifyNoInteractions(bank,stripe);
    }
    @Test void operatorClosesConfirmedCancellationWithFinancialResultAndSingleAuditEvent(){
        var order=orders.create(customer);var request=UUID.randomUUID();
        jdbc.update("INSERT INTO shop_payment_cases(order_id,allow_new_purchase_at) VALUES (?,now())",order.id());
        var result=cancellations.cancel(order.id(),customer,request,"LOCAL_CC_DEV",REASON);
        assertThat(result.status()).isEqualTo("CANCELLED");assertThat(result.caseClosed()).isTrue();
        assertThat(jdbc.queryForMap("SELECT financial_outcome,workflow_status,version,allow_new_purchase_at FROM shop_payment_cases WHERE order_id=?",order.id()))
            .containsEntry("financial_outcome","CANCELLED").containsEntry("workflow_status","RESOLVED").containsEntry("version",1L).containsEntry("allow_new_purchase_at",null);
        var identity=purchases.status(customer).get("purchaseId");
        assertThat(cancellations.cancel(order.id(),customer,request,"LOCAL_CC_DEV",REASON).caseClosed()).isTrue();
        assertThat(purchases.status(customer).get("purchaseId")).isEqualTo(identity);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM shop_payment_case_events WHERE order_id=? AND action='CLOSE_CANCELLED'",Integer.class,order.id())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT version FROM shop_payment_cases WHERE order_id=?",Long.class,order.id())).isEqualTo(1L);
        assertThat(carts.getCart(customer).items().getFirst().quantity()).isEqualTo(2);
        assertThatThrownBy(()->payments.begin(order.id(),customer,UUID.randomUUID().toString(),"CARD",new BigDecimal("20"),"PLN"))
            .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        verifyNoInteractions(bank,stripe);
    }
    @Test void operatorCannotCloseUnknownUntilProviderConfirmsNoCharge(){
        var order=orders.create(customer);var request=UUID.randomUUID();
        var attempt=payments.begin(order.id(),customer,UUID.randomUUID().toString(),"CARD",new BigDecimal("20"),"PLN").attempt();payments.unknown(attempt);
        jdbc.update("INSERT INTO shop_payment_cases(order_id) VALUES (?)",order.id());
        var result=cancellations.cancel(order.id(),customer,request,"LOCAL_CC_DEV",REASON);
        assertThat(result.status()).isEqualTo("REVIEW");assertThat(result.caseClosed()).isFalse();
        assertThat(jdbc.queryForMap("SELECT financial_outcome,workflow_status FROM shop_payment_cases WHERE order_id=?",order.id()))
            .containsEntry("financial_outcome","UNKNOWN").containsEntry("workflow_status","OPEN");
        when(bank.cardStatus(any(),any(),any())).thenReturn(new BankCardPaymentResponse(null,order.orderNumber(),new BigDecimal("20"),"PLN",new BigDecimal("20"),"DECLINED",null,null,false,"synthetic-merchant"));
        assertThat(cancellations.cancel(order.id(),customer,request,"LOCAL_CC_DEV",REASON).caseClosed()).isTrue();
        assertThat(orders.get(order.id(),customer).status()).isEqualTo("CANCELLED");
    }
    @Test void alreadyCancelledPurchaseCanBeClosedWithoutTouchingNewBasket(){
        var order=orders.create(customer);assertThat(cancel(order.id(),UUID.randomUUID()).status()).isEqualTo("CANCELLED");
        var identity=purchases.status(customer).get("purchaseId");carts.addItem(customer,product,1);
        assertThat(cancellations.cancel(order.id(),customer,UUID.randomUUID(),"LOCAL_CC_DEV",REASON).caseClosed()).isTrue();
        assertThat(purchases.status(customer).get("purchaseId")).isEqualTo(identity);
        assertThat(carts.getCart(customer).items().getFirst().quantity()).isEqualTo(3);
    }
    @Test void paidOrHistoricalUnknownCannotBeClosedAsCancelledByOperator(){
        var order=orders.create(customer);var attempt=payments.begin(order.id(),customer,UUID.randomUUID().toString(),"CARD",new BigDecimal("20"),"PLN").attempt();
        payments.succeed(attempt,"synthetic-paid-reference-"+order.id(),"11111111111111111111111111");
        var result=cancellations.cancel(order.id(),customer,UUID.randomUUID(),"LOCAL_CC_DEV",REASON);
        assertThat(result.status()).isEqualTo("PAID");assertThat(result.caseClosed()).isFalse();
        long legacy=jdbc.queryForObject("INSERT INTO shop_orders(order_number,customer_id,merchant_account_number,total,currency,status,payment_idempotency_key) VALUES (?,?,'22222222222222222222222222',20,'PLN','FAILED',?) RETURNING id",Long.class,UUID.randomUUID().toString(),customer,UUID.randomUUID().toString());
        var historical=cancellations.cancel(legacy,customer,UUID.randomUUID(),"LOCAL_CC_DEV",REASON);
        assertThat(historical.status()).isEqualTo("REVIEW");assertThat(historical.caseClosed()).isFalse();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM shop_payment_case_events WHERE order_id IN (?,?)",Integer.class,order.id(),legacy)).isZero();
        verifyNoInteractions(bank,stripe);
    }
    @Test void cancellationAndCaseAuditRollbackTogether(){
        var order=orders.create(customer);var identity=purchases.status(customer).get("purchaseId");
        jdbc.execute("CREATE FUNCTION fail_cancel_case_audit() RETURNS trigger LANGUAGE plpgsql AS 'BEGIN RAISE EXCEPTION ''synthetic case audit failure''; END'");
        jdbc.execute("CREATE TRIGGER fail_cancel_case_audit BEFORE INSERT ON shop_payment_case_events FOR EACH ROW WHEN (NEW.action='CLOSE_CANCELLED') EXECUTE FUNCTION fail_cancel_case_audit()");
        try{
            assertThatThrownBy(()->cancellations.cancel(order.id(),customer,UUID.randomUUID(),"LOCAL_CC_DEV",REASON)).isInstanceOf(Exception.class);
            assertThat(orders.get(order.id(),customer).status()).isEqualTo("PENDING");
            assertThat(purchases.status(customer).get("purchaseId")).isEqualTo(identity);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM shop_payment_cases WHERE order_id=?",Integer.class,order.id())).isZero();
        }finally{jdbc.execute("DROP TRIGGER fail_cancel_case_audit ON shop_payment_case_events");jdbc.execute("DROP FUNCTION fail_cancel_case_audit()");}
    }
    @Test void administrativeClosureBlocksRetryButLateSuccessIsReconciledAndReopensCase(){
        var order=orders.create(customer);var attempt=payments.begin(order.id(),customer,UUID.randomUUID().toString(),"CARD",new BigDecimal("20"),"PLN").attempt();payments.unknown(attempt);
        jdbc.update("INSERT INTO shop_payment_cases(order_id,workflow_status) VALUES (?,'CLOSED_UNRESOLVED')",order.id());
        assertThatThrownBy(()->payments.begin(order.id(),customer,attempt.key(),"CARD",new BigDecimal("20"),"PLN"))
            .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        payments.succeed(attempt,"synthetic-late-reference-"+order.id(),"11111111111111111111111111");
        assertThat(jdbc.queryForMap("SELECT financial_outcome,workflow_status FROM shop_payment_cases WHERE order_id=?",order.id()))
            .containsEntry("financial_outcome","PAID").containsEntry("workflow_status","ESCALATED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM shop_payment_case_events WHERE order_id=? AND action='LATE_PAYMENT_SUCCESS'",Integer.class,order.id())).isEqualTo(1);
    }
    @Test void packagedCcCommandUsesSameGuardedServiceWithoutStartingBootOrMigrating()throws Exception{
        var order=orders.create(customer);var javaBinary=java.nio.file.Path.of(System.getProperty("java.home"),"bin","java").toString();
        var jar=java.nio.file.Path.of("target/shop-app-0.0.1-SNAPSHOT.jar").toAbsolutePath().toString();
        var builder=new ProcessBuilder(javaBinary,"-Dloader.main=org.demo.shopapp.maintenance.PurchaseCancellationCommand","-cp",jar,"org.springframework.boot.loader.launch.PropertiesLauncher",
                "--profile=dev","--operation=purchase-cancel","--orderId="+order.id(),"--requestId="+UUID.randomUUID(),"--reason=Synthetic isolated CC cancellation","--confirmation=CANCEL");
        builder.environment().clear();builder.environment().putAll(Map.of("SHOP_POSTGRES_HOST",POSTGRES.getHost(),"SHOP_POSTGRES_PORT",POSTGRES.getMappedPort(5432).toString(),"SHOP_POSTGRES_DB",POSTGRES.getDatabaseName(),"SHOP_POSTGRES_USER",POSTGRES.getUsername(),"SHOP_POSTGRES_PASSWORD",POSTGRES.getPassword(),"SHOP_MERCHANT_ID","synthetic-merchant","SHOP_MERCHANT_ACCOUNT_NUMBER","22222222222222222222222222"));
        var cancellationCommand=List.copyOf(builder.command());
        builder.command(cancellationCommand.stream().map(arg->arg.equals("--operation=purchase-cancel")?"--operation=purchase-refresh":arg.equals("--confirmation=CANCEL")?"--confirmation=REFRESH":arg).toList());
        builder.redirectErrorStream(true);var refresh=builder.start();boolean refreshed=refresh.waitFor(25,TimeUnit.SECONDS);if(!refreshed)refresh.destroyForcibly();assertThat(refreshed).isTrue();
        var refreshOutput=new String(refresh.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
        assertThat(refresh.exitValue()).withFailMessage("Synthetic refresh failed; output: %s",refreshOutput).isZero();assertThat(refreshOutput).doesNotContain("Flyway","Tomcat","CANCELLED");
        assertThat(jdbc.queryForObject("SELECT cancel_requested_at IS NULL FROM shop_orders WHERE id=?",Boolean.class,order.id())).isTrue();
        builder.command(cancellationCommand);
        jdbc.update("UPDATE flyway_schema_history SET success=false WHERE version='17'");
        try{
            var missingSchema=builder.start();boolean stopped=missingSchema.waitFor(25,TimeUnit.SECONDS);if(!stopped)missingSchema.destroyForcibly();assertThat(stopped).isTrue();
            var schemaOutput=new String(missingSchema.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
            assertThat(missingSchema.exitValue()).isNotZero();assertThat(schemaOutput).contains("Wymagana migracja V17");
            assertThat(jdbc.queryForObject("SELECT cancel_requested_at IS NULL FROM shop_orders WHERE id=?",Boolean.class,order.id())).isTrue();
        }finally{jdbc.update("UPDATE flyway_schema_history SET success=true WHERE version='17'");}
        builder.redirectErrorStream(true);var process=builder.start();boolean finished=process.waitFor(25,TimeUnit.SECONDS);if(!finished)process.destroyForcibly();assertThat(finished).isTrue();
        var output=new String(process.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
        assertThat(process.exitValue()).withFailMessage("Synthetic command failed; output: %s",output).isZero();assertThat(output).contains("CANCELLED").doesNotContain("Flyway","Tomcat");
        assertThat(output).contains("\"caseClosed\":true");
        assertThat(jdbc.queryForObject("SELECT workflow_status FROM shop_payment_cases WHERE order_id=?",String.class,order.id())).isEqualTo("RESOLVED");
        assertThat(carts.getCart(customer).items().getFirst().quantity()).isEqualTo(2);
    }
}
