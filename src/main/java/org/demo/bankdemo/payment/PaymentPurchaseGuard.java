package org.demo.bankdemo.payment;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;

/** All callers lock purchase -> authorization/card -> accounts, in one local transaction. */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class PaymentPurchaseGuard {
    private final JdbcTemplate jdbc;
    public PaymentPurchaseGuard(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public void begin(MerchantIdentity merchant, String order, BigDecimal amount, String currency,
                      String account, String key, String kind) {
        begin(merchant,order,amount,currency,account,key,kind,amount,currency,null);
    }

    public void begin(MerchantIdentity merchant, String order, BigDecimal amount, String currency,
                      String account, String key, String kind, BigDecimal chargedAmount, String chargedCurrency, String title) {
        merchant.requireAccount(account);
        Boolean legacy = jdbc.queryForObject("""
                SELECT EXISTS (
                  SELECT 1 FROM bank_transactions t
                  WHERE (t.payment_order_id = ? OR t.idempotency_key = ?)
                    AND t.refund_original_id IS NULL AND t.type = 'PAYMENT'
                    AND NOT EXISTS (SELECT 1 FROM payment_purchase_attempts a
                                    WHERE a.bank_reference = t.reference_number)
                  UNION ALL
                  SELECT 1 FROM payment_authorizations
                  WHERE (shop_order_id = ? OR payment_idempotency_key = ?) AND merchant_id IS NULL
                  UNION ALL
                  SELECT 1 FROM card_transactions c
                  WHERE (c.order_id = ? OR c.idempotency_key = ?) AND c.channel = 'ONLINE'
                    AND NOT EXISTS (SELECT 1 FROM payment_purchase_attempts a WHERE a.idempotency_key=c.idempotency_key)
                )
                """, Boolean.class, order, key, order, key, order, key);
        if (Boolean.TRUE.equals(legacy))
            throw conflict("Legacy payment requires explicit merchant assignment before retry.");
        jdbc.update("""
                INSERT INTO payment_purchases(merchant_id, order_id, amount, currency)
                VALUES (?, ?, ?, ?) ON CONFLICT DO NOTHING
                """, merchant.id(), order, amount, currency);
        var purchase = lock(merchant.id(), order);
        if (purchase.amount().compareTo(amount) != 0 || !purchase.currency().equals(currency))
            throw conflict("Purchase amount or currency cannot change.");
        var existing = jdbc.query("""
                SELECT merchant_id, order_id, kind, merchant_account_number, charged_amount, charged_currency, payment_title
                FROM payment_purchase_attempts
                WHERE idempotency_key = ?
                """, (rs, n) -> rs.getString(1).equals(merchant.id()) && rs.getString(2).equals(order)
                && rs.getString(3).equals(kind) && rs.getString(4).equals(account)
                && (rs.getBigDecimal(5)==null || rs.getBigDecimal(5).compareTo(chargedAmount)==0)
                && (rs.getString(6)==null || rs.getString(6).equals(chargedCurrency))
                && java.util.Objects.equals(rs.getString(7),title), key);
        if (!existing.isEmpty()) {
            if (!existing.getFirst()) throw conflict("Idempotency key belongs to different payment data.");
            return;
        }
        if (purchase.reference() != null) throw conflict("Purchase is already paid.");
        if (purchase.activeKey() != null) {
            var state = jdbc.queryForObject("SELECT state FROM payment_purchase_attempts WHERE idempotency_key = ?",
                    String.class, purchase.activeKey());
            if (!"DECLINED".equals(state)) throw conflict("Previous payment outcome is unresolved.");
        }
        jdbc.update("""
                INSERT INTO payment_purchase_attempts
                (idempotency_key, merchant_id, order_id, kind, state, merchant_account_number,charged_amount,charged_currency,payment_title)
                VALUES (?, ?, ?, ?, 'PENDING', ?, ?, ?, ?)
                """, key, merchant.id(), order, kind, account,chargedAmount,chargedCurrency,title);
        jdbc.update("UPDATE payment_purchases SET active_key = ? WHERE merchant_id = ? AND order_id = ?",
                key, merchant.id(), order);
    }

    public Purchase lock(String merchant, String order) {
        if (merchant == null) throw conflict("Legacy payment requires explicit merchant assignment.");
        var rows = jdbc.query("""
                SELECT amount, currency, active_key, bank_reference FROM payment_purchases
                WHERE merchant_id = ? AND order_id = ? FOR UPDATE
                """, (rs, n) -> new Purchase(rs.getBigDecimal(1), rs.getString(2), rs.getString(3), rs.getString(4)),
                merchant, order);
        if (rows.isEmpty()) throw conflict("Purchase identity is missing; operator reconciliation is required.");
        return rows.getFirst();
    }

    public void finish(String merchant, String order, String key, String reference) {
        var purchase = lock(merchant, order);
        if (reference != null && purchase.reference() != null && !reference.equals(purchase.reference()))
            throw conflict("Purchase already has another financial reference.");
        int changed = jdbc.update("""
                UPDATE payment_purchase_attempts SET state = ?, bank_reference = ?
                WHERE merchant_id = ? AND order_id = ? AND idempotency_key = ?
                  AND (state = 'PENDING' OR (state = 'SUCCEEDED' AND bank_reference = ?))
                """, reference == null ? "DECLINED" : "SUCCEEDED", reference, merchant, order, key, reference);
        if (changed != 1) throw conflict("Payment attempt cannot change its terminal outcome.");
        if (reference != null) jdbc.update("""
                UPDATE payment_purchases SET bank_reference = ? WHERE merchant_id = ? AND order_id = ?
                """, reference, merchant, order);
    }

    public void requireKeyOwner(MerchantIdentity merchant, String key, String order, String account) {
        var owners = jdbc.query("""
                SELECT merchant_id, order_id, merchant_account_number FROM payment_purchase_attempts
                WHERE idempotency_key = ?
                """, (rs, n) -> new String[]{rs.getString(1), rs.getString(2), rs.getString(3)}, key);
        if (owners.isEmpty()) throw conflict("Payment identity is missing or requires legacy assignment.");
        var owner = owners.getFirst();
        merchant.requireOwner(owner[0]);
        if (!owner[1].equals(order) || !owner[2].equals(account)) throw conflict("Payment data does not match.");
    }

    public void requireReferenceOwner(MerchantIdentity merchant, String reference) {
        var owners = jdbc.query("SELECT merchant_id FROM payment_purchase_attempts WHERE bank_reference = ?",
                (rs, n) -> rs.getString(1), reference);
        if (owners.isEmpty()) throw conflict("Payment identity is missing or requires legacy assignment.");
        merchant.requireOwner(owners.getFirst());
    }

    public AttemptSnapshot snapshot(String key) {
        var rows=jdbc.query("SELECT state,charged_amount,charged_currency FROM payment_purchase_attempts WHERE idempotency_key=?",
                (rs,n)->new AttemptSnapshot(rs.getString(1),rs.getBigDecimal(2),rs.getString(3)),key);
        if(rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Payment attempt not found.");
        return rows.getFirst();
    }

    public BigDecimal amount(String merchant,String order) {
        return jdbc.queryForObject("SELECT amount FROM payment_purchases WHERE merchant_id=? AND order_id=?",
                BigDecimal.class,merchant,order);
    }

    private static ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }
    public record Purchase(BigDecimal amount, String currency, String activeKey, String reference) { }
    public record AttemptSnapshot(String state,BigDecimal chargedAmount,String chargedCurrency) { }
}
