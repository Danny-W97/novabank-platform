package org.demo.bankdemo.infrastructure;

import com.redis.testcontainers.RedisContainer;
import org.demo.bankdemo.account.*;
import org.demo.bankdemo.auth.*;
import org.demo.bankdemo.payment.*;
import org.demo.bankdemo.payment.authorization.*;
import org.demo.bankdemo.profile.UserSessionService;
import org.demo.bankdemo.user.*;
import org.demo.bankdemo.notification.NotificationService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@SpringBootTest(properties = {"app.transfer.scheduler-delay-ms=3600000", "app.exchange.quote-cleanup-delay-ms=3600000",
        "app.transfer.single-limit=50000.00", "app.transfer.daily-limit=100000.00"})
@ActiveProfiles("test")
@org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
@org.springframework.test.annotation.DirtiesContext(classMode=org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class AuditPaymentConcurrencyIT {
    static final PostgreSQLContainer POSTGRES;
    static final RedisContainer REDIS;
    static {
        POSTGRES = new PostgreSQLContainer(DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));
        REDIS = new RedisContainer(DockerImageName.parse("redis:6.2.6"));
        POSTGRES.start(); REDIS.start();
    }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username",POSTGRES::getUsername);
        r.add("spring.datasource.password",POSTGRES::getPassword);
        r.add("spring.data.redis.host",REDIS::getHost);
        r.add("spring.data.redis.port",REDIS::getFirstMappedPort);
        r.add("spring.datasource.hikari.connection-init-sql",()->"SET lock_timeout = '5s'");
        r.add("app.security.totp-encryption-key",()->Base64.getEncoder().encodeToString(
                "0123456789ABCDEF0123456789ABCDEF".getBytes(StandardCharsets.UTF_8)));
        r.add("app.security.totp-encryption-keys",()->"");
    }
    @AfterAll static void stop() {
        REDIS.stop(); POSTGRES.stop();
    }
    @Autowired UserRepository users;
    @Autowired AccountRepository accounts;
    @Autowired AccountNumberGenerator numbers;
    @Autowired PaymentAuthorizationService authorizations;
    @Autowired PaymentService payments;
    @Autowired PaymentPurchaseGuard purchases;
    @Autowired org.demo.bankdemo.config.PaymentAuthorizationProperties authorizationProperties;
    @jakarta.persistence.PersistenceContext jakarta.persistence.EntityManager entityManager;
    @Autowired PaymentAuthorizationRepository authorizationRepository;
    @Autowired MerchantPaymentService merchantPayments;
    @Autowired org.demo.bankdemo.card.CardService cards;
    @Autowired PasswordResetTokenRepository resetTokens;
    @Autowired EmailVerificationTokenRepository verificationTokens;
    @Autowired EmailTokenService emailTokens;
    @Autowired PasswordEncoder encoder;
    @Autowired JdbcTemplate jdbc;
    @Autowired org.springframework.data.redis.core.StringRedisTemplate redis;
    @Autowired org.demo.bankdemo.security.TotpReplayGuard replayGuard;
    @Autowired LoginChallengeStore challenges;
    @Autowired org.demo.bankdemo.transfer.TransferDraftStore drafts;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;
    @Autowired org.demo.bankdemo.profile.TwoFactorService twoFactor;
    @Autowired org.demo.bankdemo.profile.TotpRecoveryCodeRepository recoveryCodes;
    @Autowired org.demo.bankdemo.security.TotpSecretCipher cipher;
    @Autowired java.time.Clock clock;
    @Autowired org.demo.bankdemo.transfer.ScheduledTransferService scheduledTransfers;
    @Autowired org.springframework.test.web.servlet.MockMvc mvc;
    @Autowired @org.springframework.beans.factory.annotation.Qualifier("requestMappingHandlerMapping") org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping mappings;
    @MockitoSpyBean UserSessionService sessions;
    @MockitoSpyBean NotificationService notifications;
    @MockitoSpyBean javax.sql.DataSource dataSource;

    record Client(User user,Long accountId,String number) { }
    Client client() {
        var user=new User(UUID.randomUUID()+"@example.invalid",encoder.encode("initial-synthetic-password"),"Synthetic","Client",null);
        user.verifyEmail(Instant.now());
        user=users.saveAndFlush(user);
        var account=accounts.saveAndFlush(new Account(user,numbers.generateUnique(),new BigDecimal("1000.00")));
        return new Client(user,account.getId(),account.getAccountNumber());
    }
    MerchantIdentity merchant(Client c) { return new MerchantIdentity("merchant-"+c.accountId(),Set.of(c.number())); }
    PaymentAuthorizationResponse create(Client destination,String key,String order,BigDecimal amount) {
        return authorizations.create(merchant(destination),new PaymentAuthorizationRequest(order,"synthetic-customer",destination.number(),
                amount,"PLN","Synthetic purchase",key,UUID.randomUUID().toString()));
    }
    BigDecimal balance(Client c) { return jdbc.queryForObject("SELECT balance FROM accounts WHERE id=?",BigDecimal.class,c.accountId()); }

    @Test void everyRegisteredCookieMutationHasCsrfAndAdminRoutesRejectOrdinaryRoles()throws Exception{
        var subject=client();var inventory=new ArrayList<Map<String,Object>>();
        for(var entry:mappings.getHandlerMethods().entrySet()){
            if(!entry.getValue().getBeanType().getPackageName().startsWith("org.demo.bankdemo"))continue;
            for(var path:entry.getKey().getPatternValues())for(var method:entry.getKey().getMethodsCondition().getMethods()){
                var uri=path.replaceAll("\\{[^}]+}","1");
                inventory.add(Map.of("method",method.name(),"path",path,"policy",path.startsWith("/api/")?"API_KEY_OR_DENY":path.startsWith("/admin")?"ADMIN_AND_CSRF":"COOKIE_CSRF_OR_EXPLICIT_PUBLIC"));
                if(Set.of("POST","PUT","PATCH","DELETE").contains(method.name()) && !path.startsWith("/api/")){
                    for(var role:List.of("USER","ADMIN","METRICS"))
                        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request(org.springframework.http.HttpMethod.valueOf(method.name()),uri)
                            .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user(subject.user().getEmail()).roles(role))
                            .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf().useInvalidToken()).contentType("application/json").content("{}"))
                            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
                }
                if(path.startsWith("/admin"))for(var role:List.of("USER","METRICS"))
                    mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request(org.springframework.http.HttpMethod.valueOf(method.name()),uri)
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user(subject.user().getEmail()).roles(role))
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf()))
                        .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
                if(path.startsWith("/api/"))
                    mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request(org.springframework.http.HttpMethod.valueOf(method.name()),uri)
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user(subject.user().getEmail()).roles("USER"))
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf()))
                        .andExpect(result->assertThat(result.getResponse().getStatus()).isIn(401,403));
                if(method.name().equals("GET") && !path.startsWith("/api/") && !Set.of("/","/about","/terms","/login","/login/2fa","/register","/verify-email","/verify-email/sent","/password/forgot","/password/reset").contains(path))
                    mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(uri))
                        .andExpect(result->assertThat(result.getResponse().getStatus()).describedAs("Anonymous "+path).isIn(302,401,403));
            }
        }
        assertThat(inventory.size()).isGreaterThan(40);
        java.nio.file.Files.writeString(java.nio.file.Path.of("target/bank-security-matrix.json"),new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(inventory));
        assertThat(balance(subject)).isEqualByComparingTo("1000.00");
    }
    @Test void foreignHistoryNotificationsAndSchedulesStayOwnedEvenForAdminSessions()throws Exception{
        var owner=client();var destination=client();var attacker=client();var key=UUID.randomUUID().toString();
        var auth=create(destination,key,UUID.randomUUID().toString(),BigDecimal.TEN);authorizations.approve(auth.authorizationId(),owner.user().getEmail());
        long transaction=jdbc.queryForObject("SELECT id FROM bank_transactions WHERE idempotency_key=?",Long.class,key);
        long notification=jdbc.queryForObject("INSERT INTO notifications(user_id,type,message,is_read,created_at) VALUES (?,'INFO','Synthetic owner-only notification',false,now()) RETURNING id",Long.class,owner.user().getId());
        long recipient=jdbc.queryForObject("INSERT INTO saved_recipients(owner_id,alias,recipient_name,account_number,created_at,updated_at) VALUES (?,'Owner-only alias','Synthetic Recipient',?,now(),now()) RETURNING id",Long.class,owner.user().getId(),destination.number());
        var scheduleKey=UUID.randomUUID().toString();scheduledTransfers.create(owner.user().getEmail(),scheduleKey,destination.number(),"Synthetic Recipient",BigDecimal.ONE,"Ownership matrix",org.demo.bankdemo.transfer.ScheduleFrequency.DAILY,Instant.now().plusSeconds(86400));
        long schedule=jdbc.queryForObject("SELECT id FROM scheduled_transfers WHERE idempotency_key=?",Long.class,scheduleKey);
        for(var role:List.of("USER","ADMIN")){
            var principal=org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user(attacker.user().getEmail()).roles(role);
            for(var suffix:List.of("","/confirmation.pdf"))mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/history/"+transaction+suffix).with(principal)).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isNotFound());
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/recipients/"+recipient+"/edit").with(principal)).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isNotFound());
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/recipients/"+recipient).with(principal).with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())
                .param("alias","K4 valid alias").param("recipientName","K4 Recipient").param("accountNumber",destination.number())).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isNotFound());
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/history/"+transaction+"/retry").with(principal).with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl("/history"));
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/recipients/"+recipient+"/delete").with(principal).with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isNotFound());
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/notifications/"+notification+"/read").with(principal).with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().is3xxRedirection());
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/scheduled-transfers/"+schedule+"/cancel").with(principal).with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().is3xxRedirection());
        }
        assertThat(jdbc.queryForObject("SELECT is_read FROM notifications WHERE id=?",Boolean.class,notification)).isFalse();
        assertThat(jdbc.queryForObject("SELECT active FROM scheduled_transfers WHERE id=?",Boolean.class,schedule)).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM saved_recipients WHERE id=? AND owner_id=?",Integer.class,recipient,owner.user().getId())).isEqualTo(1);
        assertThat(balance(attacker)).isEqualByComparingTo("1000");
        var own=org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user(owner.user().getEmail()).roles("USER");
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/history/"+transaction).with(own)).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/history/"+transaction+"/confirmation.pdf").with(own)).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/recipients/"+recipient+"/edit").with(own).with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/notifications/"+notification+"/read").with(own).with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().is3xxRedirection());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/scheduled-transfers/"+schedule+"/cancel").with(own).with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().is3xxRedirection());
        assertThat(jdbc.queryForObject("SELECT is_read FROM notifications WHERE id=?",Boolean.class,notification)).isTrue();
        assertThat(jdbc.queryForObject("SELECT active FROM scheduled_transfers WHERE id=?",Boolean.class,schedule)).isFalse();
    }

    @Test void k4TransferExportsAndProfileUseAuthenticatedOwner()throws Exception{
        var owner=client();var destination=client();var stranger=client();
        var result=applicationTransferService.execute(owner.user().getEmail(),new org.demo.bankdemo.transfer.TransferCommand(UUID.randomUUID().toString(),"K4 Recipient",destination.number(),BigDecimal.TEN,"K4 owner transfer"));
        for(var role:List.of("USER","ADMIN")){
            var foreign=org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user(stranger.user().getEmail()).roles(role);
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/transfer/success/"+result.referenceNumber()).with(foreign)).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isNotFound());
            var export=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/history/export.csv").with(foreign)).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk()).andReturn();
            assertThat(export.getResponse().getContentAsString()).doesNotContain(result.referenceNumber());
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/profile/edit").with(foreign).with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())
                .param("email",owner.user().getEmail()).param("firstName","ChangedOwnName").param("lastName","K4").param("birthDate","1995-04-12").param("phone","123456789"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl("/profile"));
        }
        var own=org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user(owner.user().getEmail()).roles("USER");
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/transfer/success/"+result.referenceNumber()).with(own)).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
        var ownExport=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/history/export.csv").with(own)).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk()).andReturn();
        assertThat(ownExport.getResponse().getContentAsString()).contains(result.referenceNumber());
        assertThat(jdbc.queryForObject("SELECT first_name FROM users WHERE id=?",String.class,owner.user().getId())).isEqualTo("Synthetic");
        assertThat(jdbc.queryForObject("SELECT first_name FROM users WHERE id=?",String.class,stranger.user().getId())).isEqualTo("ChangedOwnName");
        assertThat(balance(owner)).isEqualByComparingTo("990");assertThat(balance(stranger)).isEqualByComparingTo("1000");
    }
    @Test void merchantCancellationIsOwnedIdempotentAndOldApprovalCannotCharge(){
        var source=client();var destination=client();var other=client();var key=UUID.randomUUID().toString();
        var response=create(destination,key,UUID.randomUUID().toString(),BigDecimal.TEN);
        assertThatThrownBy(()->authorizations.cancelByKey(merchant(other),key)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThat(authorizations.cancelByKey(merchant(destination),key).status()).isEqualTo("FAILED");
        assertThat(authorizations.cancelByKey(merchant(destination),key).status()).isEqualTo("FAILED");
        authorizations.approve(response.authorizationId(),source.user().getEmail());
        assertThat(balance(source)).isEqualByComparingTo("1000");assertThat(balance(destination)).isEqualByComparingTo("1000");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bank_transactions WHERE idempotency_key=?",Integer.class,key)).isZero();
    }
    @Test void approvalVsMerchantCancellationHasOneAuthoritativeWinner()throws Exception{
        var source=client();var destination=client();var key=UUID.randomUUID().toString();var response=create(destination,key,UUID.randomUUID().toString(),BigDecimal.TEN);
        var barrier=new CyclicBarrier(2);
        try(var pool=Executors.newFixedThreadPool(2)){
            var approved=pool.submit(()->{barrier.await();return authorizations.approve(response.authorizationId(),source.user().getEmail());});
            var cancelled=pool.submit(()->{barrier.await();return authorizations.cancelByKey(merchant(destination),key);});
            approved.get(20,TimeUnit.SECONDS);cancelled.get(20,TimeUnit.SECONDS);
        }
        var outcome=authorizations.status(merchant(destination),response.authorizationId());
        assertThat(outcome.status()).isIn("COMPLETED","FAILED");
        boolean paid="COMPLETED".equals(outcome.status());
        assertThat(balance(source)).isEqualByComparingTo(paid?"990":"1000");assertThat(balance(destination)).isEqualByComparingTo(paid?"1010":"1000");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bank_transactions WHERE idempotency_key=?",Integer.class,key)).isEqualTo(paid?1:0);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"source-blocked","destination-blocked","source-closed","single","daily"})
    void accountAndPersonalLimitRefusalsNeverMoveMoney(String reason) {
        var source=client(); var destination=client(); var key=UUID.randomUUID().toString();
        switch(reason) {
            case "source-blocked" -> jdbc.update("UPDATE accounts SET status='BLOCKED' WHERE id=?",source.accountId());
            case "destination-blocked" -> jdbc.update("UPDATE accounts SET status='BLOCKED' WHERE id=?",destination.accountId());
            case "source-closed" -> jdbc.update("UPDATE accounts SET status='CLOSED' WHERE id=?",source.accountId());
            case "single" -> jdbc.update("UPDATE users SET payment_single_limit=5 WHERE id=?",source.user().getId());
            case "daily" -> jdbc.update("UPDATE users SET payment_daily_limit=5 WHERE id=?",source.user().getId());
            default -> throw new AssertionError(reason);
        }
        var response=create(destination,key,UUID.randomUUID().toString(),BigDecimal.TEN);
        assertThat(authorizations.approve(response.authorizationId(),source.user().getEmail())).contains("status=failed");
        assertThat(authorizationRepository.findById(response.authorizationId()).orElseThrow().getStatus()).isEqualTo(AuthorizationStatus.FAILED);
        assertThat(balance(source)).isEqualByComparingTo("1000");
        assertThat(balance(destination)).isEqualByComparingTo("1000");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bank_transactions WHERE idempotency_key=?",Integer.class,key)).isZero();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"bank-single","bank-daily"})
    void bankLimitsRefuseWithoutPartiallyRecordingPayment(String reason) {
        var source=client();var destination=client();
        jdbc.update("UPDATE accounts SET balance=1000000 WHERE id=?",source.accountId());
        var bankLimits=new org.demo.bankdemo.config.TransferLimitsProperties();
        var amount=bankLimits.getSingleLimit().add(new BigDecimal("0.01"));
        if(reason.equals("bank-daily")) {
            // Defaults are explicit in this fixture; two valid payments reach the daily total.
            for(int i=0;i<2;i++) payments.execute(merchant(destination).id(),UUID.randomUUID().toString(),
                    new PaymentRequest(source.number(),destination.number(),UUID.randomUUID().toString(),
                            bankLimits.getSingleLimit(),"PLN","Synthetic prior payment"));
            amount=new BigDecimal("0.01");
        }
        var beforeSource=balance(source);var beforeDestination=balance(destination);var key=UUID.randomUUID().toString();
        var response=create(destination,key,UUID.randomUUID().toString(),amount);
        assertThat(authorizations.approve(response.authorizationId(),source.user().getEmail())).contains("status=failed");
        assertThat(balance(source)).isEqualByComparingTo(beforeSource);
        assertThat(balance(destination)).isEqualByComparingTo(beforeDestination);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bank_transactions WHERE idempotency_key=?",Integer.class,key)).isZero();
        assertThat(authorizationRepository.findById(response.authorizationId()).orElseThrow().getStatus()).isEqualTo(AuthorizationStatus.FAILED);
    }

    @Test void failedDeclineWriteDoesNotClaimTerminalOutcomeOrReleasePurchase() {
        var source=client(); var destination=client(); var key=UUID.randomUUID().toString();
        var response=create(destination,key,UUID.randomUUID().toString(),new BigDecimal("1001.00"));
        var name="synthetic_decline_"+UUID.randomUUID().toString().replace("-","");
        // A real database write failure, restricted to this disposable Testcontainers database and authorization.
        jdbc.execute("CREATE FUNCTION "+name+"() RETURNS trigger LANGUAGE plpgsql AS 'BEGIN IF NEW.id = ''"+
                response.authorizationId()+"'' AND NEW.status = ''FAILED'' THEN RAISE EXCEPTION ''synthetic decline write failure''; END IF; RETURN NEW; END'");
        jdbc.execute("CREATE TRIGGER "+name+" BEFORE UPDATE ON payment_authorizations FOR EACH ROW EXECUTE FUNCTION "+name+"()");
        try {
            assertThatThrownBy(()->authorizations.approve(response.authorizationId(),source.user().getEmail())).isInstanceOf(RuntimeException.class);
            assertThat(authorizationRepository.findById(response.authorizationId()).orElseThrow().getStatus()).isEqualTo(AuthorizationStatus.PENDING);
            assertThat(jdbc.queryForObject("SELECT state FROM payment_purchase_attempts WHERE idempotency_key=?",String.class,key)).isEqualTo("PENDING");
            assertThat(balance(source)).isEqualByComparingTo("1000");
            assertThat(balance(destination)).isEqualByComparingTo("1000");
        } finally {
            jdbc.execute("DROP TRIGGER "+name+" ON payment_authorizations");
            jdbc.execute("DROP FUNCTION "+name+"()");
        }
        assertThat(authorizations.approve(response.authorizationId(),source.user().getEmail())).contains("status=failed");
    }

    @Test void expiredAuthorizationCheckedConcurrentlyWithApproveCannotDebit() throws Exception {
        var source=client(); var destination=client(); var key=UUID.randomUUID().toString();
        var response=create(destination,key,UUID.randomUUID().toString(),BigDecimal.TEN);
        jdbc.update("UPDATE payment_authorizations SET expires_at=CURRENT_TIMESTAMP-INTERVAL '1 hour' WHERE id=?",response.authorizationId());
        parallel(()->authorizations.approve(response.authorizationId(),source.user().getEmail()),
                ()->authorizations.status(merchant(destination),response.authorizationId()).status());
        assertThat(authorizationRepository.findById(response.authorizationId()).orElseThrow().getStatus()).isEqualTo(AuthorizationStatus.FAILED);
        assertThat(balance(source)).isEqualByComparingTo("1000");
        assertThat(balance(destination)).isEqualByComparingTo("1000");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bank_transactions WHERE idempotency_key=?",Integer.class,key)).isZero();
    }

    @Test void extractedCardWorkerJoinsCallerTransactionAndRollsBackWithIt() {
        var source=client(); cards.attach(source.user().getEmail()); var key=UUID.randomUUID().toString();
        var transaction=new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        assertThatThrownBy(()->transaction.execute(status->{
            var result=cards.pay(source.user().getEmail(),"Synthetic merchant",org.demo.bankdemo.currency.CurrencyCode.PLN,BigDecimal.TEN,key);
            assertThat(result.status()).isEqualTo(org.demo.bankdemo.card.CardTransactionStatus.COMPLETED);
            throw new IllegalStateException("synthetic outer transaction failure");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(balance(source)).isEqualByComparingTo("1000");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM card_transactions WHERE idempotency_key=?",Integer.class,key)).isZero();
        cards.pay(source.user().getEmail(),"Synthetic merchant",org.demo.bankdemo.currency.CurrencyCode.PLN,BigDecimal.TEN,key);
        assertThat(balance(source)).isEqualByComparingTo("990");
    }

    @Test void extractedOnlineCardWorkerRollsBackBothBalancesAndBothLedgersWithCaller() {
        var source=client(); var destination=client(); var card=cards.attach(source.user().getEmail());
        var key=UUID.randomUUID().toString(); var order=UUID.randomUUID().toString();
        var expiry=java.time.format.DateTimeFormatter.ofPattern("MM/yy").format(card.demoExpiresAt());
        var transaction=new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        assertThatThrownBy(()->transaction.execute(status->{
            var result=cards.payOnline(card.onlineNumber(),card.demoCvv(),expiry,destination.number(),order,
                    org.demo.bankdemo.currency.CurrencyCode.PLN,BigDecimal.TEN,BigDecimal.TEN,"Synthetic purchase",key,merchant(destination).id());
            assertThat(result.status()).isEqualTo(org.demo.bankdemo.card.CardTransactionStatus.COMPLETED);
            throw new IllegalStateException("synthetic outer online failure");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(balance(source)).isEqualByComparingTo("1000");
        assertThat(balance(destination)).isEqualByComparingTo("1000");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM card_transactions WHERE idempotency_key=?",Integer.class,key)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bank_transactions WHERE idempotency_key=?",Integer.class,key)).isZero();
    }

    @Test void businessRefusalIsDurableAfterRollback() {
        var source=client(); var destination=client();
        var response=create(destination,UUID.randomUUID().toString(),UUID.randomUUID().toString(),new BigDecimal("1001.00"));
        assertThat(authorizations.approve(response.authorizationId(),source.user().getEmail())).contains("status=failed");
        assertThat(authorizationRepository.findById(response.authorizationId()).orElseThrow().getStatus()).isEqualTo(AuthorizationStatus.FAILED);
        assertThat(balance(source)).isEqualByComparingTo("1000");
        assertThat(balance(destination)).isEqualByComparingTo("1000");
    }
    @Test void concurrentApproveCommitsOnceAndTerminalStateSurvivesExpiryAndReject() throws Exception {
        var source=client(); var destination=client(); var key=UUID.randomUUID().toString();
        var response=create(destination,key,UUID.randomUUID().toString(),new BigDecimal("25.00"));
        parallel(()->authorizations.approve(response.authorizationId(),source.user().getEmail()),
                ()->authorizations.approve(response.authorizationId(),source.user().getEmail()));
        jdbc.update("UPDATE payment_authorizations SET expires_at=CURRENT_TIMESTAMP-INTERVAL '1 hour' WHERE id=?",response.authorizationId());
        assertThat(authorizations.approve(response.authorizationId(),source.user().getEmail())).contains("status=completed");
        assertThat(authorizations.reject(response.authorizationId())).contains("status=completed");
        assertThat(balance(source)).isEqualByComparingTo("975");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bank_transactions WHERE idempotency_key=?",Integer.class,key)).isEqualTo(1);
    }
    @Test void secondKeyOrDifferentAccountOfSameMerchantCannotPayPurchaseAgain() {
        var source=client(); var destination=client(); var otherAccount=client();
        var order=UUID.randomUUID().toString();
        var first=create(destination,UUID.randomUUID().toString(),order,new BigDecimal("25.00"));
        authorizations.approve(first.authorizationId(),source.user().getEmail());
        var rotated=new MerchantIdentity(merchant(destination).id(),Set.of(otherAccount.number()));
        assertThatThrownBy(()->authorizations.create(rotated,new PaymentAuthorizationRequest(order,"synthetic-customer",otherAccount.number(),
                new BigDecimal("25.00"),"PLN","Synthetic purchase",UUID.randomUUID().toString(),"synthetic-state")))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThat(balance(source)).isEqualByComparingTo("975");
    }
    @Test void anotherMerchantCannotReadOrRedeemAuthorization() {
        var destination=client(); var stranger=client();
        var key=UUID.randomUUID().toString();var response=create(destination,key,UUID.randomUUID().toString(),BigDecimal.TEN);
        assertThatThrownBy(()->authorizations.status(merchant(stranger),response.authorizationId()))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThatThrownBy(()->authorizations.redeem(merchant(stranger),response.authorizationId(),new PaymentAuthorizationRedeemRequest("code","state")))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThatThrownBy(()->authorizations.statusByKey(merchant(stranger),key)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThatThrownBy(()->authorizations.cancelByKey(merchant(stranger),key)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThat(authorizations.status(merchant(destination),response.authorizationId()).status()).isEqualTo("PENDING");
        assertThat(balance(destination)).isEqualByComparingTo("1000");assertThat(balance(stranger)).isEqualByComparingTo("1000");
    }
    @Test void k4ForeignMerchantCannotReadCardStatusOrRefundOriginalPayment(){
        var source=client();var destination=client();var stranger=client();var card=cards.attach(source.user().getEmail());
        var key=UUID.randomUUID().toString();var order=UUID.randomUUID().toString();
        var expiry=java.time.format.DateTimeFormatter.ofPattern("MM/yy").format(card.demoExpiresAt());
        var request=new org.demo.bankdemo.card.CardPaymentRequest(card.onlineNumber(),card.demoCvv(),expiry,destination.number(),order,"PLN",BigDecimal.TEN,BigDecimal.TEN,"K4 synthetic card purchase");
        var paid=merchantPayments.payCard(merchant(destination),key,request);assertThat(paid.status()).isEqualTo("COMPLETED");
        assertThatThrownBy(()->merchantPayments.cardStatus(merchant(stranger),key,stranger.number(),order)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThatThrownBy(()->merchantPayments.cardStatus(merchant(stranger),key,destination.number(),order)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        var refund=new RefundRequest(stranger.number(),source.number(),order,BigDecimal.TEN,"PLN","K4 foreign refund",paid.referenceNumber());
        assertThatThrownBy(()->merchantPayments.refund(merchant(stranger),UUID.randomUUID().toString(),refund,"K4 foreign refund")).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThat(merchantPayments.cardStatus(merchant(destination),key,destination.number(),order).status()).isEqualTo("COMPLETED");
        assertThat(balance(source)).isEqualByComparingTo("990");assertThat(balance(destination)).isEqualByComparingTo("1010");assertThat(balance(stranger)).isEqualByComparingTo("1000");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bank_transactions WHERE payment_order_id=?",Integer.class,order)).isEqualTo(1);
    }
    @Test void lateFailureRollsBackLedgerBalancesAndAuthorizationTogether() {
        var source=client(); var destination=client(); var key=UUID.randomUUID().toString();
        var response=create(destination,key,UUID.randomUUID().toString(),BigDecimal.TEN);
        NotificationService notificationTarget=org.springframework.test.util.AopTestUtils.getUltimateTargetObject(notifications);
        doThrow(new IllegalStateException("synthetic infrastructure failure")).when(notificationTarget).paymentCompleted(any(),any(),anyString(),anyString());
        assertThatThrownBy(()->authorizations.approve(response.authorizationId(),source.user().getEmail())).isInstanceOf(IllegalStateException.class);
        assertThat(balance(source)).isEqualByComparingTo("1000");
        assertThat(balance(destination)).isEqualByComparingTo("1000");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bank_transactions WHERE idempotency_key=?",Integer.class,key)).isZero();
        assertThat(authorizationRepository.findById(response.authorizationId()).orElseThrow().getStatus()).isEqualTo(AuthorizationStatus.PENDING);
    }
    @Test void concurrentPasswordResetConsumesOneToken() throws Exception {
        var source=client(); var raw=UUID.randomUUID().toString();
        resetTokens.saveAndFlush(new PasswordResetToken(source.user(),hash(raw),Instant.now().plusSeconds(300)));
        Callable<Boolean> change=()->{try{ emailTokens.resetPassword(raw,"new-synthetic-password"); return true; }
            catch(IllegalArgumentException exception){return false;}};
        var results=parallel(change,change);
        assertThat(results.stream().filter(Boolean::booleanValue).count()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT used_at IS NOT NULL FROM password_reset_tokens WHERE token_hash=?",Boolean.class,hash(raw))).isTrue();
    }
    @Test void failedPasswordChangeRollsBackConsumptionAndCanRetry() {
        var source=client(); var raw=UUID.randomUUID().toString();
        resetTokens.saveAndFlush(new PasswordResetToken(source.user(),hash(raw),Instant.now().plusSeconds(300)));
        UserSessionService sessionTarget=org.springframework.test.util.AopTestUtils.getUltimateTargetObject(sessions);
        doThrow(new IllegalStateException("synthetic session failure")).doReturn(0).when(sessionTarget).invalidateAllSessions(source.user().getEmail());
        assertThatThrownBy(()->emailTokens.resetPassword(raw,"new-synthetic-password")).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT used_at FROM password_reset_tokens WHERE token_hash=?",Object.class,hash(raw))).isNull();
        assertThat(encoder.matches("initial-synthetic-password",users.findById(source.user().getId()).orElseThrow().getPasswordHash())).isTrue();
        emailTokens.resetPassword(raw,"new-synthetic-password");
        assertThat(encoder.matches("new-synthetic-password",users.findById(source.user().getId()).orElseThrow().getPasswordHash())).isTrue();
    }
    @Test void concurrentEmailVerificationConsumesOneToken() throws Exception {
        var source=client(); var raw=UUID.randomUUID().toString();
        verificationTokens.saveAndFlush(new EmailVerificationToken(source.user(),hash(raw),Instant.now().plusSeconds(300)));
        Callable<Boolean> verify=()->{try{return emailTokens.verifyEmail(raw);}catch(IllegalArgumentException exception){return false;}};
        assertThat(parallel(verify,verify).stream().filter(Boolean::booleanValue).count()).isEqualTo(1);
    }
    @Test void concurrentTotpConsumptionHasOneWinnerAndCannotUseOlderStep() throws Exception {
        var source=client(); var secret=UUID.randomUUID().toString();
        assertThat(parallel(()->replayGuard.consume(source.user().getId(),secret,100),
                ()->replayGuard.consume(source.user().getId(),secret,100)).stream().filter(Boolean::booleanValue).count()).isEqualTo(1);
        assertThat(replayGuard.consume(source.user().getId(),secret,99)).isFalse();
        assertThat(replayGuard.consume(source.user().getId(),secret,101)).isTrue();
    }
    @Test void twoChallengeStoreInstancesConsumeSharedChallengeOnce() throws Exception {
        var second=new LoginChallengeStore(redis);
        var id=UUID.randomUUID().toString(); var now=Instant.now();
        challenges.start(id,"synthetic@example.invalid","/dashboard",now.plusSeconds(300));
        assertThat(parallel(()->challenges.take(id,now).isPresent(),()->second.take(id,now).isPresent())
                .stream().filter(Boolean::booleanValue).count()).isEqualTo(1);
        assertThat(challenges.read(id,now)).isEmpty();
    }
    @Test void fiveConcurrentChallengeFailuresDoNotLoseIncrements() throws Exception {
        var id=UUID.randomUUID().toString(); var now=Instant.now();
        challenges.start(id,"synthetic@example.invalid","/dashboard",now.plusSeconds(300));
        parallel(()->challenges.failure(id,now),()->challenges.failure(id,now));
        parallel(()->challenges.failure(id,now),()->challenges.failure(id,now));
        assertThat(challenges.read(id,now).orElseThrow().attempts()).isEqualTo(4);
        assertThat(challenges.failure(id,now)).isFalse();
        assertThat(challenges.take(id,now)).isEmpty();
    }
    @Test void separateSessionWrappersDoNotOverwriteParallelDrafts() throws Exception {
        var id=UUID.randomUUID().toString();
        var firstSession=new org.springframework.mock.web.MockHttpSession(null,id);
        var secondSession=new org.springframework.mock.web.MockHttpSession(null,id);
        var secondStore=new org.demo.bankdemo.transfer.TransferDraftStore(redis);
        var a=new org.demo.bankdemo.transfer.TransferCommand("a","Synthetic","22222222222222222222222222",BigDecimal.TEN,"A");
        var b=new org.demo.bankdemo.transfer.TransferCommand("b","Synthetic","22222222222222222222222222",BigDecimal.TEN,"B");
        parallel(()->{drafts.save(firstSession,a);return true;},()->{secondStore.save(secondSession,b);return true;});
        assertThat(secondStore.find(secondSession,"a")).contains(a);
        assertThat(drafts.find(firstSession,"b")).contains(b);
    }
    @Test void corruptSeedAbortsWholeRotationBatchAndPreservesOriginalCiphertext() {
        var first=client(); var corrupt=client();
        var oldKey=Base64.getEncoder().encodeToString(new byte[32]);
        var newBytes=new byte[32]; Arrays.fill(newBytes,(byte)7);
        var oldCipher=new org.demo.bankdemo.security.TotpSecretCipher(oldKey);
        var original=oldCipher.encrypt("synthetic-seed");
        jdbc.update("UPDATE users SET totp_secret_encrypted=? WHERE id=?",original,first.user().getId());
        jdbc.update("UPDATE users SET totp_secret_encrypted='corrupt-synthetic-ciphertext' WHERE id=?",corrupt.user().getId());
        var cipher=new org.demo.bankdemo.security.TotpSecretCipher(oldKey,"next="+Base64.getEncoder().encodeToString(newBytes),"next");
        var rotation=new org.demo.bankdemo.security.TotpKeyRotationService(jdbc,cipher);
        assertThatThrownBy(()->new org.springframework.transaction.support.TransactionTemplate(transactionManager)
                .execute(status->rotation.rotateBatch(first.user().getId()-1,100,false))).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT totp_secret_encrypted FROM users WHERE id=?",String.class,first.user().getId())).isEqualTo(original);
        jdbc.update("UPDATE users SET totp_secret_encrypted=NULL WHERE id=?",corrupt.user().getId());
    }
    @Test void nonexistentCardHasDurableDeclineAfterRollbackAndCanBeReadWithoutLockingReadOnlyTransaction() {
        var destination=client();var key=UUID.randomUUID().toString();var order=UUID.randomUUID().toString();
        var request=new org.demo.bankdemo.card.CardPaymentRequest("1234567890123456","123","12/30",destination.number(),
                order,"PLN",BigDecimal.TEN,BigDecimal.TEN,"Synthetic card purchase");
        var result=merchantPayments.payCard(merchant(destination),key,request);
        assertThat(result.status()).isEqualTo("DECLINED");
        assertThat(merchantPayments.cardStatus(merchant(destination),key,destination.number(),order).status()).isEqualTo("DECLINED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bank_transactions WHERE idempotency_key=?",Integer.class,key)).isZero();
        assertThat(balance(destination)).isEqualByComparingTo("1000");
    }
    @Test void actualTotpVerifierRejectsReplayAcrossParallelRequests() throws Exception {
        var source=client();
        var secret=new dev.samstevens.totp.secret.DefaultSecretGenerator().generate();
        var user=users.findById(source.user().getId()).orElseThrow();
        user.enableTotp(cipher.encrypt(secret),Instant.now(clock)); users.saveAndFlush(user);
        var code=new dev.samstevens.totp.code.DefaultCodeGenerator(dev.samstevens.totp.code.HashingAlgorithm.SHA1,6)
                .generate(secret,Instant.now(clock).getEpochSecond()/30);
        assertThat(parallel(()->twoFactor.verifyLoginCode(user.getEmail(),code),()->twoFactor.verifyLoginCode(user.getEmail(),code))
                .stream().filter(Boolean::booleanValue).count()).isEqualTo(1);
        assertThat(twoFactor.verifyLoginCode(user.getEmail(),code)).isFalse();
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints={-2,-1,0,1,2})
    void totpWindowBoundariesAreDeterministicAndConsumptionSurvivesClockRollback(int offset)throws Exception{
        var source=client();var secret=new dev.samstevens.totp.secret.DefaultSecretGenerator().generate();
        var instant=Instant.parse("2026-10-21T12:00:15Z");long step=instant.getEpochSecond()/30;
        var user=users.findById(source.user().getId()).orElseThrow();user.enableTotp(cipher.encrypt(secret),instant);users.saveAndFlush(user);
        var verifier=new org.demo.bankdemo.profile.TwoFactorService(users,recoveryCodes,encoder,cipher,java.time.Clock.fixed(instant,java.time.ZoneOffset.UTC),replayGuard);
        var generator=new dev.samstevens.totp.code.DefaultCodeGenerator(dev.samstevens.totp.code.HashingAlgorithm.SHA1,6);
        var code=generator.generate(secret,step+offset);var tx=new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        Boolean accepted=tx.execute(status->verifier.verifyLoginCode(user.getEmail(),code));assertThat(accepted).isEqualTo(Math.abs(offset)<=1);
        if(Math.abs(offset)<=1){
            Boolean replayed=tx.execute(status->verifier.verifyLoginCode(user.getEmail(),code));assertThat(replayed).isFalse();
            assertThat(replayGuard.consume(user.getId(),secret,step+offset-1)).isFalse();
            assertThat(replayGuard.consume(user.getId(),new dev.samstevens.totp.secret.DefaultSecretGenerator().generate(),step+offset-1)).isTrue();
        }
    }
    @Test void oneRecoveryCodeHasOneWinnerAcrossParallelRealTransactions()throws Exception{
        var source=client();var user=users.findById(source.user().getId()).orElseThrow();
        user.enableTotp(cipher.encrypt(new dev.samstevens.totp.secret.DefaultSecretGenerator().generate()),Instant.now());users.saveAndFlush(user);
        var code=UUID.randomUUID().toString();recoveryCodes.saveAndFlush(new org.demo.bankdemo.profile.TotpRecoveryCode(user,encoder.encode(code),Instant.now()));
        assertThat(parallel(()->twoFactor.verifyLoginCode(user.getEmail(),code),()->twoFactor.verifyLoginCode(user.getEmail(),code)).stream().filter(Boolean::booleanValue).count()).isEqualTo(1);
        assertThat(twoFactor.verifyLoginCode(user.getEmail(),code)).isFalse();
    }
    @Test void financialUniqueConstraintAlsoRejectsBypassingThePurchaseOrchestrator() {
        var source=client(); var destination=client(); var order=UUID.randomUUID().toString();
        var first=create(destination,UUID.randomUUID().toString(),order,BigDecimal.TEN);
        authorizations.approve(first.authorizationId(),source.user().getEmail());
        assertThatThrownBy(()->payments.execute(merchant(destination).id(),UUID.randomUUID().toString(),
                new PaymentRequest(source.number(),destination.number(),order,BigDecimal.TEN,"PLN","Another key")))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(balance(source)).isEqualByComparingTo("990");
        assertThat(balance(destination)).isEqualByComparingTo("1010");
    }
    @Test void lostCommitAcknowledgementDoesNotTurnRealSuccessIntoDefinitiveRefusal() {
        var source=client(); var destination=client(); var key=UUID.randomUUID().toString();
        var response=create(destination,key,UUID.randomUUID().toString(),BigDecimal.TEN);
        // Real DB commit, followed by loss of the acknowledgement to the orchestrator.
        var uncertainManager=new org.springframework.transaction.PlatformTransactionManager() {
            public org.springframework.transaction.TransactionStatus getTransaction(org.springframework.transaction.TransactionDefinition definition) {
                return transactionManager.getTransaction(definition);
            }
            public void commit(org.springframework.transaction.TransactionStatus status) {
                transactionManager.commit(status);
                throw new org.springframework.transaction.TransactionSystemException("Synthetic lost commit acknowledgement");
            }
            public void rollback(org.springframework.transaction.TransactionStatus status) { transactionManager.rollback(status); }
        };
        var uncertain=new PaymentAuthorizationService(authorizationRepository,accounts,payments,authorizationProperties,
                clock,purchases,uncertainManager,entityManager);
        assertThatThrownBy(()->uncertain.approve(response.authorizationId(),source.user().getEmail()))
                .isInstanceOf(org.springframework.transaction.TransactionSystemException.class);
        assertThat(authorizations.statusByKey(merchant(destination),key).status()).isEqualTo("COMPLETED");
        assertThat(authorizations.approve(response.authorizationId(),source.user().getEmail())).contains("status=completed");
        assertThat(balance(source)).isEqualByComparingTo("990");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bank_transactions WHERE idempotency_key=?",Integer.class,key)).isEqualTo(1);
    }
    @Test void redeemedPaymentSurvivesExpiryRejectAndLostRedeemReply() {
        var source=client(); var destination=client(); var key=UUID.randomUUID().toString();
        var response=create(destination,key,UUID.randomUUID().toString(),BigDecimal.TEN);
        var callback=authorizations.approve(response.authorizationId(),source.user().getEmail());
        var code=org.springframework.web.util.UriComponentsBuilder.fromUriString(callback).build().getQueryParams().getFirst("code");
        var redeem=new PaymentAuthorizationRedeemRequest(code,response.state());
        var paid=authorizations.redeem(merchant(destination),response.authorizationId(),redeem);
        jdbc.update("UPDATE payment_authorizations SET expires_at=CURRENT_TIMESTAMP-INTERVAL '1 hour' WHERE id=?",response.authorizationId());
        assertThatThrownBy(()->authorizations.redeem(merchant(destination),response.authorizationId(),redeem))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        var status=authorizations.statusByKey(merchant(destination),key);
        assertThat(status.status()).isEqualTo("REDEEMED");
        assertThat(status.referenceNumber()).isEqualTo(paid.referenceNumber());
        assertThat(authorizations.approve(response.authorizationId(),source.user().getEmail())).contains("status=redeemed");
        assertThat(authorizations.reject(response.authorizationId())).contains("status=redeemed");
        assertThat(balance(source)).isEqualByComparingTo("990");
    }
    @Test void concurrentApproveAndRejectHaveOneConsistentFinancialOutcome() throws Exception {
        var source=client(); var destination=client(); var key=UUID.randomUUID().toString();
        var response=create(destination,key,UUID.randomUUID().toString(),BigDecimal.TEN);
        parallel(()->authorizations.approve(response.authorizationId(),source.user().getEmail()),
                ()->authorizations.reject(response.authorizationId()));
        var status=authorizations.statusByKey(merchant(destination),key).status();
        assertThat(status).isIn("COMPLETED","FAILED");
        int count=jdbc.queryForObject("SELECT count(*) FROM bank_transactions WHERE idempotency_key=?",Integer.class,key);
        assertThat(count).isEqualTo("COMPLETED".equals(status)?1:0);
        assertThat(balance(source)).isEqualByComparingTo("COMPLETED".equals(status)?"990":"1000");
        assertThat(balance(destination)).isEqualByComparingTo("COMPLETED".equals(status)?"1010":"1000");
    }
    @Test void merchantPurchaseUniquenessStillAllowsOneLinkedRefundAndItsReplay() {
        var source=client(); var destination=client(); var key=UUID.randomUUID().toString();
        var order=UUID.randomUUID().toString();
        var authorization=create(destination,key,order,BigDecimal.TEN);
        authorizations.approve(authorization.authorizationId(),source.user().getEmail());
        var reference=authorizations.status(merchant(destination),authorization.authorizationId()).referenceNumber();
        var refundKey=UUID.randomUUID().toString();
        var request=new RefundRequest(destination.number(),source.number(),order,BigDecimal.TEN,"PLN","Synthetic refund",reference);
        var first=merchantPayments.refund(merchant(destination),refundKey,request,"Synthetic refund");
        var replay=merchantPayments.refund(merchant(destination),refundKey,request,"Synthetic refund");
        assertThat(first.referenceNumber()).isEqualTo(replay.referenceNumber());
        assertThat(replay.replayed()).isTrue();
        assertThat(balance(source)).isEqualByComparingTo("1000");
        assertThat(balance(destination)).isEqualByComparingTo("1000");
        assertThatThrownBy(()->merchantPayments.refund(merchant(destination),UUID.randomUUID().toString(),request,"Synthetic refund"))
                .isInstanceOf(PaymentException.class);
    }
    @Test void concurrentInStoreCardReplayDebitsOnlyOnce() throws Exception {
        var source=client(); cards.attach(source.user().getEmail());
        var key=UUID.randomUUID().toString();
        Callable<org.demo.bankdemo.card.CardService.TransactionResult> pay=()->cards.pay(source.user().getEmail(),
                "Synthetic merchant",org.demo.bankdemo.currency.CurrencyCode.PLN,BigDecimal.TEN,key);
        assertThat(parallel(pay,pay)).allSatisfy(result->assertThat(result.status())
                .isEqualTo(org.demo.bankdemo.card.CardTransactionStatus.COMPLETED));
        assertThat(balance(source)).isEqualByComparingTo("990");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM card_transactions WHERE idempotency_key=?",Integer.class,key)).isEqualTo(1);
    }
    @Test void atomicCounterRepairsMissingExpiryWithoutExtendingExistingWindow() {
        var key="bank:audit-synthetic-counter:"+UUID.randomUUID();
        redis.opsForValue().set(key,"4");
        assertThat(org.demo.bankdemo.config.AtomicRedisCounter.increment(redis,key,java.time.Duration.ofSeconds(30))).isEqualTo(5);
        long before=redis.getExpire(key,TimeUnit.MILLISECONDS);
        assertThat(before).isBetween(1L,30000L);
        assertThat(org.demo.bankdemo.config.AtomicRedisCounter.increment(redis,key,java.time.Duration.ofMinutes(10))).isEqualTo(6);
        assertThat(redis.getExpire(key,TimeUnit.MILLISECONDS)).isBetween(1L,before);
        redis.delete(key);
    }
    @Test void rotationDryRunAndCommittedPageKeepTheSameTotpSeed() {
        var source=client(); var seed="SYNTHETICROTATIONSEED";
        var original=cipher.encrypt(seed);
        jdbc.update("UPDATE users SET totp_secret_encrypted=? WHERE id=?",original,source.user().getId());
        var keyBytes=new byte[32]; Arrays.fill(keyBytes,(byte)31);
        var syntheticLegacy=Base64.getEncoder().encodeToString("0123456789ABCDEF0123456789ABCDEF".getBytes(StandardCharsets.UTF_8));
        var rotatingCipher=new org.demo.bankdemo.security.TotpSecretCipher(syntheticLegacy,
                "next="+Base64.getEncoder().encodeToString(keyBytes),"next");
        var rotation=new org.demo.bankdemo.security.TotpKeyRotationService(jdbc,rotatingCipher);
        var transaction=new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        var dry=transaction.execute(status->rotation.rotateBatch(source.user().getId()-1,1,true));
        assertThat(dry.requiringMigration()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT totp_secret_encrypted FROM users WHERE id=?",String.class,source.user().getId())).isEqualTo(original);
        var done=transaction.execute(status->rotation.rotateBatch(source.user().getId()-1,1,false));
        var changed=jdbc.queryForObject("SELECT totp_secret_encrypted FROM users WHERE id=?",String.class,source.user().getId());
        assertThat(done.nextCheckpoint()).isEqualTo(source.user().getId());
        assertThat(changed).startsWith("v1.next.");
        assertThat(rotatingCipher.decrypt(changed)).isEqualTo(seed);
    }
    @Test void administrativeRotationKeepsCheckpointAndSafeReplayInSameTransaction() {
        var source=client(); var seed="SYNTHETICADMINROTATION";
        var original=cipher.encrypt(seed);
        jdbc.update("UPDATE users SET totp_secret_encrypted=? WHERE id=?",original,source.user().getId());
        var next=new byte[32]; Arrays.fill(next,(byte)47);
        var legacy=Base64.getEncoder().encodeToString("0123456789ABCDEF0123456789ABCDEF".getBytes(StandardCharsets.UTF_8));
        var active=new org.demo.bankdemo.security.TotpSecretCipher(legacy,"next="+Base64.getEncoder().encodeToString(next),"next");
        var admin=new org.demo.bankdemo.maintenance.TotpRotationAdministration(jdbc.getDataSource(),active);
        var plan=admin.plan(source.user().getId()-1,1);
        assertThat(plan.requiringMigration()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT totp_secret_encrypted FROM users WHERE id=?",String.class,source.user().getId())).isEqualTo(original);
        var applied=admin.apply(source.user().getId()-1,1,plan.planDigest(),"ROTATE");
        var encrypted=jdbc.queryForObject("SELECT totp_secret_encrypted FROM users WHERE id=?",String.class,source.user().getId());
        assertThat(active.decrypt(encrypted)).isEqualTo(seed);
        assertThat(applied.nextCheckpoint()).isEqualTo(source.user().getId());
        assertThat(admin.apply(source.user().getId()-1,1,plan.planDigest(),"ROTATE").replayed()).isTrue();
        assertThat(jdbc.queryForObject("SELECT totp_secret_encrypted FROM users WHERE id=?",String.class,source.user().getId())).isEqualTo(encrypted);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM totp_rotation_runs WHERE plan_digest=?",Integer.class,plan.planDigest())).isEqualTo(1);
    }

    @Test void changedRotationInputAbortsWithoutAdvancingCheckpoint() {
        var source=client();
        jdbc.update("UPDATE users SET totp_secret_encrypted=? WHERE id=?",cipher.encrypt("FIRSTSEED"),source.user().getId());
        var admin=new org.demo.bankdemo.maintenance.TotpRotationAdministration(jdbc.getDataSource(),cipher);
        var plan=admin.plan(source.user().getId()-1,1);
        var changed=cipher.encrypt("SECONDSEED");
        jdbc.update("UPDATE users SET totp_secret_encrypted=? WHERE id=?",changed,source.user().getId());
        assertThatThrownBy(()->admin.apply(source.user().getId()-1,1,plan.planDigest(),"ROTATE")).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM totp_rotation_runs WHERE plan_digest=?",Integer.class,plan.planDigest())).isZero();
        assertThat(jdbc.queryForObject("SELECT totp_secret_encrypted FROM users WHERE id=?",String.class,source.user().getId())).isEqualTo(changed);
    }

    @Test void completedTransferRetryDoesNotRequireNewTotpAndNeverAcceptsDifferentData() {
        var source=client(); var destination=client();
        var command=new org.demo.bankdemo.transfer.TransferCommand(UUID.randomUUID().toString(),"Recipient",destination.number(),new BigDecimal("10.00"),"Retry");
        var result=applicationTransferService.execute(source.user().getEmail(),command);
        var before=balance(source);
        var replay=transferWorkflow.execute(source.user().getEmail(),command,null,null,new org.springframework.mock.web.MockHttpSession());
        assertThat(replay.result().referenceNumber()).isEqualTo(result.referenceNumber());
        assertThat(balance(source)).isEqualByComparingTo(before);
        var changed=new org.demo.bankdemo.transfer.TransferCommand(command.idempotencyKey(),"Recipient",destination.number(),new BigDecimal("11.00"),"Retry");
        assertThatThrownBy(()->transferWorkflow.execute(source.user().getEmail(),changed,null,null,new org.springframework.mock.web.MockHttpSession()))
                .isInstanceOf(org.demo.bankdemo.transfer.TransferException.class);
    }

    @Autowired org.demo.bankdemo.transfer.TransferService applicationTransferService;
    @Autowired org.demo.bankdemo.transfer.TransferConfirmationWorkflow transferWorkflow;
    @Autowired org.demo.bankdemo.transfer.ScheduledTransferRepository scheduledRepository;
    @Autowired org.demo.bankdemo.history.HistoryService historyService;

    @Test void boundedReadsWithFiveThousandSyntheticRowsAndImmutableSchedulerCursor() throws Exception {
        var source=client(); var destination=client();
        var prefix="perf-"+source.user().getId()+"-";
        jdbc.update("INSERT INTO scheduled_transfers(user_id,destination_account_number,recipient_name,amount,title,frequency,next_execution_at,scheduled_for_at,first_execution_at,idempotency_key) SELECT ?,?,'Synthetic',1,'Performance fixture','DAILY',now()-INTERVAL '1 day',now()-INTERVAL '1 day',now()-INTERVAL '1 day',?||n FROM generate_series(1,5000) n",
                source.user().getId(), destination.number(), prefix);
        var now=Instant.now(); var upper=scheduledRepository.findDueUpperId(now);
        long cursor=0, count=0; int largest=0;
        long started=System.nanoTime();
        while(true) {
            var rows=scheduledRepository.findDueBatchAfter(now,cursor,upper,org.springframework.data.domain.PageRequest.of(0,100));
            if(rows.isEmpty()) break;
            largest=Math.max(largest,rows.size()); count+=rows.size(); cursor=rows.getLast().id();
            // It remains due, but moving a mutable due date cannot put it back ahead of the ID cursor.
            jdbc.update("UPDATE scheduled_transfers SET next_execution_at=next_execution_at+INTERVAL '1 second' WHERE id=?",rows.getFirst().id());
        }
        long schedulerMillis=java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started);
        assertThat(count).isEqualTo(5000); assertThat(largest).isEqualTo(100);
        jdbc.update("INSERT INTO bank_transactions(reference_number,idempotency_key,destination_account_id,recipient_name_snapshot,amount,currency,title,type,status,created_at,completed_at) SELECT ?||n,?||n,?,'Synthetic',1,'PLN','Performance fixture','INITIAL_CREDIT','COMPLETED',now(),now() FROM generate_series(1,5000) n",
                prefix,prefix,source.accountId());
        started=System.nanoTime();
        assertThatThrownBy(()->historyService.exportRowsFor(source.user().getEmail(),new org.demo.bankdemo.history.HistoryFilter()))
                .isInstanceOf(org.demo.bankdemo.history.HistoryExportLimitException.class);
        long exportMillis=java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started);
        var sqlPlan=jdbc.queryForList("EXPLAIN (ANALYZE,BUFFERS) SELECT id,next_execution_at FROM scheduled_transfers WHERE active=true AND next_execution_at<=now() AND id>0 AND id<=? ORDER BY id LIMIT 100",String.class,upper);
        var metrics=Map.of("syntheticRows",5000,"largestSchedulerPage",largest,"schedulerScanMillis",schedulerMillis,
                "rejectedHistoryExportMillis",exportMillis,"explain",sqlPlan,
                "scope","Local synthetic measurement, not a production SLO or a load-test acceptance.");
        java.nio.file.Files.writeString(java.nio.file.Path.of("target/audit-performance-20261018.json"),
                new com.fasterxml.jackson.databind.ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(metrics));
    }

    @Test void paymentMigrationInventoryNeverAssignsOrChangesHistoricalRows() {
        var count=jdbc.queryForObject("SELECT count(*) FROM payment_authorizations",Long.class);
        var result=new org.demo.bankdemo.maintenance.PaymentMigrationReview(jdbc.getDataSource()).review();
        assertThat(result.status()).isIn("STOP_MAPPING_REQUIRED","INVENTORY_ONLY");
        assertThat(result.counts()).containsKey("unassignedAuthorizations");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_authorizations",Long.class)).isEqualTo(count);
    }
    @Test void committedPaymentWithLostCommitAcknowledgementIsRecoveredWithoutAnotherDebit() throws Exception {
        var source=client(); var destination=client(); var key=UUID.randomUUID().toString();
        var authorization=create(destination,key,UUID.randomUUID().toString(),BigDecimal.TEN);
        var lost=new java.util.concurrent.atomic.AtomicBoolean(false);
        doAnswer(invocation->{
            var connection=(java.sql.Connection)invocation.callRealMethod();
            return java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{java.sql.Connection.class},(proxy,method,args)->{
                boolean inject=false;
                if(method.getName().equals("commit") && !lost.get()) {
                    try(var statement=connection.prepareStatement("SELECT count(*) FROM bank_transactions WHERE idempotency_key=?")) {
                        statement.setString(1,key);
                        try(var rows=statement.executeQuery()) {rows.next(); inject=rows.getInt(1)==1;}
                    }
                }
                try {
                    var result=method.invoke(connection,args);
                    if(inject && lost.compareAndSet(false,true))
                        throw new java.sql.SQLRecoverableException("Synthetic acknowledgement lost AFTER successful commit","08006");
                    return result;
                } catch(java.lang.reflect.InvocationTargetException exception) {throw exception.getCause();}
            });
        }).when(dataSource).getConnection();
        assertThatThrownBy(()->authorizations.approve(authorization.authorizationId(),source.user().getEmail()))
                .isInstanceOf(RuntimeException.class);
        assertThat(lost).isTrue();
        assertThat(balance(source)).isEqualByComparingTo("990");
        assertThat(balance(destination)).isEqualByComparingTo("1010");
        assertThat(authorizations.status(merchant(destination),authorization.authorizationId()).status()).isEqualTo("COMPLETED");
        assertThat(authorizations.approve(authorization.authorizationId(),source.user().getEmail())).contains("status=completed");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bank_transactions WHERE idempotency_key=?",Integer.class,key)).isEqualTo(1);
        assertThat(balance(source)).isEqualByComparingTo("990");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"ARE","AER","RAE","REA","EAR","ERA"})
    void authorizationExpiryRejectAndApprovalInEverySequentialOrderPreserveFinalLedger(String sequence){
        var source=client();var destination=client();var key=UUID.randomUUID().toString();
        var authorization=create(destination,key,UUID.randomUUID().toString(),BigDecimal.TEN);
        boolean paid=false;
        for(char event:sequence.toCharArray()){
            switch(event){
                case 'A' -> authorizations.approve(authorization.authorizationId(),source.user().getEmail());
                case 'R' -> authorizations.reject(authorization.authorizationId());
                case 'E' -> {jdbc.update("UPDATE payment_authorizations SET expires_at=now()-interval '1 hour' WHERE id=?",authorization.authorizationId());authorizations.statusByKey(merchant(destination),key);}
                default -> throw new IllegalArgumentException();
            }
            var state=authorizations.statusByKey(merchant(destination),key).status();
            if(paid)assertThat(state).isEqualTo("COMPLETED");
            if(state.equals("COMPLETED"))paid=true;
            assertThat(jdbc.queryForObject("SELECT count(*) FROM bank_transactions WHERE idempotency_key=?",Integer.class,key)).isEqualTo(paid?1:0);
        }
        assertThat(balance(source)).isEqualByComparingTo(paid?"990":"1000");assertThat(balance(destination)).isEqualByComparingTo(paid?"1010":"1000");
    }

    @Test void physicallyTerminatedPostgresConnectionBeforeCommitRollsBackAndSameKeyRecovers()throws Exception{
        var source=client();var destination=client();var key=UUID.randomUUID().toString();
        var authorization=create(destination,key,UUID.randomUUID().toString(),BigDecimal.TEN);
        var cut=new java.util.concurrent.atomic.AtomicBoolean();
        doAnswer(invocation->{
            var connection=(java.sql.Connection)invocation.callRealMethod();
            return java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{java.sql.Connection.class},(proxy,method,args)->{
                if(method.getName().equals("commit")&&!cut.get()){
                    try(var statement=connection.prepareStatement("SELECT pg_backend_pid() FROM bank_transactions WHERE idempotency_key=?")){
                        statement.setString(1,key);try(var rows=statement.executeQuery()){
                            if(rows.next()&&cut.compareAndSet(false,true)){
                                // A real second connection terminates precisely this disposable backend, not the DB service.
                                try(var killer=java.sql.DriverManager.getConnection(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword());
                                    var terminate=killer.prepareStatement("SELECT pg_terminate_backend(?)")){
                                    terminate.setInt(1,rows.getInt(1));try(var result=terminate.executeQuery()){assertThat(result.next()).isTrue();assertThat(result.getBoolean(1)).isTrue();}
                                }
                            }
                        }
                    }
                }
                try{return method.invoke(connection,args);}catch(java.lang.reflect.InvocationTargetException failure){throw failure.getCause();}
            });
        }).when(dataSource).getConnection();
        assertThatThrownBy(()->authorizations.approve(authorization.authorizationId(),source.user().getEmail())).isInstanceOf(RuntimeException.class);
        assertThat(cut).isTrue();assertThat(balance(source)).isEqualByComparingTo("1000");assertThat(balance(destination)).isEqualByComparingTo("1000");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bank_transactions WHERE idempotency_key=?",Integer.class,key)).isZero();
        assertThat(authorizations.statusByKey(merchant(destination),key).status()).isEqualTo("PENDING");
        assertThat(authorizations.approve(authorization.authorizationId(),source.user().getEmail())).contains("status=approved");
        assertThat(balance(source)).isEqualByComparingTo("990");assertThat(balance(destination)).isEqualByComparingTo("1010");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bank_transactions WHERE idempotency_key=?",Integer.class,key)).isEqualTo(1);
    }

    static String hash(String raw) {
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8)));}
        catch(Exception e){throw new IllegalStateException(e);}
    }
    static <T> List<T> parallel(Callable<T> a,Callable<T> b) throws Exception {
        var ready=new CountDownLatch(2); var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var first=pool.submit(()->{ready.countDown();if(!start.await(10,TimeUnit.SECONDS))throw new IllegalStateException("start timed out");return a.call();});
            var second=pool.submit(()->{ready.countDown();if(!start.await(10,TimeUnit.SECONDS))throw new IllegalStateException("start timed out");return b.call();});
            assertThat(ready.await(10,TimeUnit.SECONDS)).isTrue();start.countDown();
            return List.of(first.get(30,TimeUnit.SECONDS),second.get(30,TimeUnit.SECONDS));
        }
    }
}
