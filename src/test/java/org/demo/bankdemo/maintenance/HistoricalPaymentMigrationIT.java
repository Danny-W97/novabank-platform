package org.demo.bankdemo.maintenance;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import java.math.BigDecimal;
import java.util.*;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;

/** Disposable databases only. Stripe evidence is synthetic; no network/account keys are used. */
class HistoricalPaymentMigrationIT {
    static PostgreSQLContainer bankContainer,shopContainer;
    static DriverManagerDataSource bankSource,shopSource;
    static JdbcTemplate bank,shop;
    static final String ACCOUNT="22222222222222222222222222", SOURCE="33333333333333333333333333";
    HistoricalPaymentMigration migration;
    Map<String,Object> stripe;
    @BeforeAll static void start(){
        bankContainer=new PostgreSQLContainer(DockerImageName.parse("postgres:18"));
        shopContainer=new PostgreSQLContainer(DockerImageName.parse("postgres:18"));
        try {
            bankContainer.start();shopContainer.start();
            bankSource=new DriverManagerDataSource(bankContainer.getJdbcUrl(),bankContainer.getUsername(),bankContainer.getPassword());
            shopSource=new DriverManagerDataSource(shopContainer.getJdbcUrl(),shopContainer.getUsername(),shopContainer.getPassword());
            Flyway.configure().dataSource(bankSource).locations("filesystem:src/main/resources/db/migration").load().migrate();
            Flyway.configure().dataSource(shopSource).locations("filesystem:shop-app/src/main/resources/db/migration").load().migrate();
            bank=new JdbcTemplate(bankSource);shop=new JdbcTemplate(shopSource);
        }catch(RuntimeException failure){stop();throw failure;}
    }
    @AfterAll static void stop(){if(shopContainer!=null)shopContainer.stop();if(bankContainer!=null)bankContainer.stop();}
    @BeforeEach void clean(){
        // Exact tables in THIS test's containers; not the user checkout's Compose databases.
        shop.execute("TRUNCATE shop_purchase_cancellation_events,shop_purchase_cancellation_requests,shop_payment_case_events,shop_payment_case_plans,shop_payment_cases,shop_purchase_snapshots,historical_payment_migration_runs,shop_order_items,shop_refunds,shop_payment_attempts,shop_orders RESTART IDENTITY");
        bank.execute("TRUNCATE payment_purchase_attempts,payment_purchases,payment_authorizations,card_transactions,bank_transactions,accounts,users CASCADE");
        for(var pair:List.of(new String[]{"source@example.invalid",SOURCE},new String[]{"merchant@example.invalid",ACCOUNT})){
            var id=bank.queryForObject("INSERT INTO users(email,password_hash,first_name,last_name,role,status,password_changed_at,created_at,updated_at) VALUES (?,'synthetic','Synthetic','Client','USER','ACTIVE',now(),now(),now()) RETURNING id",Long.class,pair[0]);
            bank.update("INSERT INTO accounts(account_number,owner_id,balance,currency,status,created_at,updated_at) VALUES (?,?,1000,'PLN','ACTIVE',now(),now())",pair[1],id);
        }
        stripe=null;
        migration=new HistoricalPaymentMigration(bankSource,shopSource,Map.of("SHOP_MERCHANT_ID","synthetic-shop","SHOP_MERCHANT_ACCOUNT_NUMBER",ACCOUNT,
                "BANK_API_KEY_MERCHANTS","legacy=synthetic-shop","BANK_PAYMENT_MERCHANTS","synthetic-shop="+ACCOUNT,
                "MIGRATION_BANK_BACKUP_SHA256","a".repeat(64),"MIGRATION_SHOP_BACKUP_SHA256","b".repeat(64)), id->stripe);
    }
    record Fixture(long id,String number,String key,String reference){}
    Fixture fixture(boolean success){
        var number=UUID.randomUUID().toString();var key="checkout-"+number;var reference="H"+UUID.randomUUID().toString().replace("-","").substring(0,30);
        var id=shop.queryForObject("INSERT INTO shop_orders(order_number,customer_id,merchant_account_number,total,currency,status,payment_idempotency_key) VALUES (?,'synthetic-customer',?,10,'PLN','FAILED',?) RETURNING id",Long.class,number,ACCOUNT,key);
        if(success){
            var source=bank.queryForObject("SELECT id FROM accounts WHERE account_number=?",Long.class,SOURCE);
            var target=bank.queryForObject("SELECT id FROM accounts WHERE account_number=?",Long.class,ACCOUNT);
            bank.update("UPDATE accounts SET balance=balance-10 WHERE id=?",source);bank.update("UPDATE accounts SET balance=balance+10 WHERE id=?",target);
            bank.update("""
                    INSERT INTO bank_transactions(reference_number,idempotency_key,source_account_id,destination_account_id,
                    recipient_name_snapshot,amount,currency,title,type,status,created_at,completed_at,payment_order_id)
                    VALUES (?,?,?,?,'Synthetic Merchant',10,'PLN','Synthetic history','PAYMENT','COMPLETED',now(),now(),?)
                    """,reference,key,source,target,number);
            bank.update("""
                    INSERT INTO payment_authorizations(id,shop_order_id,shop_customer_id,merchant_account_number,amount,currency,title,
                    payment_idempotency_key,state,return_url,status,source_account_number,bank_reference,created_at,expires_at,completed_at)
                    VALUES (?,?,'synthetic-customer',?,10,'PLN','Synthetic history',?,?,'http://localhost:8081/payment/callback','COMPLETED',?,?,now(),now(),now())
                    """,UUID.randomUUID().toString(),number,ACCOUNT,key,UUID.randomUUID().toString(),SOURCE,reference);
        }
        return new Fixture(id,number,key,reference);
    }
    @Test void onlyProvenSuccessIsImportedAndReplayDoesNotTouchBalancesOrCarts()throws Exception{
        var paid=fixture(true);var uncertain=fixture(false);
        var balances=bank.queryForList("SELECT id,balance FROM accounts ORDER BY id");
        var plan=migration.plan();assertThat(plan.eligible()).isEqualTo(1);assertThat(plan.blocked()).hasSize(1);
        assertThat(shop.queryForObject("SELECT merchant_id FROM shop_orders WHERE id=?",String.class,paid.id())).isNull();
        var result=migration.apply(plan.planDigest(),"MIGRATE",true,true);assertThat(result.completed()).isEqualTo(1);
        assertThat(migration.apply(plan.planDigest(),"MIGRATE",true,true).replayed()).isTrue();
        assertThat(shop.queryForObject("SELECT status FROM shop_orders WHERE id=?",String.class,paid.id())).isEqualTo("PAID");
        assertThat(shop.queryForObject("SELECT merchant_id FROM shop_orders WHERE id=?",String.class,uncertain.id())).isNull();
        assertThat(shop.queryForObject("SELECT count(*) FROM shop_orders",Integer.class)).isEqualTo(2);
        assertThat(shop.queryForObject("SELECT count(*) FROM shop_payment_attempts",Integer.class)).isEqualTo(1);
        assertThat(shop.queryForObject("SELECT historical_imported_at IS NOT NULL AND cart_cleanup_completed_at IS NULL FROM shop_orders WHERE id=?",Boolean.class,paid.id())).isTrue();
        assertThat(bank.queryForList("SELECT id,balance FROM accounts ORDER BY id")).isEqualTo(balances);
        assertThat(bank.queryForObject("SELECT count(*) FROM bank_transactions",Integer.class)).isEqualTo(1);
    }
    @Test void bankCommitThenShopFailureLeavesBlockAndCanResumeSameManifest()throws Exception{
        var row=fixture(true);var plan=migration.plan();
        shop.execute("CREATE FUNCTION fail_synthetic_import() RETURNS trigger LANGUAGE plpgsql AS 'BEGIN RAISE EXCEPTION ''synthetic failure''; END'");
        shop.execute("CREATE TRIGGER fail_synthetic_import BEFORE UPDATE ON shop_orders FOR EACH ROW EXECUTE FUNCTION fail_synthetic_import()");
        try {
            assertThatThrownBy(()->migration.apply(plan.planDigest(),"MIGRATE",true,true)).isInstanceOf(Exception.class);
            assertThat(bank.queryForObject("SELECT count(*) FROM payment_purchase_attempts WHERE state='SUCCEEDED'",Integer.class)).isEqualTo(1);
            assertThat(shop.queryForObject("SELECT merchant_id FROM shop_orders WHERE id=?",String.class,row.id())).isNull();
            assertThat(shop.queryForObject("SELECT count(*) FROM shop_payment_attempts",Integer.class)).isZero();
        }finally{shop.execute("DROP TRIGGER fail_synthetic_import ON shop_orders");shop.execute("DROP FUNCTION fail_synthetic_import()");}
        assertThat(migration.apply(plan.planDigest(),"MIGRATE",true,true).completed()).isEqualTo(1);
        assertThat(bank.queryForObject("SELECT count(*) FROM payment_purchase_attempts",Integer.class)).isEqualTo(1);
    }
    @Test void changedOrderOrMissingConsentCannotBeImported()throws Exception{
        var row=fixture(true);var plan=migration.plan();
        assertThatThrownBy(()->migration.apply(plan.planDigest(),"MIGRATE",false,true)).isInstanceOf(IllegalArgumentException.class);
        shop.update("UPDATE shop_orders SET total=11 WHERE id=?",row.id());
        assertThatThrownBy(()->migration.apply(plan.planDigest(),"MIGRATE",true,true)).isInstanceOf(IllegalStateException.class);
        assertThat(bank.queryForObject("SELECT count(*) FROM payment_purchases",Integer.class)).isZero();
        assertThat(shop.queryForObject("SELECT count(*) FROM historical_payment_migration_runs",Integer.class)).isZero();
    }
    @Test void partialBatchReplaysCompletedRowsButRejectsChangedImportedIdentity()throws Exception{
        var first=fixture(true);var second=fixture(true);var plan=migration.plan();
        shop.execute("CREATE FUNCTION fail_synthetic_import() RETURNS trigger LANGUAGE plpgsql AS 'BEGIN RAISE EXCEPTION ''synthetic failure''; END'");
        shop.execute("CREATE TRIGGER fail_synthetic_import BEFORE UPDATE ON shop_orders FOR EACH ROW WHEN (OLD.id="+second.id()+") EXECUTE FUNCTION fail_synthetic_import()");
        try{assertThatThrownBy(()->migration.apply(plan.planDigest(),"MIGRATE",true,true)).isInstanceOf(Exception.class);}
        finally{shop.execute("DROP TRIGGER fail_synthetic_import ON shop_orders");shop.execute("DROP FUNCTION fail_synthetic_import()");}
        assertThat(shop.queryForObject("SELECT completed_orders FROM historical_payment_migration_runs",Integer.class)).isEqualTo(1);
        shop.update("UPDATE shop_orders SET total=11 WHERE id=?",first.id());
        assertThatThrownBy(()->migration.apply(plan.planDigest(),"MIGRATE",true,true)).isInstanceOf(IllegalStateException.class);
        shop.update("UPDATE shop_orders SET total=10 WHERE id=?",first.id());
        assertThat(migration.apply(plan.planDigest(),"MIGRATE",true,true).completed()).isEqualTo(2);
        assertThat(shop.queryForObject("SELECT count(*) FROM shop_payment_attempts",Integer.class)).isEqualTo(2);
        assertThat(bank.queryForObject("SELECT count(*) FROM bank_transactions",Integer.class)).isEqualTo(2);
    }
    @Test void stripeNeedsAuthoritativePaidSessionWithMatchingOrderAndAmount()throws Exception{
        var row=fixture(false);shop.update("UPDATE shop_orders SET payment_provider='STRIPE',external_payment_id='cs_test_synthetic' WHERE id=?",row.id());
        assertThat(migration.plan().eligible()).isZero();
        stripe=new HashMap<>(Map.of("id","cs_test_synthetic","livemode",false,"mode","payment","status","complete","payment_status","paid",
                "client_reference_id",row.number(),"currency","pln","amount_total",1000,"metadata",Map.of("orderNumber",row.number())));
        var valid=new HashMap<>(stripe);
        for(var changed:List.of(Map.<String,Object>of("amount_total",999),Map.<String,Object>of("payment_status","unpaid"),
                Map.<String,Object>of("client_reference_id","other-order"),Map.<String,Object>of("currency","eur"),Map.<String,Object>of("status","expired"),Map.<String,Object>of("livemode",true))){
            stripe=new HashMap<>(valid);stripe.putAll(changed);assertThat(migration.plan().eligible()).isZero();
        }
        stripe=valid;var plan=migration.plan();assertThat(plan.eligible()).isEqualTo(1);
        assertThat(migration.apply(plan.planDigest(),"MIGRATE",true,true).completed()).isEqualTo(1);
        assertThat(bank.queryForObject("SELECT count(*) FROM payment_purchases",Integer.class)).isZero();
        assertThat(shop.queryForObject("SELECT financial_reference FROM shop_payment_attempts",String.class)).isEqualTo("cs_test_synthetic");
    }
    @Test void conflictingSuccessfulPaymentsRemainBlocked(){
        var row=fixture(true);
        bank.update("""
                INSERT INTO bank_transactions(reference_number,idempotency_key,source_account_id,destination_account_id,recipient_name_snapshot,
                amount,currency,title,type,status,created_at,completed_at,payment_order_id)
                SELECT 'duplicate-proof','duplicate-proof',source_account_id,destination_account_id,recipient_name_snapshot,amount,currency,title,type,status,now(),now(),payment_order_id
                FROM bank_transactions WHERE idempotency_key=?
                """,row.key());
        assertThat(migration.plan().eligible()).isZero();
        assertThat(shop.queryForObject("SELECT merchant_id FROM shop_orders WHERE id=?",String.class,row.id())).isNull();
    }
    @Test void cardSuccessPreservesChargedCurrencyAndRequiresMatchingSource()throws Exception{
        var row=fixture(true);
        bank.update("DELETE FROM payment_authorizations WHERE shop_order_id=?",row.number());
        var source=bank.queryForObject("SELECT id FROM accounts WHERE account_number=?",Long.class,SOURCE);
        var card=bank.queryForObject("INSERT INTO demo_cards(account_id,masked_number,online_number,status,demo_cvv,demo_expires_at) VALUES (?,'synthetic-card','1111111111111111','ACTIVE','123',CURRENT_DATE+365) RETURNING id",Long.class,source);
        bank.update("""
                INSERT INTO card_transactions(card_id,merchant_name,currency,amount,amount_pln,idempotency_key,status,channel,
                merchant_account_number,order_id,bank_reference) VALUES (?,'Synthetic','EUR',2.50,10,?,'COMPLETED','ONLINE',?,?,?)
                """,card,row.key(),ACCOUNT,row.number(),row.reference());
        var plan=migration.plan();assertThat(plan.eligible()).isEqualTo(1);
        bank.update("UPDATE card_transactions SET amount_pln=11 WHERE order_id=?",row.number());
        assertThat(migration.plan().eligible()).isZero();
        bank.update("UPDATE card_transactions SET amount_pln=10 WHERE order_id=?",row.number());
        migration.apply(plan.planDigest(),"MIGRATE",true,true);
        assertThat(shop.queryForObject("SELECT charged_currency FROM shop_payment_attempts",String.class)).isEqualTo("EUR");
        assertThat(shop.queryForObject("SELECT charged_amount FROM shop_payment_attempts",BigDecimal.class)).isEqualByComparingTo("2.50");
        assertThat(bank.queryForObject("SELECT kind FROM payment_purchase_attempts",String.class)).isEqualTo("CARD");
    }
    @Test void historiesWithRefundsOrWrongPayerRemainBlocked(){
        var row=fixture(true);
        bank.update("UPDATE payment_authorizations SET source_account_number=? WHERE shop_order_id=?",ACCOUNT,row.number());
        assertThat(migration.plan().eligible()).isZero();
        bank.update("UPDATE payment_authorizations SET source_account_number=? WHERE shop_order_id=?",SOURCE,row.number());
        assertThat(migration.plan().eligible()).isEqualTo(1);
        shop.update("""
                INSERT INTO shop_refunds(order_id,order_number,idempotency_key,amount,currency,status,reason)
                VALUES (?,?,?,10,'PLN','COMPLETED','Synthetic refund')
                """,row.id(),row.number(),UUID.randomUUID().toString());
        assertThat(migration.plan().eligible()).isZero();
    }
    @Test void unlinkedLegacyRefundAlsoBlocksPaidOnlyMigration(){
        var row=fixture(true);
        bank.update("""
                INSERT INTO bank_transactions(reference_number,idempotency_key,source_account_id,destination_account_id,recipient_name_snapshot,
                amount,currency,title,type,status,created_at,completed_at,payment_order_id)
                SELECT 'legacy-refund','legacy-refund',destination_account_id,source_account_id,'Synthetic refund',amount,currency,title,
                type,status,now(),now(),'refund:'||payment_order_id FROM bank_transactions WHERE idempotency_key=?
                """,row.key());
        assertThat(migration.plan().eligible()).isZero();
        assertThat(shop.queryForObject("SELECT count(*) FROM shop_payment_attempts",Integer.class)).isZero();
    }
    @Test void packagedLauncherPlansAndAppliesWithoutStartingSpringOrFlyway()throws Exception{
        fixture(true);var plan=migration.plan();
        var migrations=shop.queryForObject("SELECT count(*) FROM flyway_schema_history",Integer.class);
        var review=launch("--operation=payment-migration-plan");
        assertThat(review).contains(plan.planDigest()).doesNotContain("Started BankDemoApplication");
        var applied=launch("--operation=payment-migration-apply","--planDigest="+plan.planDigest(),
                "--confirmation=MIGRATE","--backupsVerified=true","--writesPaused=true");
        assertThat(applied).contains("\"completed\":1","\"applied\":true");
        assertThat(shop.queryForObject("SELECT count(*) FROM flyway_schema_history",Integer.class)).isEqualTo(migrations);
    }
    String launch(String... options)throws Exception{
        var args=new ArrayList<>(List.of(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-Xmx128m",
                "-Dloader.main=org.demo.bankdemo.maintenance.MaintenanceCommand","-cp",
                Path.of("target/Bank-demo-0.0.1-SNAPSHOT.jar").toAbsolutePath().toString(),
                "org.springframework.boot.loader.launch.PropertiesLauncher","--profile=dev"));
        args.addAll(List.of(options));var builder=new ProcessBuilder(args).redirectErrorStream(true);
        var env=builder.environment();env.clear();
        for(var pair:List.of(new Object[]{"",bankContainer},new Object[]{"SHOP_",shopContainer})){
            var prefix=(String)pair[0];var container=(PostgreSQLContainer)pair[1];
            env.put(prefix+"POSTGRES_HOST","127.0.0.1");env.put(prefix+"POSTGRES_PORT",container.getFirstMappedPort().toString());
            env.put(prefix+"POSTGRES_DB",container.getDatabaseName());env.put(prefix+"POSTGRES_USER",container.getUsername());env.put(prefix+"POSTGRES_PASSWORD",container.getPassword());
        }
        env.put("SHOP_MERCHANT_ID","synthetic-shop");env.put("SHOP_MERCHANT_ACCOUNT_NUMBER",ACCOUNT);
        env.put("BANK_API_KEY_MERCHANTS","legacy=synthetic-shop");env.put("BANK_PAYMENT_MERCHANTS","synthetic-shop="+ACCOUNT);
        env.put("MIGRATION_BANK_BACKUP_SHA256","a".repeat(64));env.put("MIGRATION_SHOP_BACKUP_SHA256","b".repeat(64));
        var process=builder.start();
        try{
            assertThat(process.waitFor(25,TimeUnit.SECONDS)).isTrue();
            var output=new String(process.getInputStream().readNBytes(64000),StandardCharsets.UTF_8);
            assertThat(process.exitValue()).describedAs(output).isZero();return output;
        }finally{if(process.isAlive())process.destroyForcibly().waitFor();}
    }
}
