package org.demo.bankdemo.audit;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface LoginHistoryRepository extends JpaRepository<LoginHistory, Long> {

    List<LoginHistory> findTop50ByEmailOrderByCreatedAtDesc(String email);

    List<LoginHistory> findTop50ByEmailAndSuccessfulTrueOrderByCreatedAtDesc(String email);
}
