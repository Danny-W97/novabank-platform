package org.demo.shopapp.cart;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;

/** A new UUID alone is not a new purchase: explicit acknowledgement archives and empties the old cart. */
@Service
public class PurchaseIdentityService {
    private final JdbcTemplate jdbc;
    private final CartRepository carts;
    public PurchaseIdentityService(JdbcTemplate jdbc, CartRepository carts) {this.jdbc=jdbc;this.carts=carts;}

    public static final String UNRESOLVED = """
        (EXISTS(SELECT 1 FROM shop_payment_attempts a WHERE a.order_id=o.id
            AND a.state IN ('CREATED','PROCESSING','AWAITING_AUTHORIZATION','UNKNOWN'))
         OR (o.merchant_id IS NULL AND o.payment_idempotency_key IS NOT NULL AND o.status<>'PAID'))
        """;
    @Transactional
    public UUID lock(String customer) {
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(?,0)) IS NULL",Boolean.class,"checkout-customer:"+customer);
        // Unique upsert also serializes the first simultaneous cart creations.
        carts.ensureCart(customer);
        return jdbc.queryForObject("SELECT purchase_id FROM shop_carts WHERE customer_id=? FOR UPDATE",UUID.class,customer);
    }
    public boolean blocked(String customer) {
        return blocked(jdbc,customer);
    }
    public static boolean blocked(JdbcTemplate jdbc,String customer) {
        if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM shop_carts WHERE customer_id=? AND requires_independent_ack)",Boolean.class,customer)))return true;
        return Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT EXISTS(SELECT 1 FROM shop_orders o LEFT JOIN shop_payment_cases p ON p.order_id=o.id
            LEFT JOIN shop_carts c ON c.customer_id=o.customer_id WHERE o.customer_id=? AND
            """+UNRESOLVED+"""
            AND (p.allow_new_purchase_at IS NULL OR c.independent_purchase_at IS NULL
                 OR c.independent_purchase_at<p.allow_new_purchase_at OR o.purchase_id=c.purchase_id))
            """,Boolean.class,customer));
    }
    public boolean mayStartIndependent(String customer) {
        return !Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT EXISTS(SELECT 1 FROM shop_orders o LEFT JOIN shop_payment_cases p ON p.order_id=o.id
            WHERE o.customer_id=? AND
            """+UNRESOLVED+" AND p.allow_new_purchase_at IS NULL)",Boolean.class,customer));
    }
    public void requireEditable(String customer) {
        var purchase=lock(customer);
        if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM shop_orders WHERE customer_id=? AND purchase_id=?)",Boolean.class,customer,purchase)))
            throw conflict("Koszyk tego zakupu jest zapisany w zamówieniu. Wróć do zamówienia albo jawnie rozpocznij nowy zakup.");
    }
    @Transactional
    public Map<String,Object> status(String customer) {
        var purchase=lock(customer);
        var orders=jdbc.queryForList("SELECT id::text AS id,status,payment_provider AS provider,cancel_requested_at AS \"cancelRequestedAt\" FROM shop_orders WHERE customer_id=? AND purchase_id=?",customer,purchase);
        return Map.of("purchaseId",purchase.toString(),"blocked",blocked(customer),"canStartIndependent",mayStartIndependent(customer),"orders",orders,
            "requiresIndependentAcknowledgement",jdbc.queryForObject("SELECT requires_independent_ack FROM shop_carts WHERE customer_id=?",Boolean.class,customer));
    }
    @Transactional
    public Map<String,Object> startIndependent(String customer,String expected,boolean acknowledged) {
        UUID previous;try{previous=UUID.fromString(expected);}catch(Exception invalid){throw conflict("Nieprawidłowy identyfikator koszyka.");}
        var current=lock(customer);
        if(!acknowledged)throw conflict("Potwierdź odłożenie starego koszyka i rozpoczęcie niezależnego zakupu.");
        if(!current.equals(previous)) {
            var replay=jdbc.queryForList("SELECT replacement_purchase_id FROM shop_purchase_snapshots WHERE purchase_id=? AND customer_id=?",previous,customer);
            if(replay.size()==1 && current.equals(replay.getFirst().get("replacement_purchase_id")))return status(customer);
            throw conflict("Koszyk zmienił się. Odśwież widok przed ponowieniem.");
        }
        if(!mayStartIndependent(customer))throw conflict("Nierozstrzygnięta płatność wymaga decyzji operatora w Control Center.");
        var next=UUID.randomUUID();
        jdbc.update("""
            INSERT INTO shop_purchase_snapshots(purchase_id,customer_id,snapshot,replacement_purchase_id)
            SELECT c.purchase_id,c.customer_id,jsonb_build_object('items',COALESCE((SELECT jsonb_agg(to_jsonb(i))
            FROM shop_cart_items i WHERE i.cart_id=c.id),'[]'::jsonb),'orderIds',COALESCE((SELECT jsonb_agg(o.id)
            FROM shop_orders o WHERE o.customer_id=c.customer_id AND o.purchase_id=c.purchase_id),'[]'::jsonb)),?
            FROM shop_carts c WHERE c.customer_id=?
            """,next,customer);
        carts.clear(customer); // Explicit confirmation; original order/items and archived cart survive.
        jdbc.update("UPDATE shop_carts SET purchase_id=?,independent_purchase_at=clock_timestamp(),requires_independent_ack=FALSE,updated_at=CURRENT_TIMESTAMP WHERE customer_id=?",next,customer);
        return status(customer);
    }
    private static ResponseStatusException conflict(String message){return new ResponseStatusException(HttpStatus.CONFLICT,message);}
}
