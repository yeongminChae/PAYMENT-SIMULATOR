package com.chaeyeongmin.van_sim.ledger.reversal.repository;

import com.chaeyeongmin.van_sim.ledger.reversal.entity.VanReversal;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * VAN reversal 원장을 조회하고 저장하는 JPA Repository다.
 *
 * <p>
 * save는 JpaRepository 기본 메서드를 사용한다.
 * reversal service는 이 repository와 van_approval row lock을 조합해 같은 원승인에 reversal row가
 * 하나만 생기도록 제어한다.
 */
public interface VanReversalRepository extends JpaRepository<VanReversal, Long> {

    /**
     * 같은 reversal 거래번호 재요청 여부를 확인한다.
     *
     * <p>
     * 같은 payload면 replay, 다른 payload면 ReversalRequestConflictException으로 처리된다.
     */
    Optional<VanReversal> findByReversalPosTrx(String reversalPosTrx);

    /**
     * 같은 원승인에 이미 reversal 원장이 있는지 확인한다.
     *
     * <p>
     * 서로 다른 reversalPosTrx가 같은 original을 대상으로 들어온 경우,
     * 기존 row를 찾아 ALREADY_REVERSED 또는 기존 decline 결과로 재응답하는 데 사용한다.
     */
    Optional<VanReversal> findByOriginalPosTrxAndOriginalAttemptSeq(
            String originalPosTrx,
            int originalAttemptSeq
    );
}
