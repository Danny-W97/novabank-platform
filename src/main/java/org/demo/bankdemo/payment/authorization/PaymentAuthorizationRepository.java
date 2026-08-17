package org.demo.bankdemo.payment.authorization;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

import java.util.Optional;

public interface PaymentAuthorizationRepository extends JpaRepository<PaymentAuthorization, String> {

    Optional<PaymentAuthorization> findByPaymentIdempotencyKey(String paymentIdempotencyKey);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select authorization from PaymentAuthorization authorization where authorization.id = :id")
    Optional<PaymentAuthorization> findByIdForUpdate(@Param("id") String id);
}
