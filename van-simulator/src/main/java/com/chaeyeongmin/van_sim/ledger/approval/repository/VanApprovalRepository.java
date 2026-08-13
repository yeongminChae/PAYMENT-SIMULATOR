package com.chaeyeongmin.van_sim.ledger.approval.repository;

import com.chaeyeongmin.van_sim.ledger.approval.entity.VanApproval;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * VAN 승인 원장을 조회하고 저장하는 JPA Repository다.
 */
public interface VanApprovalRepository extends JpaRepository<VanApproval, Long> {

    Optional<VanApproval> findByPosTrxAndAttemptSeq(String posTrx, int attemptSeq);

    Optional<VanApproval> findByVanTrxId(String vanTrxId);
}
