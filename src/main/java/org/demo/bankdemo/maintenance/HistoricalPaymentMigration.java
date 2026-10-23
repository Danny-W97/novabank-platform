package org.demo.bankdemo.maintenance;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import javax.sql.DataSource;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.time.Duration;
import java.util.*;
import java.util.function.Function;

/** Explicit, bounded success-only backfill. No charges, refunds, account updates or inferred declines.
 * Bank identity commits before shop identity. A crash leaves the original shop block in place;
 * the immutable approved manifest in the shop journal supports a safe replay.
 */
public final class HistoricalPaymentMigration {
    private final DataSource bankSource,shopSource;
    private final JdbcTemplate bank,shop;
    private final String merchant,account,bankBackup,shopBackup;
    private final Function<String,Map<String,Object>> stripe;
    private final ObjectMapper json=new ObjectMapper();

    public HistoricalPaymentMigration(DataSource bankSource,DataSource shopSource,Map<String,String> env) {
        this(bankSource,shopSource,env,id -> stripeSession(id,env.getOrDefault("SHOP_PAYMENT_API_KEY","")));
    }
    public HistoricalPaymentMigration(DataSource b,DataSource s,Map<String,String> env,Function<String,Map<String,Object>> stripe) {
        bankSource=b;shopSource=s;bank=jdbc(b);shop=jdbc(s);this.stripe=stripe;
        merchant=required(env,"SHOP_MERCHANT_ID","[A-Za-z0-9_-]{1,64}");
        account=required(env,"SHOP_MERCHANT_ACCOUNT_NUMBER","[0-9]{26}");
        var mappings=new org.demo.bankdemo.config.PaymentApiProperties();
        mappings.setKeyMerchants(env.getOrDefault("BANK_API_KEY_MERCHANTS",""));
        mappings.setMerchants(env.getOrDefault("BANK_PAYMENT_MERCHANTS",""));
        var identity=mappings.merchantForKey(env.getOrDefault("BANK_API_KEY_ID","legacy"));
        if(!merchant.equals(identity.id()))throw new IllegalArgumentException("Shop/bank merchant identity mismatch.");
        identity.requireAccount(account);
        bankBackup=required(env,"MIGRATION_BANK_BACKUP_SHA256","[a-f0-9]{64}");
        shopBackup=required(env,"MIGRATION_SHOP_BACKUP_SHA256","[a-f0-9]{64}");
    }
    static JdbcTemplate jdbc(DataSource source){var j=new JdbcTemplate(source);j.setQueryTimeout(10);return j;}
    static String required(Map<String,String> env,String key,String pattern){var v=env.get(key);if(v==null||!v.matches(pattern))throw new IllegalArgumentException("Migration configuration/backup evidence missing: "+key);return v;}
    public record Order(long id,String number,String key,String customer,String account,BigDecimal amount,String currency,
                        String provider,String reference,String external,String authorization,String correlation,String updated,String merchant,String status){}
    public record Evidence(String provider,String reference,long ledger,String authorization,String sourceAccount,BigDecimal charged,String currency){}
    public record Entry(Order order,Evidence evidence){}
    public record Manifest(String merchant,String account,String bankBackup,String shopBackup,List<Entry> entries){}
    public record Plan(String planDigest,int eligible,List<Map<String,Object>> orders,List<Map<String,Object>> blocked,
                       String bankSnapshotSha256,String shopSnapshotSha256,boolean applied){}
    public record Result(int completed,boolean replayed,boolean applied,String planDigest){}

    List<Order> orders(JdbcTemplate j,Long id){
        return j.query("""
                SELECT id,order_number,payment_idempotency_key,customer_id,merchant_account_number,total,currency,
                payment_provider,bank_reference,external_payment_id,bank_authorization_id,payment_state,updated_at::text,merchant_id,status
                FROM shop_orders WHERE
                """+(id==null ? "merchant_id IS NULL ORDER BY id LIMIT 1001" : "id=?"),
                (rs,n)->new Order(rs.getLong(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getString(5).strip(),rs.getBigDecimal(6),rs.getString(7).strip(),
                        rs.getString(8),rs.getString(9),rs.getString(10),rs.getString(11),rs.getString(12),rs.getString(13),rs.getString(14),rs.getString(15)),
                id==null?new Object[0]:new Object[]{id});
    }
    private Manifest inspect(List<Map<String,Object>> blocked) {
        var rows=orders(shop,null);if(rows.size()>1000)throw new IllegalStateException("Migration exceeds the bounded 1000-row review; split the approved scope first.");
        var entries=new ArrayList<Entry>();
        for(var row:rows) {
            try {entries.add(new Entry(row,evidence(bank,row)));}
            catch(Unconfirmed ignored){blocked.add(Map.of("orderId",row.id(),"reason","NO_UNAMBIGUOUS_SUCCESS_EVIDENCE"));}
        }
        return new Manifest(merchant,account,bankBackup,shopBackup,List.copyOf(entries));
    }
    public Plan plan() {
        if(!Boolean.TRUE.equals(shop.queryForObject("SELECT to_regclass('public.historical_payment_migration_runs') IS NOT NULL",Boolean.class)))
            throw new IllegalStateException("Shop schema V14 is required before migration review.");
        var blocked=new ArrayList<Map<String,Object>>();var manifest=inspect(blocked);
        return new Plan(digest(manifest),manifest.entries().size(),manifest.entries().stream().map(e->Map.<String,Object>of(
                "orderId",e.order().id(),"provider",e.evidence().provider(),"resolution","CONFIRMED_PAID")).toList(),blocked,bankBackup,shopBackup,false);
    }
    Evidence evidence(JdbcTemplate b,Order order) {
        return evidence(b,order,false);
    }
    Evidence evidence(JdbcTemplate b,Order order,boolean reviewRefunds) {
        if(order.key()==null || !order.key().matches("[A-Za-z0-9_-]{1,64}") || !account.equals(order.account())
                || (order.merchant()!=null && !merchant.equals(order.merchant())) || !"PLN".equals(order.currency())
                || order.amount()==null || order.amount().signum()<=0)throw new Unconfirmed();
        // Refunded or disputed histories need their own evidence model, not a PAID-only backfill.
        if(!reviewRefunds && (shop.queryForObject("SELECT count(*) FROM shop_refunds WHERE order_id=?",Integer.class,order.id())!=0
                || b.queryForObject("SELECT count(*) FROM bank_transactions r LEFT JOIN bank_transactions original ON original.id=r.refund_original_id WHERE original.payment_order_id=? OR r.payment_order_id=?",Integer.class,order.number(),"refund:"+order.number())!=0))
            throw new Unconfirmed();
        var provider=order.provider()==null?"":order.provider().toUpperCase(Locale.ROOT);
        if("STRIPE".equals(provider)) {
            if(order.external()==null || !order.external().matches("cs_test_[A-Za-z0-9_]{1,240}"))throw new Unconfirmed();
            var session=stripe.apply(order.external());
            if(!stripeMatches(order,session))throw new Unconfirmed();
            return new Evidence("STRIPE",order.external(),0,null,null,order.amount(),order.currency());
        }
        // Exactly one original bank success, linked by BOTH purchase number and original key.
        var matches=b.query("""
                SELECT t.id,t.reference_number,t.idempotency_key,t.amount,t.currency,t.merchant_id,
                destination.account_number,source.account_number,t.status
                FROM bank_transactions t JOIN accounts destination ON destination.id=t.destination_account_id
                JOIN accounts source ON source.id=t.source_account_id
                WHERE t.payment_order_id=? AND t.type='PAYMENT' AND t.refund_original_id IS NULL
                """,(rs,n)->new Object[]{rs.getLong(1),rs.getString(2),rs.getString(3),rs.getBigDecimal(4),rs.getString(5).strip(),rs.getString(6),rs.getString(7).strip(),rs.getString(8).strip(),rs.getString(9)},order.number());
        if(matches.size()!=1)throw new Unconfirmed();var t=matches.getFirst();
        if(!order.key().equals(t[2]) || order.amount().compareTo((BigDecimal)t[3])!=0 || !order.currency().equals(t[4])
                || (t[5]!=null&&!merchant.equals(t[5])) || !account.equals(t[6]) || !"COMPLETED".equals(t[8])
                || (order.reference()!=null&&!order.reference().equals(t[1])))throw new Unconfirmed();
        var auth=b.query("""
                SELECT id,bank_reference,payment_idempotency_key,shop_customer_id,amount,currency,status,merchant_account_number,merchant_id,source_account_number
                FROM payment_authorizations WHERE shop_order_id=?
                """,(rs,n)->new Object[]{rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getBigDecimal(5),rs.getString(6),rs.getString(7),rs.getString(8),rs.getString(9),rs.getString(10)},order.number());
        var cards=b.query("""
                SELECT c.bank_reference,c.idempotency_key,c.amount_pln,c.amount,c.currency,c.status,c.merchant_account_number,a.account_number
                FROM card_transactions c JOIN demo_cards d ON d.id=c.card_id JOIN accounts a ON a.id=d.account_id
                WHERE c.order_id=? AND c.channel='ONLINE'
                """,(rs,n)->new Object[]{rs.getString(1),rs.getString(2),rs.getBigDecimal(3),rs.getBigDecimal(4),rs.getString(5),rs.getString(6),rs.getString(7),rs.getString(8).strip()},order.number());
        if(auth.size()==1 && cards.isEmpty() && (provider.isEmpty()||provider.equals("BANK"))) {
            var a=auth.getFirst();
            if(!Objects.equals(t[1],a[1]) || !order.key().equals(a[2]) || !order.customer().equals(a[3])
                    || order.amount().compareTo((BigDecimal)a[4])!=0 || !order.currency().equals(a[5])
                    || !Set.of("COMPLETED","REDEEMED").contains(a[6]) || !account.equals(a[7])
                    || (a[8]!=null&&!merchant.equals(a[8])) || !Objects.equals(t[7],a[9])
                    || (order.authorization()!=null&&!order.authorization().equals(a[0])))throw new Unconfirmed();
            return new Evidence("BANK",(String)t[1],(Long)t[0],(String)a[0],(String)t[7],order.amount(),order.currency());
        }
        if(cards.size()==1 && auth.isEmpty() && (provider.isEmpty()||provider.equals("CARD"))) {
            var c=cards.getFirst();
            if(!Objects.equals(t[1],c[0]) || !order.key().equals(c[1]) || c[2]==null || order.amount().compareTo((BigDecimal)c[2])!=0
                    || c[3]==null || ((BigDecimal)c[3]).signum()<=0 || c[4]==null || !c[4].toString().matches("[A-Z]{3}")
                    || !"COMPLETED".equals(c[5]) || !account.equals(c[6]) || !Objects.equals(t[7],c[7]))throw new Unconfirmed();
            return new Evidence("CARD",(String)t[1],(Long)t[0],null,(String)t[7],(BigDecimal)c[3],(String)c[4]);
        }
        throw new Unconfirmed();
    }
    static boolean stripeMatches(Order o,Map<String,Object> s) {
        if(s==null || !Boolean.FALSE.equals(s.get("livemode")) || !Objects.equals(s.get("id"),o.external()) || !"paid".equals(s.get("payment_status")) || !"complete".equals(s.get("status"))
                || !"payment".equals(s.get("mode")) || !Objects.equals(o.number(),s.get("client_reference_id")) || !"pln".equals(s.get("currency"))
                || !(s.get("amount_total") instanceof Number n) || o.amount().compareTo(new BigDecimal(n.toString()).movePointLeft(2))!=0
                || !(s.get("metadata") instanceof Map<?,?> m) || !o.number().equals(m.get("orderNumber")))return false;
        return o.reference()==null || o.reference().equals(o.external());
    }
    public Result apply(String expected,String confirmation,boolean backupsVerified,boolean writesPaused) throws Exception {
        if(!"MIGRATE".equals(confirmation)||expected==null||!expected.matches("[a-f0-9]{64}")||!backupsVerified||!writesPaused)
            throw new IllegalArgumentException("Reviewed plan, MIGRATE, restored backups and coordinated pause are required.");
        // Session advisory lock serializes CLI and CC importers, including replay after a lost acknowledgement.
        try(var control=shopSource.getConnection()) {
            try(var statement=control.createStatement()){statement.setQueryTimeout(5);statement.execute("SELECT pg_advisory_lock(719034815)");}
            var journal=jdbc(new SingleConnectionDataSource(control,true));
            try {
            var old=journal.query("SELECT manifest::text,status,completed_orders FROM historical_payment_migration_runs WHERE plan_digest=?",
                    (rs,n)->new Object[]{rs.getString(1),rs.getString(2),rs.getInt(3)},expected);
            Manifest manifest;
            if(!old.isEmpty()) {
                manifest=json.readValue((String)old.getFirst()[0],Manifest.class);checkScope(manifest);
                if(!expected.equals(digest(manifest)))throw new IllegalStateException("Stored manifest hash mismatch.");
                if("COMPLETED".equals(old.getFirst()[1]))return new Result((Integer)old.getFirst()[2],true,true,expected);
            } else {
                manifest=inspect(new ArrayList<>());if(!expected.equals(digest(manifest))||manifest.entries().isEmpty())throw new IllegalStateException("Plan changed or contains no proven successes.");
                journal.update("INSERT INTO historical_payment_migration_runs(plan_digest,manifest,status) VALUES (?,?::jsonb,'APPLYING')",expected,json.writeValueAsString(manifest));
            }
            int completed=0;
            for(var entry:manifest.entries()) {
                migrate(entry);completed++;
                journal.update("UPDATE historical_payment_migration_runs SET completed_orders=?,updated_at=CURRENT_TIMESTAMP WHERE plan_digest=?",completed,expected);
            }
            journal.update("UPDATE historical_payment_migration_runs SET status='COMPLETED',updated_at=CURRENT_TIMESTAMP WHERE plan_digest=?",expected);
            return new Result(completed,!old.isEmpty(),true,expected);
            } finally {journal.execute("SELECT pg_advisory_unlock(719034815)");}
        }
    }
    void checkScope(Manifest m){if(!merchant.equals(m.merchant())||!account.equals(m.account())||!bankBackup.equals(m.bankBackup())||!shopBackup.equals(m.shopBackup()))throw new IllegalStateException("Migration scope or backup evidence changed.");}
    void migrate(Entry entry)throws Exception {
        migrate(entry,false);
    }
    void migrate(Entry entry,boolean reviewedRefunds)throws Exception {
        var original=entry.order();var proof=entry.evidence();
        try(var bc=bankSource.getConnection();var sc=shopSource.getConnection()) {
            bc.setAutoCommit(false);sc.setAutoCommit(false);
            var b=jdbc(new SingleConnectionDataSource(bc,true));var s=jdbc(new SingleConnectionDataSource(sc,true));
            try {
                b.execute("SET LOCAL lock_timeout='3s'");s.execute("SET LOCAL lock_timeout='3s'");
                b.execute("LOCK TABLE bank_transactions,payment_authorizations,card_transactions,payment_purchases,payment_purchase_attempts IN SHARE ROW EXCLUSIVE MODE");
                s.execute("LOCK TABLE shop_orders,shop_payment_attempts,shop_refunds IN SHARE ROW EXCLUSIVE MODE");
                var current=orders(s,original.id());if(current.size()!=1)throw new IllegalStateException("Source order missing.");
                var row=current.getFirst();
                if(alreadyApplied(s,row,proof)){
                    if(!Objects.equals(row.number(),original.number()) || !Objects.equals(row.key(),original.key())
                            || !Objects.equals(row.customer(),original.customer()) || !Objects.equals(row.account(),original.account())
                            || !Objects.equals(row.currency(),original.currency()) || row.amount().compareTo(original.amount())!=0
                            || !Objects.equals(row.external(),original.external()))
                        throw new IllegalStateException("Imported order identity changed; operator review is required.");
                    bc.rollback();sc.rollback();return;
                }
                if(!row.equals(original))throw new IllegalStateException("Source order changed after approval.");
                if(!proof.equals(evidence(b,row,reviewedRefunds)))throw new IllegalStateException("Provider evidence changed.");
                if(s.queryForObject("SELECT count(*) FROM shop_payment_attempts WHERE order_id=?",Integer.class,row.id())!=0)
                    throw new IllegalStateException("Order already has a different durable attempt.");
                if(!"STRIPE".equals(proof.provider()))writeBank(b,row,proof);
                // This is not a distributed transaction. Before this point a rollback changes neither DB.
                // After bank commit the success guard is durable; the still-unassigned shop order remains blocked.
                bc.commit();
                var id=attemptId(row);
                s.update("""
                        INSERT INTO shop_payment_attempts(id,order_id,merchant_id,provider,idempotency_key,state,correlation_state,
                        charged_amount,charged_currency,bank_authorization_id,external_payment_id,financial_reference,customer_account_number)
                        VALUES (?,?,?,?,?,'SUCCEEDED',?,?,?,?,?,?,?)
                        """,id,row.id(),merchant,proof.provider(),row.key(),row.correlation()==null?id:row.correlation(),proof.charged(),proof.currency(),
                        proof.authorization(),"STRIPE".equals(proof.provider())?row.external():null,proof.reference(),proof.sourceAccount());
                if(s.update("""
                        UPDATE shop_orders SET merchant_id=?,status='PAID',payment_provider=?,bank_reference=?,failure_reason=NULL,
                        historical_imported_at=CURRENT_TIMESTAMP,reconciliation_suspended_at=NULL,reconciliation_failures=0,
                        reconciliation_last_error=NULL,payment_reconciled_at=CURRENT_TIMESTAMP,updated_at=CURRENT_TIMESTAMP
                        WHERE id=? AND merchant_id IS NULL
                        """,merchant,proof.provider(),proof.reference(),row.id())!=1)throw new IllegalStateException("Order import conflict.");
                sc.commit();
            } catch(Exception error){bc.rollback();sc.rollback();throw error;}
        }
    }
    boolean alreadyApplied(JdbcTemplate s,Order row,Evidence proof) {
        if(!merchant.equals(row.merchant())||!"PAID".equals(row.status())||!proof.reference().equals(row.reference()))return false;
        return Boolean.TRUE.equals(s.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM shop_payment_attempts WHERE id=? AND order_id=? AND merchant_id=? AND provider=?
                AND idempotency_key=? AND state='SUCCEEDED' AND financial_reference=? AND charged_amount=? AND charged_currency=?
                AND EXISTS(SELECT 1 FROM shop_orders o WHERE o.id=order_id AND o.historical_imported_at IS NOT NULL))
                """,Boolean.class,attemptId(row),row.id(),merchant,proof.provider(),row.key(),proof.reference(),proof.charged(),proof.currency()));
    }
    void writeBank(JdbcTemplate b,Order row,Evidence proof){
        b.update("INSERT INTO payment_purchases(merchant_id,order_id,amount,currency,active_key,bank_reference) VALUES (?,?,?,?,?,?) ON CONFLICT DO NOTHING",
                merchant,row.number(),row.amount(),row.currency(),row.key(),proof.reference());
        if(!Boolean.TRUE.equals(b.queryForObject("SELECT EXISTS(SELECT 1 FROM payment_purchases WHERE merchant_id=? AND order_id=? AND amount=? AND currency=? AND active_key=? AND bank_reference=?)",
                Boolean.class,merchant,row.number(),row.amount(),row.currency(),row.key(),proof.reference())))throw new IllegalStateException("Bank purchase conflict.");
        b.update("""
                INSERT INTO payment_purchase_attempts(idempotency_key,merchant_id,order_id,kind,state,merchant_account_number,bank_reference,charged_amount,charged_currency)
                VALUES (?,?,?,?,'SUCCEEDED',?,?,?,?) ON CONFLICT DO NOTHING
                """,row.key(),merchant,row.number(),proof.provider(),account,proof.reference(),proof.charged(),proof.currency());
        if(!Boolean.TRUE.equals(b.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM payment_purchase_attempts WHERE idempotency_key=? AND merchant_id=? AND order_id=?
                AND kind=? AND state='SUCCEEDED' AND merchant_account_number=? AND bank_reference=? AND charged_amount=? AND charged_currency=?)
                """,Boolean.class,row.key(),merchant,row.number(),proof.provider(),account,proof.reference(),proof.charged(),proof.currency())))throw new IllegalStateException("Bank attempt conflict.");
        if(b.update("UPDATE bank_transactions SET merchant_id=? WHERE id=? AND (merchant_id IS NULL OR merchant_id=?)",merchant,proof.ledger(),merchant)!=1)
            throw new IllegalStateException("Financial identity conflict.");
        if(proof.authorization()!=null && b.update("UPDATE payment_authorizations SET merchant_id=? WHERE id=? AND (merchant_id IS NULL OR merchant_id=?)",merchant,proof.authorization(),merchant)!=1)
            throw new IllegalStateException("Authorization identity conflict.");
    }
    static String attemptId(Order row){return UUID.nameUUIDFromBytes(("historical:"+row.number()+":"+row.key()).getBytes(StandardCharsets.UTF_8)).toString();}
    String digest(Manifest manifest){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(manifest)));}catch(Exception error){throw new IllegalStateException("Cannot hash migration manifest.");}}
    static Map<String,Object> stripeSession(String id,String key){
        if(!key.matches("(?:sk|rk)_test_[A-Za-z0-9_]+"))throw new Unconfirmed();
        try {
            var client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
            var result=client.send(HttpRequest.newBuilder(URI.create("https://api.stripe.com/v1/checkout/sessions/"+id)).timeout(Duration.ofSeconds(10))
                    .header("Authorization","Basic "+Base64.getEncoder().encodeToString((key+":").getBytes(StandardCharsets.UTF_8))).GET().build(),HttpResponse.BodyHandlers.ofInputStream());
            try(var stream=result.body()){
                var bytes=stream.readNBytes(262145);if(result.statusCode()!=200||bytes.length>262144)throw new Unconfirmed();
                return new ObjectMapper().readValue(bytes,new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){});
            }
        }catch(Exception error){throw new Unconfirmed();}
    }
    static final class Unconfirmed extends RuntimeException { }
}
