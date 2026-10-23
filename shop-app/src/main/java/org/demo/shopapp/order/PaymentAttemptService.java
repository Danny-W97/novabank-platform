package org.demo.shopapp.order;

import org.demo.shopapp.config.ShopOrderProperties;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.math.BigDecimal;

@Service
public class PaymentAttemptService {
    private final PaymentAttemptRepository attempts;
    private final OrderRepository orders;
    private final ShopOrderProperties properties;
    private final JdbcTemplate jdbc;
    public PaymentAttemptService(PaymentAttemptRepository attempts, OrderRepository orders,
                                 ShopOrderProperties properties, JdbcTemplate jdbc) {
        this.attempts=attempts; this.orders=orders; this.properties=properties; this.jdbc=jdbc;
    }

    @Transactional
    public Prepared begin(long orderId, String customer, String key, String provider,
                          BigDecimal chargedAmount, String chargedCurrency) {
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(?,0)) IS NULL",Boolean.class,"checkout-customer:"+customer);
        var order=orders.findByIdForUpdate(orderId,customer);
        if(order.status()==OrderStatus.PAID) return new Prepared(order,null,false);
        if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM shop_payment_cases WHERE order_id=? AND workflow_status='CLOSED_UNRESOLVED')",Boolean.class,orderId)))
            throw conflict("Sprawę zamknięto bez rozstrzygnięcia. Ponawianie płatności tego zamówienia jest zablokowane; wymagany przegląd operatora.");
        if(order.status()==OrderStatus.CANCELLED || Boolean.TRUE.equals(jdbc.queryForObject("SELECT cancel_requested_at IS NOT NULL FROM shop_orders WHERE id=?",Boolean.class,orderId)))
            throw conflict("Zlecono rezygnację z tego zakupu. Wróć do koszyka lub sprawdź status zamówienia.");
        var merchant=properties.requireMerchantId();
        if(!merchant.equals(attempts.merchantOfOrder(orderId)))
            throw conflict("Order requires explicit merchant migration before payment.");
        if(chargedAmount==null) chargedAmount=order.total();
        if(chargedCurrency==null) chargedCurrency=order.currency();
        var previous=attempts.byKey(key);
        if(previous.isPresent()) {
            var attempt=previous.get();
            if(attempt.orderId()!=orderId || !attempt.merchantId().equals(merchant)
                    || !attempt.provider().equals(provider) || attempt.chargedAmount().compareTo(chargedAmount)!=0
                    || !attempt.chargedCurrency().equals(chargedCurrency))
                throw conflict("Idempotency key belongs to different payment data.");
            if("UNKNOWN".equals(attempt.state()) && !"STRIPE".equals(provider)) {
                // Explicit user retry only; the bank retains this key and serializes it. Never generate a new key/state.
                PaymentAttemptRepository.requireOne(jdbc.update("""
                        UPDATE shop_payment_attempts SET state='PROCESSING',version=version+1,updated_at=CURRENT_TIMESTAMP
                        WHERE id=? AND state='UNKNOWN'
                        """,attempt.id()));
                return new Prepared(order,attempts.byId(attempt.id()).orElseThrow(),true);
            }
            return new Prepared(order,attempt,false);
        }
        if(attempts.active(orderId).isPresent())
            throw conflict("Previous payment is pending or UNKNOWN. Reconcile it before starting another payment.");
        var attempt=attempts.insert(orderId,merchant,key,provider,chargedAmount,chargedCurrency);
        PaymentAttemptRepository.requireOne(jdbc.update("""
                UPDATE shop_orders SET status='PENDING',payment_idempotency_key=?,payment_provider=?,payment_state=?,
                bank_authorization_id=NULL,external_payment_id=NULL,external_checkout_url=NULL,
                bank_reference=NULL,failure_reason=NULL,reconciliation_failures=0,reconciliation_suspended_at=NULL,
                reconciliation_last_error=NULL,payment_reconciled_at=NULL,updated_at=CURRENT_TIMESTAMP WHERE id=? AND status<>'PAID'
                """,key,provider,attempt.correlation(),orderId));
        return new Prepared(orders.findById(orderId,customer),attempt,true);
    }

    @Transactional
    public void attachAuthorization(PaymentAttempt snapshot,String authorization,String url) {
        var order=attempts.orderForAttempt(snapshot,orders);
        var attempt=fresh(snapshot);
        if(attempt.succeeded()) return;
        attempts.attachAuthorization(attempt,authorization,url);
        if(snapshot.key().equals(order.paymentIdempotencyKey()))
            orders.attachAuthorization(order.id(),order.customerId(),authorization,attempt.correlation());
    }

    @Transactional
    public void attachExternal(PaymentAttempt snapshot,String external,String url) {
        var order=attempts.orderForAttempt(snapshot,orders);
        var attempt=fresh(snapshot);
        attempts.attachExternal(attempt,external,url);
        if(snapshot.key().equals(order.paymentIdempotencyKey()))
            orders.attachExternalPayment(order.id(),order.customerId(),"STRIPE",external,url);
    }

    @Transactional
    public OrderRepository.OrderRow succeed(PaymentAttempt snapshot,String reference,String account) {
        var order=attempts.orderForAttempt(snapshot,orders);
        if(order.status()==OrderStatus.CANCELLED)throw conflict("FINANCIAL_CONFLICT: success contradicts confirmed purchase cancellation; operator review required.");
        var attempt=fresh(snapshot);
        if(reference==null || reference.isBlank()) throw conflict("Missing financial reference.");
        if(order.status()==OrderStatus.PAID && !reference.equals(order.bankReference()))
            throw conflict("FINANCIAL_CONFLICT: another reference already paid this order; operator review required.");
        if(!attempt.key().equals(order.paymentIdempotencyKey()))
            throw conflict("FINANCIAL_CONFLICT: late success from a different attempt; operator review required.");
        attempts.succeed(attempt,reference,account);
        PaymentAttemptRepository.requireOne(jdbc.update("""
                UPDATE shop_orders SET status='PAID',bank_reference=?,customer_account_number=?,
                failure_reason=NULL,reconciliation_failures=0,reconciliation_suspended_at=NULL,
                reconciliation_last_error=NULL,payment_reconciled_at=CURRENT_TIMESTAMP,updated_at=CURRENT_TIMESTAMP
                WHERE id=? AND payment_idempotency_key=? AND (status<>'PAID' OR bank_reference=?)
                """,reference,account,order.id(),attempt.key(),reference));
        var reopened=jdbc.queryForList("""
                UPDATE shop_payment_cases SET financial_outcome='PAID',workflow_status='ESCALATED',
                version=version+1,updated_at=CURRENT_TIMESTAMP WHERE order_id=? AND workflow_status='CLOSED_UNRESOLVED'
                RETURNING version
                """,Long.class,order.id());
        if(!reopened.isEmpty()){
            long version=reopened.getFirst();
            jdbc.update("""
                    INSERT INTO shop_payment_case_events(request_id,order_id,action,operator_id,reason,expected_version,resulting_version)
                    VALUES (?,?,'LATE_PAYMENT_SUCCESS','PAYMENT_RECONCILIATION',?,?,?)
                    """,java.util.UUID.randomUUID(),order.id(),"Potwierdzono opłacenie po zamknięciu bez rozstrzygnięcia. Sprawę otwarto do przeglądu.",version-1,version);
        }
        return orders.findById(order.id(),order.customerId());
    }

    @Transactional
    public void decline(PaymentAttempt snapshot,String reason) {
        var order=attempts.orderForAttempt(snapshot,orders);
        var attempt=fresh(snapshot);
        if(attempt.succeeded() || order.status()==OrderStatus.PAID || order.status()==OrderStatus.CANCELLED) return;
        attempts.decline(attempt,reason);
        jdbc.update("""
                UPDATE shop_orders SET status='FAILED',failure_reason=?,reconciliation_failures=0,
                reconciliation_suspended_at=NULL,reconciliation_last_error=NULL,payment_reconciled_at=CURRENT_TIMESTAMP,
                updated_at=CURRENT_TIMESTAMP
                WHERE id=? AND payment_idempotency_key=? AND status<>'PAID'
                """,reason,order.id(),attempt.key());
    }

    @Transactional
    public void unknown(PaymentAttempt snapshot) {
        var order=attempts.orderForAttempt(snapshot,orders);
        var attempt=fresh(snapshot);
        if(attempt.terminal() || order.status()==OrderStatus.PAID || order.status()==OrderStatus.CANCELLED) return;
        var message="Wynik płatności jest niepotwierdzony. Nie rozpoczynaj nowej płatności; trwa uzgadnianie.";
        attempts.unknown(attempt,message);
        jdbc.update("""
                UPDATE shop_orders SET status='UNKNOWN',failure_reason=?,updated_at=CURRENT_TIMESTAMP
                WHERE id=? AND payment_idempotency_key=? AND status<>'PAID'
                """,message,order.id(),attempt.key());
    }

    @Transactional(readOnly=true)
    public OrderRepository.OrderRow order(PaymentAttempt attempt) {
        var customer=jdbc.queryForObject("SELECT customer_id FROM shop_orders WHERE id=?",String.class,attempt.orderId());
        return orders.findById(attempt.orderId(),customer);
    }
    @Transactional public void checked(PaymentAttempt attempt) {
        attempts.orderForAttempt(attempt,orders);
        attempts.checked(attempt);
        jdbc.update("""
                UPDATE shop_orders SET reconciliation_failures=0,reconciliation_last_error=NULL,
                payment_reconciled_at=CURRENT_TIMESTAMP WHERE id=? AND payment_idempotency_key=? AND reconciliation_suspended_at IS NULL
                """,attempt.orderId(),attempt.key());
    }
    @Transactional public int failure(PaymentAttempt attempt) {
        attempts.orderForAttempt(attempt,orders);
        int count=attempts.failure(attempt);
        if(count>0) jdbc.update("""
                UPDATE shop_orders SET reconciliation_failures=?,payment_reconciled_at=CURRENT_TIMESTAMP,
                reconciliation_last_error='Payment reconciliation failed; outcome unresolved.',
                reconciliation_suspended_at=CASE WHEN ?>=3 THEN CURRENT_TIMESTAMP ELSE NULL END
                WHERE id=? AND payment_idempotency_key=? AND status<>'PAID'
                """,count,count,attempt.orderId(),attempt.key());
        return count;
    }
    private PaymentAttempt fresh(PaymentAttempt attempt) { return attempts.byId(attempt.id()).orElseThrow(); }
    private static ResponseStatusException conflict(String text) { return new ResponseStatusException(HttpStatus.CONFLICT,text); }
    public record Prepared(OrderRepository.OrderRow order,PaymentAttempt attempt,boolean dispatch) { }
}
