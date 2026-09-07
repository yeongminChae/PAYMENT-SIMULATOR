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

    /**
     * 승인 원장을 POS 거래번호와 attemptSeq로 조회한다.
     */
    Optional<VanApproval> findByPosTrxAndAttemptSeq(String posTrx, int attemptSeq);

    /**
     * VAN 내부 승인 거래번호로 원승인을 찾는다.
     */
    Optional<VanApproval> findByVanTrxId(String vanTrxId);

    /**
     * 같은 원승인에 대한 취소 요청들을 직렬화하기 위한 lock 조회다.
     *
     * <p>
     * PostgreSQL에서는 Hibernate가 이 조회를 SELECT ... FOR UPDATE로 실행한다.
     * CancelServiceImpl은 이 lock을 잡은 뒤 cancelPosTrx와 original 기준 cancel row를 다시 조회해,
     * lock 대기 중 먼저 commit된 취소를 반영한다.
     */
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
