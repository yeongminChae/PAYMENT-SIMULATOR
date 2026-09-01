package com.chaeyeongmin.van_sim.ledger.cancel.repository;

import com.chaeyeongmin.van_sim.ledger.cancel.entity.VanCancel;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * VAN 취소 원장을 조회하고 저장하는 JPA Repository다.
 */
public interface VanCancelRepository extends JpaRepository<VanCancel, Long> {

    Optional<VanCancel> findByCancelPosTrx(String cancelPosTrx);

    Optional<VanCancel> findByVanCancelTrxId(String vanCancelTrxId);

    Optional<VanCancel> findByOriginalPosTrxAndOriginalAttemptSeq(
            String originalPosTrx,
            int originalAttemptSeq
    );
}
