package org.demo.shopapp.order;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Age schedules a guarded cancellation request; it never proves a financial result. */
@Component
public class AbandonedPurchaseJob {
    private final JdbcTemplate jdbc;
    private final PurchaseCancellationService cancellations;
    private final boolean enabled;
    private final int minutes;
    public AbandonedPurchaseJob(JdbcTemplate jdbc,PurchaseCancellationService cancellations,
            @Value("${shop.orders.reconciliation-enabled:true}") boolean enabled,
            @Value("${shop.orders.abandoned-after-minutes:30}") int minutes){
        this.jdbc=jdbc;this.cancellations=cancellations;this.enabled=enabled;this.minutes=Math.max(10,minutes);
    }
    @Scheduled(fixedDelayString="${shop.orders.abandoned-check-ms:60000}",initialDelayString="${shop.orders.abandoned-initial-delay-ms:60000}")
    public void run(){
        if(!enabled)return;
        var candidates=jdbc.queryForList("""
            SELECT o.id,o.customer_id FROM shop_orders o
            WHERE o.status NOT IN ('PAID','CANCELLED') AND o.purchase_id IS NOT NULL AND o.historical_imported_at IS NULL
            AND (o.cancel_requested_at IS NOT NULL OR GREATEST(o.created_at,COALESCE((SELECT max(a.created_at) FROM shop_payment_attempts a WHERE a.order_id=o.id),o.created_at)) < CURRENT_TIMESTAMP - make_interval(mins=>?))
            AND NOT EXISTS(SELECT 1 FROM shop_purchase_cancellation_requests r WHERE r.order_id=o.id AND r.checked_at>CURRENT_TIMESTAMP-INTERVAL '5 minutes')
            ORDER BY o.created_at,o.id LIMIT 25
            """,minutes);
        for(var row:candidates)try{
            long id=((Number)row.get("id")).longValue();
            cancellations.cancel(id,(String)row.get("customer_id"),UUID.nameUUIDFromBytes(("abandoned-purchase:"+id).getBytes(StandardCharsets.UTF_8)),"AUTO_EXPIRY","Upłynął skonfigurowany czas niedokończonego zakupu. Wymagane potwierdzenie braku obciążenia.");
        }catch(RuntimeException failure){org.slf4j.LoggerFactory.getLogger(getClass()).warn("Purchase cancellation requires review; order ID {}.",row.get("id"));}
    }
}
