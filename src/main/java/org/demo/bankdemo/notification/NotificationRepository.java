package org.demo.bankdemo.notification;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

    List<Notification> findByUserEmailOrderByCreatedAtDesc(String email, Pageable pageable);

    Optional<Notification> findByIdAndUserEmail(Long id, String email);

    long countByUserEmailAndReadFalse(String email);
}
