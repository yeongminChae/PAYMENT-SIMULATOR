package com.chaeyeongmin.van_sim.ledger.reversal.repository;

import com.chaeyeongmin.van_sim.ledger.reversal.entity.VanReversal;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * VAN reversal 원장을 조회하고 저장하는 JPA Repository다.
 */
public interface VanReversalRepository extends JpaRepository<VanReversal, Long> {

    /**
     * 같은 reversal 거래번호 재요청 여부를 확인한다.
     */
    Optional<VanReversal> findByReversalPosTrx(String reversalPosTrx);

    /**
     * 같은 원승인에 이미 reversal 원장이 있는지 확인한다.
     */
    Optional<VanReversal> findByOriginalPosTrxAndOriginalAttemptSeq(
            String originalPosTrx,
            int originalAttemptSeq
    );
}
