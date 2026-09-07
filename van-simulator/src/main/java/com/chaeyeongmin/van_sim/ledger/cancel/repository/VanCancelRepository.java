package com.chaeyeongmin.van_sim.ledger.cancel.repository;

import com.chaeyeongmin.van_sim.ledger.cancel.entity.VanCancel;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * VAN 취소 원장을 조회하고 저장하는 JPA Repository다.
 */
public interface VanCancelRepository extends JpaRepository<VanCancel, Long> {

    /**
     * 같은 취소 거래번호 재요청 여부를 확인한다.
     *
     * <p>
     * CancelServiceImpl은 이 조회를 원승인 lock 전후로 수행한다.
     * lock 대기 중 먼저 commit된 같은 cancelPosTrx row까지 반영해야 하기 때문이다.
     */
    Optional<VanCancel> findByCancelPosTrx(String cancelPosTrx);

    /**
     * VAN 내부 취소 거래번호로 원장을 찾는다.
     */
    Optional<VanCancel> findByVanCancelTrxId(String vanCancelTrxId);

    /**
     * 같은 원승인이 이미 취소됐는지 확인한다.
     *
     * <p>
     * 서로 다른 cancelPosTrx가 같은 원승인을 취소하려는 경우, 먼저 commit된 row를 찾아
     * 후속 요청을 ALREADY_CANCELLED 응답으로 전환하는 데 사용한다.
     */
    Optional<VanCancel> findByOriginalPosTrxAndOriginalAttemptSeq(
            String originalPosTrx,
            int originalAttemptSeq
    );
}
