package org.demo.bankdemo.admin;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AdminStatusChangeRepository extends JpaRepository<AdminStatusChange, Long> {

    List<AdminStatusChange> findAllByOrderByCreatedAtDesc(Pageable pageable);
}
