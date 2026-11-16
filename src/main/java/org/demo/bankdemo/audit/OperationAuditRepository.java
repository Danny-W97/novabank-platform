package org.demo.bankdemo.audit;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface OperationAuditRepository extends JpaRepository<OperationAudit, Long> {

    List<OperationAudit> findAllByOrderByCreatedAtDesc(Pageable pageable);

    List<OperationAudit> findBySuccessFalseAndAdminActionFalseOrderByCreatedAtDesc(Pageable pageable);

    List<OperationAudit> findByAdminActionTrueOrderByCreatedAtDesc(Pageable pageable);
}
