package org.demo.bankdemo.maintenance;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import javax.sql.DataSource;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.util.*;
import java.util.function.Function;

/** Evidence-only financial resolution; workflow/risk decisions never invent a financial outcome.
 * Original bank ledger entries and balances are never changed by this administration service.
 */
public final class PaymentCaseAdministration {
    private final DataSource bankSource,shopSource;
    private final JdbcTemplate bank,shop;
    private final HistoricalPaymentMigration migration;
    private final Map<String,String> env;
    private final ObjectMapper json=new ObjectMapper().enable(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    private static final Set<String> DECISIONS=Set.of("ESCALATE","CLOSE_UNRESOLVED","ALLOW_NEW_PURCHASE","REVOKE_NEW_PURCHASE");
    private static final Set<String> FINANCIAL=Set.of("PAID","DECLINED","REFUNDED");
    public record Proof(HistoricalPaymentMigration.Entry entry,String resolution,List<Map<String,Object>> facts,
                        long version,String bankBackup,String shopBackup){}
    public record Result(long orderId,String resolution,String planDigest,
                         @com.fasterxml.jackson.annotation.JsonProperty("financialApplied") boolean applied,
                         boolean replayed,long version){}
    public PaymentCaseAdministration(DataSource b,DataSource s,Map<String,String> env){
        this(b,s,env,id->HistoricalPaymentMigration.stripeSession(id,env.getOrDefault("SHOP_PAYMENT_API_KEY","")));
    }
    public PaymentCaseAdministration(DataSource b,DataSource s,Map<String,String> env,Function<String,Map<String,Object>> stripe){
        bankSource=b;shopSource=s;bank=HistoricalPaymentMigration.jdbc(b);shop=HistoricalPaymentMigration.jdbc(s);this.env=env;
        migration=new HistoricalPaymentMigration(b,s,env,stripe);
    }
    private HistoricalPaymentMigration.Order order(long id){
        var rows=migration.orders(shop,id);if(rows.size()!=1)throw new IllegalArgumentException("Order not found.");return rows.getFirst();
    }
    private long version(JdbcTemplate s,long order){
        var found=s.queryForList("SELECT version FROM shop_payment_cases WHERE order_id=?",Long.class,order);
        return found.isEmpty()?0:found.getFirst();
    }
    Proof inspect(long id,long version){
        var row=order(id);var facts=new ArrayList<Map<String,Object>>();
        var refunds=shop.queryForList("SELECT status,count(*) AS count FROM shop_refunds WHERE order_id=? GROUP BY status",id);
        refunds.forEach(r->facts.add(Map.of("source","SHOP_REFUNDS","status",r.get("status"),"count",r.get("count"))));
        for(var rowStatus:bank.queryForList("SELECT status,count(*) AS count FROM payment_authorizations WHERE shop_order_id=? GROUP BY status ORDER BY status",row.number()))
            facts.add(Map.of("source","LEGACY_BANK_AUTHORIZATION_NOT_FINAL_PROOF","status",rowStatus.get("status"),"count",rowStatus.get("count")));
        for(var rowStatus:bank.queryForList("SELECT status,count(*) AS count FROM card_transactions WHERE order_id=? AND channel='ONLINE' GROUP BY status ORDER BY status",row.number()))
            facts.add(Map.of("source","LEGACY_CARD_RECORD_NOT_FINAL_PROOF","status",rowStatus.get("status"),"count",rowStatus.get("count")));
        var originals=bank.queryForObject("SELECT count(*) FROM bank_transactions WHERE payment_order_id=? AND type='PAYMENT' AND refund_original_id IS NULL",Long.class,row.number());
        facts.add(Map.of("source","BANK_LEDGER","originalPayments",originals));
        if(originals>1 || ("CANCELLED".equals(row.status()) && originals>0))return proof(row,null,"CONFLICT",facts,version);
        try {
            var paid=migration.evidence(bank,row,true);
            facts.add(Map.of("source",paid.provider(),"result","CONFIRMED_ORIGINAL_SUCCESS","reference",paid.reference()));
            if("CANCELLED".equals(row.status()))return proof(row,paid,"CONFLICT",facts,version);
            var returned=bank.queryForList("""
                SELECT r.reference_number,r.amount,r.currency,r.status,r.source_account_id,r.destination_account_id,
                o.source_account_id AS original_source,o.destination_account_id AS original_destination
                FROM bank_transactions r JOIN bank_transactions o ON o.id=?
                WHERE r.refund_original_id=o.id OR r.payment_order_id=?
                """,paid.ledger(),"refund:"+row.number());
            if(!returned.isEmpty() || !refunds.isEmpty()) {
                // First implementation accepts only the application's single, full, matched bank refund.
                if(!"STRIPE".equals(paid.provider()) && returned.size()==1){
                    var r=returned.getFirst();
                    var matching=shop.queryForObject("""
                        SELECT count(*) FROM shop_refunds WHERE order_id=? AND status='COMPLETED'
                        AND bank_reference=? AND amount=? AND currency=?
                        """,Integer.class,id,r.get("reference_number"),row.amount(),row.currency());
                    if("COMPLETED".equals(r.get("status")) && ((BigDecimal)r.get("amount")).compareTo(row.amount())==0
                            && row.currency().equals(r.get("currency").toString().strip())
                            && Objects.equals(r.get("source_account_id"),r.get("original_destination"))
                            && Objects.equals(r.get("destination_account_id"),r.get("original_source"))
                            && matching==1 && shop.queryForObject("SELECT count(*) FROM shop_refunds WHERE order_id=?",Integer.class,id)==1){
                        facts.add(Map.of("source","MATCHED_FULL_REFUND","reference",r.get("reference_number")));
                        return proof(row,paid,"REFUNDED",facts,version);
                    }
                }
                return proof(row,paid,"REFUND_REVIEW",facts,version);
            }
            return proof(row,paid,"PAID",facts,version);
        }catch(HistoricalPaymentMigration.Unconfirmed unavailable){
            if("CANCELLED".equals(row.status()) && refunds.isEmpty() && Boolean.TRUE.equals(shop.queryForObject("""
                    SELECT EXISTS(SELECT 1 FROM shop_payment_cases WHERE order_id=? AND financial_outcome='CANCELLED')
                    AND NOT EXISTS(SELECT 1 FROM shop_payment_attempts WHERE order_id=? AND state NOT IN ('DECLINED','CANCELLED'))
                    """,Boolean.class,id,id))){
                facts.add(Map.of("source","CONFIRMED_PURCHASE_CANCELLATION","result","CANCELLED"));
                return proof(row,null,"CANCELLED",facts,version);
            }
            if("STRIPE".equalsIgnoreCase(row.provider()))facts.add(Map.of("source","STRIPE","result","NO_COMPLETE_FINANCIAL_PROOF","note","An expired session or one failed attempt does not prove the whole historical purchase was never charged."));
            // Only a durable bank refusal is accepted, never an old local FAILED, 404 or expired Checkout.
            if(originals==0 && refunds.isEmpty() && row.key()!=null && row.key().matches("[A-Za-z0-9_-]{1,64}") && row.merchant()==null && "PLN".equals(row.currency())){
                var refusals=bank.queryForList("""
                    SELECT a.kind,a.charged_amount,a.charged_currency FROM payment_purchase_attempts a
                    JOIN payment_purchases p ON p.merchant_id=a.merchant_id AND p.order_id=a.order_id
                    WHERE a.idempotency_key=? AND a.merchant_id=? AND a.order_id=? AND a.state='DECLINED'
                    AND a.merchant_account_number=? AND p.amount=? AND p.currency=?
                    AND NOT EXISTS(SELECT 1 FROM payment_purchase_attempts other WHERE other.merchant_id=a.merchant_id
                    AND other.order_id=a.order_id AND other.state IN ('PENDING','SUCCEEDED'))
                    AND NOT EXISTS(SELECT 1 FROM payment_authorizations auth WHERE auth.shop_order_id=a.order_id AND auth.status IN ('PENDING','COMPLETED','REDEEMED'))
                    AND NOT EXISTS(SELECT 1 FROM card_transactions card WHERE card.order_id=a.order_id AND card.channel='ONLINE' AND card.status='COMPLETED')
                    """,row.key(),env.get("SHOP_MERCHANT_ID"),row.number(),row.account(),row.amount(),row.currency());
                if(refusals.size()==1 && Objects.equals(row.account(),env.get("SHOP_MERCHANT_ACCOUNT_NUMBER"))){
                    var refusal=refusals.getFirst();var kind=refusal.get("kind").toString();
                    if(row.provider()==null || row.provider().equalsIgnoreCase(kind)){
                        var charged=(BigDecimal)refusal.get("charged_amount");var currency=(String)refusal.get("charged_currency");
                        // Legacy refusal without a charged snapshot does not prove a foreign-currency request.
                        if(charged!=null && charged.signum()>0 && currency!=null){
                            facts.add(Map.of("source","BANK_DURABLE_ATTEMPT","result","DECLINED","provider",kind));
                            var evidence=new HistoricalPaymentMigration.Evidence(kind,"",0,null,null,charged,currency);
                            return proof(row,evidence,"DECLINED",facts,version);
                        }
                    }
                }
            }
            return proof(row,null,refunds.isEmpty()?"UNKNOWN":"REFUND_REVIEW",facts,version);
        }
    }
    private Proof proof(HistoricalPaymentMigration.Order row,HistoricalPaymentMigration.Evidence e,String state,List<Map<String,Object>> facts,long v){
        return new Proof(new HistoricalPaymentMigration.Entry(row,e),state,List.copyOf(facts),v,
                env.get("MIGRATION_BANK_BACKUP_SHA256"),env.get("MIGRATION_SHOP_BACKUP_SHA256"));
    }
    public Result plan(long id,UUID request,String reason)throws Exception{
        reason(reason);
        return locked(id,(control,c)->{
            var old=c.queryForList("SELECT expected_version FROM shop_payment_case_events WHERE request_id=?",Long.class,request);
            if(!old.isEmpty())return replay(c,request,id,"CHECK",reason,old.getFirst(),null);
            refuseInFlight(c,id);
            var snapshot=inspect(id,version(c,id));var digest=digest(snapshot);
            control.setAutoCommit(false);
            ensure(c,id);
            if(version(c,id)!=snapshot.version())throw new IllegalStateException("Case changed during evidence check.");
            c.update("INSERT INTO shop_payment_case_plans(digest,order_id,resolution,manifest) VALUES (?,?,?,?::jsonb) ON CONFLICT DO NOTHING",digest,id,snapshot.resolution(),json.writeValueAsString(snapshot));
            c.update("""
                UPDATE shop_payment_cases SET checked_at=CURRENT_TIMESTAMP,financial_outcome=?,
                workflow_status=CASE WHEN ? IN ('CONFLICT','REFUND_REVIEW') THEN 'ESCALATED' ELSE workflow_status END,
                updated_at=CURRENT_TIMESTAMP WHERE order_id=?
                """,snapshot.resolution(),snapshot.resolution(),id);
            event(c,request,id,"CHECK",reason,snapshot.version(),snapshot.version(),digest);
            control.commit();
            return new Result(id,snapshot.resolution(),digest,false,false,snapshot.version());
        });
    }
    public Result decide(long id,long expected,UUID request,String decision,String reason,String confirmation)throws Exception{
        reason(reason);if(!DECISIONS.contains(decision)||!"DECIDE".equals(confirmation))throw new IllegalArgumentException("Explicit workflow/risk decision required.");
        return locked(id,(control,c)->{
            var old=replay(c,request,id,decision,reason,expected,null);if(old!=null)return old;
            refuseInFlight(c,id);
            control.setAutoCommit(false);
            // Payment completion locks the order before updating the case. Use the same lock order,
            // so a concurrent successful payment cannot be archived as UNKNOWN after this check.
            c.queryForObject("SELECT id FROM shop_orders WHERE id=? FOR UPDATE",Long.class,id);
            ensure(c,id);
            if(version(c,id)!=expected)throw new IllegalStateException("Case version changed; reload before deciding.");
            if(decision.equals("CLOSE_UNRESOLVED") && !Boolean.TRUE.equals(c.queryForObject("""
                SELECT c.financial_outcome='UNKNOWN' AND o.status NOT IN ('PAID','CANCELLED')
                FROM shop_payment_cases c JOIN shop_orders o ON o.id=c.order_id WHERE o.id=?
                """,Boolean.class,id)))throw new IllegalStateException("Only an unresolved financial outcome can be closed without resolution.");
            if(decision.equals("ALLOW_NEW_PURCHASE") && !Boolean.TRUE.equals(c.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM shop_orders o WHERE id=? AND
                (EXISTS(SELECT 1 FROM shop_payment_attempts a WHERE a.order_id=o.id AND a.state IN ('CREATED','PROCESSING','AWAITING_AUTHORIZATION','UNKNOWN'))
                OR (o.merchant_id IS NULL AND o.payment_idempotency_key IS NOT NULL AND o.status<>'PAID')))
                """,Boolean.class,id)))throw new IllegalStateException("Order is not unresolved; no risk exception is needed.");
            switch(decision){
                case "ESCALATE" -> c.update("UPDATE shop_payment_cases SET workflow_status='ESCALATED' WHERE order_id=?",id);
                case "CLOSE_UNRESOLVED" -> c.update("UPDATE shop_payment_cases SET workflow_status='CLOSED_UNRESOLVED' WHERE order_id=?",id);
                case "ALLOW_NEW_PURCHASE" -> c.update("UPDATE shop_payment_cases SET allow_new_purchase_at=clock_timestamp() WHERE order_id=?",id);
                case "REVOKE_NEW_PURCHASE" -> c.update("UPDATE shop_payment_cases SET allow_new_purchase_at=NULL WHERE order_id=?",id);
            }
            c.update("UPDATE shop_payment_cases SET version=version+1,updated_at=CURRENT_TIMESTAMP WHERE order_id=?",id);
            event(c,request,id,decision,reason,expected,expected+1,null);control.commit();
            return new Result(id,decision,null,false,false,expected+1);
        });
    }
    public Result apply(long id,String digest,UUID request,String reason,boolean backups,boolean paused,String confirmation)throws Exception{
        reason(reason);if(!backups||!paused||!"RECONCILE".equals(confirmation)||digest==null||!digest.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("Backups, stopped writers and RECONCILE are required.");
        return locked(id,(control,c)->{
            var plans=c.queryForList("SELECT manifest::text,status,apply_request_id,apply_reason,expires_at>CURRENT_TIMESTAMP AS valid FROM shop_payment_case_plans WHERE digest=? AND order_id=?",digest,id);
            if(plans.size()!=1)throw new IllegalStateException("Reviewed plan missing.");var stored=plans.getFirst();
            var proof=json.readValue((String)stored.get("manifest"),Proof.class);
            if(!digest.equals(digest(proof)) || !FINANCIAL.contains(proof.resolution()))throw new IllegalStateException("No definitive financial evidence.");
            var old=replay(c,request,id,"APPLY_"+proof.resolution(),reason,proof.version(),digest);if(old!=null)return old;
            if(!Objects.equals(proof.bankBackup(),env.get("MIGRATION_BANK_BACKUP_SHA256")) || !Objects.equals(proof.shopBackup(),env.get("MIGRATION_SHOP_BACKUP_SHA256")))throw new IllegalStateException("Backup scope changed.");
            if("READY".equals(stored.get("status"))){
                if(!Boolean.TRUE.equals(stored.get("valid")) || version(c,id)!=proof.version()
                        || !digest.equals(digest(inspect(id,proof.version()))))throw new IllegalStateException("Evidence/plan changed; review again.");
                // Existing live attempts use their normal reconciler, not a historical rewrite.
                if(proof.entry().order().merchant()!=null && !(Set.of("PAID","REFUNDED").contains(proof.resolution())
                        && "PAID".equals(proof.entry().order().status())
                        && shop.queryForObject("SELECT count(*) FROM shop_payment_attempts WHERE order_id=? AND state='SUCCEEDED' AND financial_reference=?",Integer.class,id,proof.entry().evidence().reference())==1))
                    throw new IllegalStateException("Use live reconciliation for an assigned unresolved order; history writer refuses it.");
                c.update("UPDATE shop_payment_case_plans SET status='APPLYING',apply_request_id=?,apply_reason=? WHERE digest=?",request,reason,digest);
            }else if(!request.equals(stored.get("apply_request_id")) || !reason.equals(stored.get("apply_reason")))throw new IllegalStateException("Resume the original approved request, not a new decision.");
            if(version(c,id)!=proof.version())throw new IllegalStateException("Workflow decision changed after approval.");
            if(proof.entry().order().merchant()==null){
                if("DECLINED".equals(proof.resolution()))writeDecline(proof);
                else migration.migrate(proof.entry(),"REFUNDED".equals(proof.resolution()));
            }
            control.setAutoCommit(false);
            c.update("UPDATE shop_payment_cases SET financial_outcome=?,workflow_status='RESOLVED',allow_new_purchase_at=NULL,version=version+1,updated_at=CURRENT_TIMESTAMP WHERE order_id=?",proof.resolution(),id);
            event(c,request,id,"APPLY_"+proof.resolution(),reason,proof.version(),proof.version()+1,digest);
            c.update("UPDATE shop_payment_case_plans SET status='COMPLETED' WHERE digest=?",digest);control.commit();
            return new Result(id,proof.resolution(),digest,true,false,proof.version()+1);
        });
    }
    private void writeDecline(Proof proof)throws Exception{
        var row=proof.entry().order();var evidence=proof.entry().evidence();var attempt=HistoricalPaymentMigration.attemptId(row);
        try(var bc=bankSource.getConnection();var sc=shopSource.getConnection()){
            bc.setAutoCommit(false);sc.setAutoCommit(false);var b=HistoricalPaymentMigration.jdbc(new SingleConnectionDataSource(bc,true));var s=HistoricalPaymentMigration.jdbc(new SingleConnectionDataSource(sc,true));
            try{
                b.execute("SET LOCAL lock_timeout='3s'");s.execute("SET LOCAL lock_timeout='3s'");
                b.execute("LOCK TABLE bank_transactions,payment_purchases,payment_purchase_attempts IN SHARE ROW EXCLUSIVE MODE");
                s.execute("LOCK TABLE shop_orders,shop_payment_attempts,shop_refunds IN SHARE ROW EXCLUSIVE MODE");
                if(Boolean.TRUE.equals(s.queryForObject("SELECT EXISTS(SELECT 1 FROM shop_payment_attempts a JOIN shop_orders o ON o.id=a.order_id WHERE a.id=? AND a.order_id=? AND a.state='DECLINED' AND o.merchant_id=? AND o.status='FAILED' AND o.historical_imported_at IS NOT NULL)",Boolean.class,attempt,row.id(),env.get("SHOP_MERCHANT_ID")))){bc.rollback();sc.rollback();return;}
                if(!digest(proof).equals(digest(inspect(row.id(),proof.version()))) || !migration.orders(s,row.id()).getFirst().equals(row))throw new IllegalStateException("Refusal evidence changed.");
                if(s.queryForObject("SELECT count(*) FROM shop_payment_attempts WHERE order_id=?",Integer.class,row.id())!=0)throw new IllegalStateException("Existing live attempt requires normal reconciliation.");
                s.update("INSERT INTO shop_payment_attempts(id,order_id,merchant_id,provider,idempotency_key,state,correlation_state,charged_amount,charged_currency) VALUES (?,?,?,?,?,'DECLINED',?,?,?)",attempt,row.id(),env.get("SHOP_MERCHANT_ID"),evidence.provider(),row.key(),row.correlation()==null?attempt:row.correlation(),evidence.charged(),evidence.currency());
                s.update("UPDATE shop_orders SET merchant_id=?,status='FAILED',historical_imported_at=CURRENT_TIMESTAMP,updated_at=CURRENT_TIMESTAMP WHERE id=? AND merchant_id IS NULL",env.get("SHOP_MERCHANT_ID"),row.id());
                sc.commit();bc.rollback(); // No bank write and no charge/refund request.
            }catch(Exception failure){sc.rollback();bc.rollback();throw failure;}
        }
    }
    private void ensure(JdbcTemplate c,long id){order(id);c.update("INSERT INTO shop_payment_cases(order_id) VALUES (?) ON CONFLICT DO NOTHING",id);}
    private void refuseInFlight(JdbcTemplate c,long id){
        if(c.queryForObject("SELECT count(*) FROM shop_payment_case_plans WHERE order_id=? AND status='APPLYING'",Integer.class,id)!=0)
            throw new IllegalStateException("Resume the existing approved financial operation before another check or decision.");
    }
    private Result replay(JdbcTemplate c,UUID request,long id,String action,String reason,long expected,String digest){
        var rows=c.queryForList("SELECT * FROM shop_payment_case_events WHERE request_id=?",request);if(rows.isEmpty())return null;var old=rows.getFirst();
        if(((Number)old.get("order_id")).longValue()!=id || !action.equals(old.get("action")) || !reason.equals(old.get("reason"))
                || ((Number)old.get("expected_version")).longValue()!=expected || (digest!=null&&!digest.equals(old.get("evidence_digest"))))throw new IllegalStateException("Request ID belongs to a different decision.");
        return new Result(id,action,(String)old.get("evidence_digest"),action.startsWith("APPLY_"),true,((Number)old.get("resulting_version")).longValue());
    }
    private void event(JdbcTemplate c,UUID request,long id,String action,String reason,long expected,long result,String digest){
        c.update("INSERT INTO shop_payment_case_events(request_id,order_id,action,operator_id,reason,expected_version,resulting_version,evidence_digest) VALUES (?,?,?,'local-cc-dev',?,?,?,?)",request,id,action,reason,expected,result,digest);
    }
    static void reason(String reason){if(reason==null||reason.strip().length()<8||reason.length()>1000||reason.chars().anyMatch(c->c<32||c==127))throw new IllegalArgumentException("A reason of 8–1000 plain characters is required.");}
    String digest(Proof proof){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(proof)));}catch(Exception invalid){throw new IllegalStateException("Cannot hash evidence.");}}
    interface Work{Result run(Connection connection,JdbcTemplate jdbc)throws Exception;}
    private Result locked(long id,Work work)throws Exception{
        if(id<1)throw new IllegalArgumentException("Invalid order ID.");
        try(var connection=shopSource.getConnection()){
            var c=HistoricalPaymentMigration.jdbc(new SingleConnectionDataSource(connection,true));
            var customer=order(id).customer();
            boolean customerLocked=false,caseLocked=false;
            try{
                c.queryForObject("SELECT pg_advisory_lock(hashtextextended(?,0)) IS NULL",Boolean.class,"checkout-customer:"+customer);customerLocked=true;
                c.queryForObject("SELECT pg_advisory_lock(hashtextextended(?,0)) IS NULL",Boolean.class,"payment-case:"+id);caseLocked=true;
                return work.run(connection,c);
            }finally{
                if(!connection.getAutoCommit()){connection.rollback();connection.setAutoCommit(true);}
                if(caseLocked)c.queryForObject("SELECT pg_advisory_unlock(hashtextextended(?,0))",Boolean.class,"payment-case:"+id);
                if(customerLocked)c.queryForObject("SELECT pg_advisory_unlock(hashtextextended(?,0))",Boolean.class,"checkout-customer:"+customer);
            }
        }
    }
}
