package org.demo.shopapp.order;

import org.demo.shopapp.payment.BankAuthorizationClient;
import org.demo.shopapp.payment.StripeCheckoutClient;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;

/** Durable cancellation intent first, provider calls outside DB transactions, then guarded finalization.
 * This is NOT the risk override for a historical UNKNOWN payment. No balances/refunds are changed here.
 */
@Service
public class PurchaseCancellationService {
    private final JdbcTemplate jdbc;
    private final OrderRepository orders;
    private final PaymentAttemptRepository attempts;
    private final PaymentResolutionService resolution;
    private final BankAuthorizationClient bank;
    private final StripeCheckoutClient stripe;
    private final TransactionTemplate tx;
    private final int abandonedMinutes;
    public record Result(long orderId,String status,String message,boolean cartReleased,boolean caseClosed){}
    private record Claim(Result result,PaymentAttempt attempt){}
    public PurchaseCancellationService(JdbcTemplate jdbc,OrderRepository orders,PaymentAttemptRepository attempts,
            PaymentResolutionService resolution,BankAuthorizationClient bank,StripeCheckoutClient stripe,PlatformTransactionManager manager,
            @org.springframework.beans.factory.annotation.Value("${shop.orders.abandoned-after-minutes:30}") int abandonedMinutes){
        this.jdbc=jdbc;this.orders=orders;this.attempts=attempts;this.resolution=resolution;this.bank=bank;this.stripe=stripe;tx=new TransactionTemplate(manager);this.abandonedMinutes=Math.max(10,abandonedMinutes);
    }
    public Result cancel(long id,String customer,UUID request,String actor,String reason){
        if(request==null || actor==null || !Set.of("CUSTOMER","LOCAL_CC_DEV","AUTO_EXPIRY").contains(actor)
                || reason==null || reason.strip().length()<8 || reason.length()>1000 || reason.chars().anyMatch(c->c<32||c==127))
            throw conflict("Wymagane poprawne potwierdzenie i uzasadnienie rezygnacji.");
        var claim=tx.execute(s->claim(id,customer,request,actor,reason));
        if(claim.result()!=null)return claim.result();
        var attempt=claim.attempt();
        try {
            switch(attempt.provider()){
                case "BANK" -> {
                    // Both bank cancellation and approval lock the same durable purchase before authorization.
                    bank.cancelByKey(attempt.key());
                    resolution.refresh(attempt);
                }
                case "CARD" -> resolution.refresh(attempt); // A synchronous card dispatch cannot be locally revoked.
                case "STRIPE" -> {
                    if(attempt.externalId()==null)throw conflict("Brak potwierdzonego identyfikatora operatora.");
                    var session=stripe.cancellationStatus(attempt.externalId(),request.toString());
                    if(resolution.stripeCancellationConfirmed(attempt,session))
                        tx.executeWithoutResult(s->{
                            lock(id,customer);
                            int changed=jdbc.update("UPDATE shop_payment_attempts SET state='CANCELLED',version=version+1,updated_at=CURRENT_TIMESTAMP WHERE id=? AND state IN ('PROCESSING','AWAITING_AUTHORIZATION','UNKNOWN','CANCELLED')",attempt.id());
                            if(changed!=1)throw conflict("Stan płatności zmienił się podczas rezygnacji.");
                        });
                }
                default -> throw conflict("Operator wymaga uzgodnienia.");
            }
        } catch(RuntimeException unconfirmed) {
            // Never convert 404, timeout, bad evidence or an in-flight payment into a terminal refusal.
        }
        return tx.execute(s->finish(id,customer,request));
    }
    private OrderRepository.OrderRow lock(long id,String customer){
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(?,0)) IS NULL",Boolean.class,"checkout-customer:"+customer);
        return orders.findByIdForUpdate(id,customer);
    }
    private Claim claim(long id,String customer,UUID request,String actor,String reason){
        var order=lock(id,customer);
        if("AUTO_EXPIRY".equals(actor) && Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT cancel_requested_at IS NULL AND GREATEST(created_at,COALESCE((SELECT max(a.created_at) FROM shop_payment_attempts a WHERE a.order_id=o.id),created_at))
                > CURRENT_TIMESTAMP-make_interval(mins=>?) FROM shop_orders o WHERE id=?
                """,Boolean.class,abandonedMinutes,id)))return new Claim(new Result(id,"NOT_DUE","Zakup jest nadal aktywny.",false,false),null);
        var old=jdbc.queryForList("SELECT order_id,actor,reason FROM shop_purchase_cancellation_requests WHERE request_id=?",request);
        if(!old.isEmpty()){
            var row=old.getFirst();
            if(((Number)row.get("order_id")).longValue()!=id || !actor.equals(row.get("actor")) || !reason.equals(row.get("reason")))throw conflict("Identyfikator rezygnacji należy do innego żądania.");
        }else{
            jdbc.update("INSERT INTO shop_purchase_cancellation_requests(request_id,order_id,actor,reason,state) VALUES (?,?,?,?,'REQUESTED')",request,id,actor,reason);
            jdbc.update("INSERT INTO shop_purchase_cancellation_events(request_id,state) VALUES (?,'REQUESTED')",request);
        }
        if(order.status()==OrderStatus.PAID)return new Claim(outcome(request,id,"PAID",false),null);
        if(order.status()==OrderStatus.CANCELLED)return new Claim(outcome(request,id,"CANCELLED",false),null);
        jdbc.update("UPDATE shop_orders SET cancel_requested_at=COALESCE(cancel_requested_at,clock_timestamp()),updated_at=CURRENT_TIMESTAMP WHERE id=?",id);
        if(!modern(id)||blockedByEvidence(id))return new Claim(outcome(request,id,"REVIEW",false),null);
        var active=attempts.active(id);
        if(active.isEmpty())return new Claim(finish(id,customer,request),null);
        return new Claim(null,active.get());
    }
    private boolean modern(long id){
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT purchase_id IS NOT NULL AND merchant_id IS NOT NULL AND historical_imported_at IS NULL FROM shop_orders WHERE id=?",Boolean.class,id));
    }
    private boolean blockedByEvidence(long id){
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM shop_payment_cases WHERE order_id=? AND financial_outcome IN ('PAID','REFUNDED','CONFLICT','REFUND_REVIEW'))
                OR EXISTS(SELECT 1 FROM shop_payment_case_plans WHERE order_id=? AND status='APPLYING')
                OR EXISTS(SELECT 1 FROM shop_refunds WHERE order_id=?)
                """,Boolean.class,id,id,id));
    }
    private boolean noChargeEvidence(OrderRepository.OrderRow order){
        var count=jdbc.queryForObject("SELECT count(*) FROM shop_payment_attempts WHERE order_id=?",Integer.class,order.id());
        var unsafe=jdbc.queryForObject("SELECT count(*) FROM shop_payment_attempts WHERE order_id=? AND state NOT IN ('DECLINED','CANCELLED')",Integer.class,order.id());
        // An old reference without a durable attempt cannot prove that dispatch never began.
        return unsafe==0 && (count>0 || (order.paymentIdempotencyKey()==null && order.bankAuthorizationId()==null && order.externalPaymentId()==null));
    }
    private Result finish(long id,String customer,UUID request){
        var order=lock(id,customer);
        if(order.status()==OrderStatus.PAID)return outcome(request,id,"PAID",false);
        if(order.status()==OrderStatus.CANCELLED)return outcome(request,id,"CANCELLED",false);
        if(!modern(id)||blockedByEvidence(id))return outcome(request,id,"REVIEW",false);
        if(!noChargeEvidence(order))return outcome(request,id,"REVIEW",false);
        jdbc.update("UPDATE shop_orders SET status='CANCELLED',cancelled_at=clock_timestamp(),updated_at=CURRENT_TIMESTAMP WHERE id=?",id);
        var current=jdbc.queryForList("SELECT c.purchase_id FROM shop_carts c JOIN shop_orders o ON o.customer_id=c.customer_id AND o.purchase_id=c.purchase_id WHERE o.id=? AND c.customer_id=? FOR UPDATE OF c",UUID.class,id,customer);
        boolean released=!current.isEmpty();
        if(released){
            var next=UUID.randomUUID();
            jdbc.update("""
                INSERT INTO shop_purchase_snapshots(purchase_id,customer_id,snapshot,replacement_purchase_id)
                SELECT c.purchase_id,c.customer_id,jsonb_build_object('orderIds',jsonb_build_array(?::bigint),'reason','CONFIRMED_CANCELLATION',
                'items',COALESCE((SELECT jsonb_agg(to_jsonb(i)) FROM shop_cart_items i WHERE i.cart_id=c.id),'[]'::jsonb)),?
                FROM shop_carts c WHERE c.customer_id=?
                """,id,next,customer);
            // Preserve items and earlier reviewed risk permissions. Never modify an unrelated newer basket.
            jdbc.update("UPDATE shop_carts SET purchase_id=?,updated_at=CURRENT_TIMESTAMP WHERE customer_id=? AND purchase_id=?",next,customer,current.getFirst());
        }
        return outcome(request,id,"CANCELLED",released);
    }
    private Result outcome(UUID request,long id,String state,boolean released){
        var stored=jdbc.queryForMap("SELECT state,actor,reason FROM shop_purchase_cancellation_requests WHERE request_id=?",request);
        boolean closed=false;
        if("CANCELLED".equals(state) && "LOCAL_CC_DEV".equals(stored.get("actor"))){
            var order=orders.findById(id,jdbc.queryForObject("SELECT customer_id FROM shop_orders WHERE id=?",String.class,id));
            if(!modern(id)||blockedByEvidence(id)||!noChargeEvidence(order))state="REVIEW";
            else closed=closeConfirmedCase(id,request,(String)stored.get("reason"));
        }
        jdbc.update("UPDATE shop_purchase_cancellation_requests SET state=?,checked_at=CURRENT_TIMESTAMP WHERE request_id=?",state,request);
        if(!state.equals(stored.get("state")))
            jdbc.update("INSERT INTO shop_purchase_cancellation_events(request_id,state) VALUES (?,?)",request,state);
        final boolean caseClosed=closed;
        return new Result(id,state,switch(state){
            case "CANCELLED" -> caseClosed?"Potwierdzono brak obciążenia. Zakup anulowano, sprawę zamknięto i przeniesiono do Historii. Produkty zachowano.":"Zrezygnowano z zakupu. Produkty zachowano; możesz wrócić do edycji koszyka.";
            case "PAID" -> "Zamówienie jest opłacone. Rezygnacja wymaga osobnego procesu zwrotu.";
            default -> "Zapisano prośbę o rezygnację. Wynik płatności wymaga potwierdzenia; nie wysłano nowego obciążenia.";
        },released,closed);
    }
    private boolean closeConfirmedCase(long id,UUID request,String reason){
        // Called under the checkout-customer lock and the order row lock, in the cancellation transaction.
        var previous=jdbc.queryForList("SELECT order_id,action,reason FROM shop_payment_case_events WHERE request_id=?",request);
        if(!previous.isEmpty()){
            var event=previous.getFirst();
            if(((Number)event.get("order_id")).longValue()!=id || !"CLOSE_CANCELLED".equals(event.get("action")) || !reason.equals(event.get("reason")))
                throw conflict("Identyfikator rezygnacji należy do innej decyzji operatora.");
            return Boolean.TRUE.equals(jdbc.queryForObject("SELECT workflow_status='RESOLVED' AND financial_outcome='CANCELLED' FROM shop_payment_cases WHERE order_id=?",Boolean.class,id));
        }
        jdbc.update("INSERT INTO shop_payment_cases(order_id) VALUES (?) ON CONFLICT DO NOTHING",id);
        long version=jdbc.queryForObject("SELECT version FROM shop_payment_cases WHERE order_id=? FOR UPDATE",Long.class,id);
        jdbc.update("""
                UPDATE shop_payment_cases SET financial_outcome='CANCELLED',workflow_status='RESOLVED',
                allow_new_purchase_at=NULL,checked_at=CURRENT_TIMESTAMP,updated_at=CURRENT_TIMESTAMP,version=version+1 WHERE order_id=?
                """,id);
        jdbc.update("""
                INSERT INTO shop_payment_case_events(request_id,order_id,action,operator_id,reason,expected_version,resulting_version)
                VALUES (?,?,'CLOSE_CANCELLED','local-cc-dev',?,?,?)
                """,request,id,reason,version,version+1);
        return true;
    }
    public Result refresh(long id,String customer){
        orders.findById(id,customer); // Ownership before any provider call or reconciliation side effect.
        var active=attempts.active(id);
        if(active.isPresent())try{resolution.refresh(active.get());}catch(RuntimeException unconfirmed){ }
        return new Result(id,orders.findById(id,customer).status().name(),"Sprawdzono dostępny stan płatności. Brak potwierdzenia nie oznacza odmowy.",false,false);
    }
    private static ResponseStatusException conflict(String text){return new ResponseStatusException(HttpStatus.CONFLICT,text);}
}
