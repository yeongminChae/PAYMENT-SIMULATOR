package com.chaeyeongmin.van_sim.ledger.approval.repository;

import com.chaeyeongmin.van_sim.ledger.approval.entity.VanApproval;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/**
 * VAN 승인 원장을 조회하고 저장하는 JPA Repository다.
 */
public interface VanApprovalRepository extends JpaRepository<VanApproval, Long> {

    Optional<VanApproval> findByPosTrxAndAttemptSeq(String posTrx, int attemptSeq);

    Optional<VanApproval> findByVanTrxId(String vanTrxId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select a
            from VanApproval a
            where a.posTrx = :posTrx
              and a.attemptSeq = :attemptSeq
            """)
    Optional<VanApproval> findByPosTrxAndAttemptSeqForUpdate(
            @Param("posTrx") String posTrx,
            @Param("attemptSeq") int attemptSeq
    );
    
}
