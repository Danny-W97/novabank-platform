package org.demo.shopapp.order;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

final class PaymentFixtures {
    static PaymentAttempt attempt(String provider) {
        return new PaymentAttempt(provider+"-attempt",7L,"merchant",provider,"key","UNKNOWN","state",BigDecimal.TEN,"PLN",
                "BANK".equals(provider)?"auth":null,null,"STRIPE".equals(provider)?"session":null,null,null,null,Instant.EPOCH,null,0);
    }
    static OrderRepository.OrderRow order(OrderStatus status) {
        return new OrderRepository.OrderRow(7L,"order","customer",null,"22222222222222222222222222",BigDecimal.TEN,"PLN",
                status,"key","auth","state",null,null,List.of());
    }
}
