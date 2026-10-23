package org.demo.bankdemo.maintenance;

import org.junit.jupiter.api.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.demo.bankdemo.maintenance.HistoricalPaymentMigrationIT.*;

/** Only disposable databases. No operator keys, live checkout or existing DEV records. */
class PaymentCaseAdministrationIT {
    HistoricalPaymentMigrationIT fixtures;
    PaymentCaseAdministration cases;
    static final String REASON="Controlled synthetic evidence review";
    @BeforeAll static void startFixtures(){HistoricalPaymentMigrationIT.start();}
    @AfterAll static void stopFixtures(){HistoricalPaymentMigrationIT.stop();}
    @BeforeEach void clean(){
        fixtures=new HistoricalPaymentMigrationIT();fixtures.clean();
        cases=new PaymentCaseAdministration(bankSource,shopSource,Map.of("SHOP_MERCHANT_ID","synthetic-shop","SHOP_MERCHANT_ACCOUNT_NUMBER",ACCOUNT,
            "BANK_API_KEY_MERCHANTS","legacy=synthetic-shop","BANK_PAYMENT_MERCHANTS","synthetic-shop="+ACCOUNT,
            "MIGRATION_BANK_BACKUP_SHA256","a".repeat(64),"MIGRATION_SHOP_BACKUP_SHA256","b".repeat(64)),id->fixtures.stripe);
    }
    @Test void administrativeClosureAndRiskDecisionDoNotInventFinancialRefusal()throws Exception{
        var row=fixtures.fixture(false);var request=UUID.randomUUID();
        cases.decide(row.id(),0,request,"CLOSE_UNRESOLVED",REASON,"DECIDE");
        assertThat(cases.decide(row.id(),0,request,"CLOSE_UNRESOLVED",REASON,"DECIDE").replayed()).isTrue();
        assertThat(shop.queryForObject("SELECT financial_outcome FROM shop_payment_cases",String.class)).isEqualTo("UNKNOWN");
        assertThat(shop.queryForObject("SELECT merchant_id FROM shop_orders",String.class)).isNull();
        assertThat(shop.queryForObject("SELECT count(*) FROM shop_payment_attempts",Integer.class)).isZero();
        cases.decide(row.id(),1,UUID.randomUUID(),"ALLOW_NEW_PURCHASE",REASON,"DECIDE");
        assertThat(shop.queryForObject("SELECT allow_new_purchase_at IS NOT NULL FROM shop_payment_cases",Boolean.class)).isTrue();
        cases.decide(row.id(),2,UUID.randomUUID(),"REVOKE_NEW_PURCHASE",REASON,"DECIDE");
        assertThat(shop.queryForObject("SELECT allow_new_purchase_at IS NULL FROM shop_payment_cases",Boolean.class)).isTrue();
        assertThat(shop.queryForObject("SELECT count(*) FROM shop_payment_case_events",Integer.class)).isEqualTo(3);
        assertThatThrownBy(()->cases.decide(row.id(),0,UUID.randomUUID(),"ALLOW_NEW_PURCHASE",REASON,"DECIDE")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->cases.decide(row.id(),0,request,"ESCALATE",REASON,"DECIDE")).isInstanceOf(IllegalStateException.class);
    }
    @Test void absentBankResultAndOldFailedAreUnknownNotDeclined()throws Exception{
        var row=fixtures.fixture(false);var plan=cases.plan(row.id(),UUID.randomUUID(),REASON);
        assertThat(plan.resolution()).isEqualTo("UNKNOWN");
        assertThatThrownBy(()->cases.apply(row.id(),plan.planDigest(),UUID.randomUUID(),REASON,true,true,"RECONCILE")).isInstanceOf(IllegalStateException.class);
        assertThat(bank.queryForObject("SELECT count(*) FROM bank_transactions",Integer.class)).isZero();
    }
    @Test void closureWaitsForConcurrentPaymentCommitAndRefusesToArchivePaidOrder()throws Exception{
        var row=fixtures.fixture(false);
        shop.update("INSERT INTO shop_payment_cases(order_id) VALUES (?)",row.id());
        try(var payment=shopSource.getConnection();var pool=java.util.concurrent.Executors.newSingleThreadExecutor()){
            payment.setAutoCommit(false);
            try(var statement=payment.prepareStatement("UPDATE shop_orders SET status='PAID' WHERE id=?")){
                statement.setLong(1,row.id());statement.executeUpdate();
            }
            var entered=new java.util.concurrent.CountDownLatch(1);
            var closing=pool.submit(()->{entered.countDown();return cases.decide(row.id(),0,UUID.randomUUID(),"CLOSE_UNRESOLVED",REASON,"DECIDE");});
            try{
                assertThat(entered.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(()->closing.get(1,java.util.concurrent.TimeUnit.SECONDS)).isInstanceOf(java.util.concurrent.TimeoutException.class);
            }finally{payment.commit();}
            assertThatThrownBy(()->closing.get(10,java.util.concurrent.TimeUnit.SECONDS)).hasRootCauseInstanceOf(IllegalStateException.class);
        }
        assertThat(shop.queryForObject("SELECT workflow_status FROM shop_payment_cases WHERE order_id=?",String.class,row.id())).isEqualTo("OPEN");
    }
    @Test void confirmedCancellationSurvivesRecheckAndContradictoryChargeReopensReview()throws Exception{
        var cancelled=fixtures.fixture(false);
        shop.update("UPDATE shop_orders SET status='CANCELLED',cancelled_at=now(),merchant_id='synthetic-shop',purchase_id=?,payment_idempotency_key=NULL WHERE id=?",UUID.randomUUID(),cancelled.id());
        shop.update("INSERT INTO shop_payment_cases(order_id,financial_outcome,workflow_status) VALUES (?,'CANCELLED','RESOLVED')",cancelled.id());
        assertThat(cases.plan(cancelled.id(),UUID.randomUUID(),REASON).resolution()).isEqualTo("CANCELLED");
        assertThatThrownBy(()->cases.decide(cancelled.id(),0,UUID.randomUUID(),"CLOSE_UNRESOLVED",REASON,"DECIDE")).isInstanceOf(IllegalStateException.class);
        var charged=fixtures.fixture(true);
        shop.update("UPDATE shop_orders SET status='CANCELLED',cancelled_at=now() WHERE id=?",charged.id());
        shop.update("INSERT INTO shop_payment_cases(order_id,financial_outcome,workflow_status) VALUES (?,'CANCELLED','RESOLVED')",charged.id());
        assertThat(cases.plan(charged.id(),UUID.randomUUID(),REASON).resolution()).isEqualTo("CONFLICT");
        assertThat(shop.queryForObject("SELECT workflow_status FROM shop_payment_cases WHERE order_id=?",String.class,charged.id())).isEqualTo("ESCALATED");
    }
    @Test void onlyDurableDefinitiveRefusalCanUnblockAnOldOrder()throws Exception{
        var row=fixtures.fixture(false);
        bank.update("INSERT INTO payment_purchases(merchant_id,order_id,amount,currency) VALUES ('synthetic-shop',?,10,'PLN')",row.number());
        bank.update("INSERT INTO payment_purchase_attempts(idempotency_key,merchant_id,order_id,kind,state,merchant_account_number,charged_amount,charged_currency) VALUES (?,'synthetic-shop',?,'BANK','DECLINED',?,10,'PLN')",row.key(),row.number(),ACCOUNT);
        bank.update("""
            INSERT INTO payment_authorizations(id,shop_order_id,shop_customer_id,merchant_account_number,amount,currency,title,
            payment_idempotency_key,state,return_url,status,created_at,expires_at)
            VALUES (?,?,'synthetic-customer',?,10,'PLN','Synthetic pending',?,?,'http://localhost:8081/payment/callback','PENDING',now(),now())
            """,UUID.randomUUID().toString(),row.number(),ACCOUNT,row.key(),UUID.randomUUID().toString());
        assertThat(cases.plan(row.id(),UUID.randomUUID(),REASON).resolution()).isEqualTo("UNKNOWN");
        bank.update("DELETE FROM payment_authorizations WHERE shop_order_id=?",row.number());
        var balances=bank.queryForList("SELECT id,balance FROM accounts ORDER BY id");
        var plan=cases.plan(row.id(),UUID.randomUUID(),REASON);assertThat(plan.resolution()).isEqualTo("DECLINED");
        var request=UUID.randomUUID();cases.apply(row.id(),plan.planDigest(),request,REASON,true,true,"RECONCILE");
        assertThat(cases.apply(row.id(),plan.planDigest(),request,REASON,true,true,"RECONCILE").replayed()).isTrue();
        assertThat(shop.queryForObject("SELECT state FROM shop_payment_attempts",String.class)).isEqualTo("DECLINED");
        assertThat(shop.queryForObject("SELECT financial_outcome FROM shop_payment_cases",String.class)).isEqualTo("DECLINED");
        assertThat(bank.queryForList("SELECT id,balance FROM accounts ORDER BY id")).isEqualTo(balances);
    }
    @Test void confirmedSuccessIsAppliedOnceAndStalePlansAreRefused()throws Exception{
        var row=fixtures.fixture(true);var plan=cases.plan(row.id(),UUID.randomUUID(),REASON);
        assertThat(plan.resolution()).isEqualTo("PAID");
        var request=UUID.randomUUID();cases.apply(row.id(),plan.planDigest(),request,REASON,true,true,"RECONCILE");
        assertThat(cases.apply(row.id(),plan.planDigest(),request,REASON,true,true,"RECONCILE").replayed()).isTrue();
        assertThat(shop.queryForObject("SELECT count(*) FROM shop_payment_attempts",Integer.class)).isEqualTo(1);
        assertThat(bank.queryForObject("SELECT count(*) FROM bank_transactions",Integer.class)).isEqualTo(1);
        var another=fixtures.fixture(true);var stale=cases.plan(another.id(),UUID.randomUUID(),REASON);
        shop.update("UPDATE shop_orders SET total=11 WHERE id=?",another.id());
        assertThatThrownBy(()->cases.apply(another.id(),stale.planDigest(),UUID.randomUUID(),REASON,true,true,"RECONCILE")).isInstanceOf(IllegalStateException.class);
    }
    @Test void fullMatchedRefundIsSeparateFromOriginalSuccessfulPayment()throws Exception{
        var row=fixtures.fixture(true);
        bank.update("""
            INSERT INTO bank_transactions(reference_number,idempotency_key,source_account_id,destination_account_id,recipient_name_snapshot,
            amount,currency,title,type,status,created_at,completed_at,payment_order_id,refund_original_id)
            SELECT 'case-refund','case-refund',destination_account_id,source_account_id,'Synthetic refund',amount,currency,title,
            type,status,now(),now(),'refund:'||payment_order_id,id FROM bank_transactions WHERE idempotency_key=?
            """,row.key());
        shop.update("INSERT INTO shop_refunds(order_id,order_number,idempotency_key,amount,currency,status,reason,bank_reference) VALUES (?,?,?,10,'PLN','COMPLETED','Synthetic refund','case-refund')",row.id(),row.number(),UUID.randomUUID().toString());
        var balances=bank.queryForList("SELECT id,balance FROM accounts ORDER BY id");
        var plan=cases.plan(row.id(),UUID.randomUUID(),REASON);assertThat(plan.resolution()).isEqualTo("REFUNDED");
        cases.apply(row.id(),plan.planDigest(),UUID.randomUUID(),REASON,true,true,"RECONCILE");
        assertThat(shop.queryForObject("SELECT status FROM shop_orders",String.class)).isEqualTo("PAID");
        assertThat(shop.queryForObject("SELECT financial_outcome FROM shop_payment_cases",String.class)).isEqualTo("REFUNDED");
        assertThat(shop.queryForObject("SELECT count(*) FROM shop_refunds",Integer.class)).isEqualTo(1);
        assertThat(bank.queryForList("SELECT id,balance FROM accounts ORDER BY id")).isEqualTo(balances);
    }
    @Test void failedRefundOrDuplicateChargeNeedsReviewNotAutomaticFinancialRewrite()throws Exception{
        var row=fixtures.fixture(true);
        shop.update("INSERT INTO shop_refunds(order_id,order_number,idempotency_key,amount,currency,status,reason) VALUES (?,?,?,10,'PLN','FAILED','Synthetic refund')",row.id(),row.number(),UUID.randomUUID().toString());
        var plan=cases.plan(row.id(),UUID.randomUUID(),REASON);assertThat(plan.resolution()).isEqualTo("REFUND_REVIEW");
        assertThatThrownBy(()->cases.apply(row.id(),plan.planDigest(),UUID.randomUUID(),REASON,true,true,"RECONCILE")).isInstanceOf(IllegalStateException.class);
        var duplicate=fixtures.fixture(true);
        bank.update("""
            INSERT INTO bank_transactions(reference_number,idempotency_key,source_account_id,destination_account_id,recipient_name_snapshot,
            amount,currency,title,type,status,created_at,completed_at,payment_order_id)
            SELECT 'case-duplicate','case-duplicate',source_account_id,destination_account_id,recipient_name_snapshot,amount,currency,title,type,status,now(),now(),payment_order_id
            FROM bank_transactions WHERE idempotency_key=?
            """,duplicate.key());
        assertThat(cases.plan(duplicate.id(),UUID.randomUUID(),REASON).resolution()).isEqualTo("CONFLICT");
    }
    @Test void aRefusedAuthorizationWithAnExistingChargeIsNeverImportedAsDeclined()throws Exception{
        var row=fixtures.fixture(true);bank.update("UPDATE payment_authorizations SET status='FAILED' WHERE shop_order_id=?",row.number());
        assertThat(cases.plan(row.id(),UUID.randomUUID(),REASON).resolution()).isEqualTo("UNKNOWN");
    }
    @Test void financialCommitBeforeAuditFailureCanResumeOnlyTheApprovedOperation()throws Exception{
        var row=fixtures.fixture(true);var check=UUID.randomUUID();var plan=cases.plan(row.id(),check,REASON);var request=UUID.randomUUID();
        shop.execute("CREATE FUNCTION fail_case_resolution() RETURNS trigger LANGUAGE plpgsql AS 'BEGIN RAISE EXCEPTION ''synthetic audit failure''; END'");
        shop.execute("CREATE TRIGGER fail_case_resolution BEFORE UPDATE ON shop_payment_cases FOR EACH ROW WHEN (NEW.workflow_status=''RESOLVED'') EXECUTE FUNCTION fail_case_resolution()".replace("''RESOLVED''","'RESOLVED'"));
        try{
            assertThatThrownBy(()->cases.apply(row.id(),plan.planDigest(),request,REASON,true,true,"RECONCILE")).isInstanceOf(Exception.class);
            assertThat(shop.queryForObject("SELECT status FROM shop_orders WHERE id=?",String.class,row.id())).isEqualTo("PAID");
            assertThat(shop.queryForObject("SELECT status FROM shop_payment_case_plans",String.class)).isEqualTo("APPLYING");
            assertThatThrownBy(()->cases.decide(row.id(),0,UUID.randomUUID(),"ALLOW_NEW_PURCHASE",REASON,"DECIDE")).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(()->cases.plan(row.id(),UUID.randomUUID(),REASON)).isInstanceOf(IllegalStateException.class);
        }finally{shop.execute("DROP TRIGGER fail_case_resolution ON shop_payment_cases");shop.execute("DROP FUNCTION fail_case_resolution()");}
        assertThat(cases.apply(row.id(),plan.planDigest(),request,REASON,true,true,"RECONCILE").applied()).isTrue();
        assertThat(cases.plan(row.id(),check,REASON).replayed()).isTrue();
        assertThat(bank.queryForObject("SELECT count(*) FROM bank_transactions",Integer.class)).isEqualTo(1);
    }
    @Test void packagedControlCenterCommandChecksEvidenceAndAuditsRiskWithoutCharges()throws Exception{
        var row=fixtures.fixture(false);
        var plan=fixtures.launch("--operation=payment-case-plan","--orderId="+row.id(),"--requestId="+UUID.randomUUID(),"--reason="+REASON);
        assertThat(plan).contains("\"resolution\":\"UNKNOWN\"");
        var decision=fixtures.launch("--operation=payment-case-decision","--orderId="+row.id(),"--requestId="+UUID.randomUUID(),"--reason="+REASON,
                "--expectedVersion=0","--decision=ALLOW_NEW_PURCHASE","--confirmation=DECIDE");
        assertThat(decision).contains("ALLOW_NEW_PURCHASE","\"financialApplied\":false");
        assertThat(shop.queryForObject("SELECT financial_outcome FROM shop_payment_cases",String.class)).isEqualTo("UNKNOWN");
        assertThat(bank.queryForObject("SELECT count(*) FROM bank_transactions",Integer.class)).isZero();
    }
}
