package org.demo.bankdemo.profile;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface TotpRecoveryCodeRepository extends JpaRepository<TotpRecoveryCode, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select code from TotpRecoveryCode code where code.user.id = :userId and code.usedAt is null")
    List<TotpRecoveryCode> findUnusedForUpdate(@Param("userId") Long userId);

    void deleteByUser_Id(Long userId);
}
