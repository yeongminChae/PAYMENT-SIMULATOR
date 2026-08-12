package com.chaeyeongmin.van_sim.ledger.approval.repository;

import com.chaeyeongmin.van_sim.ledger.approval.entity.VanApproval;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface VanApprovalRepository extends JpaRepository<VanApproval, Long> {

    Optional<VanApproval> findByPosTrxAndAttemptSeq(String posTrx, int attemptSeq);

    Optional<VanApproval> findByVanTrxId(String vanTrxId);
}
