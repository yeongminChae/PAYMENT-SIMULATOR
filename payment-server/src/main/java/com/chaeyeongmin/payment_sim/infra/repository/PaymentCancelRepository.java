package com.chaeyeongmin.payment_sim.infra.repository;

import com.chaeyeongmin.payment_sim.domain.model.PaymentCancel;
import com.chaeyeongmin.payment_sim.infra.repository.dto.CancelInsertParam;
import com.chaeyeongmin.payment_sim.infra.repository.dto.CancelResultUpdateParam;

import java.util.Optional;

/**
 * PAYMENT_CANCEL 영속성 포트.
 *
 * <p>
 * updateCancelResult와 updateUnknownTimeoutToFinal은 대상 상태가 다르므로 분리한다.
 * 일반 VAN cancel 결과는 PENDING row만, VAN Inquiry(CANCEL) 복구 결과는 UNKNOWN_TIMEOUT row만
 * 갱신해야 서로 다른 business flow의 WHERE 조건이 넓어지지 않는다.
 */
public interface PaymentCancelRepository {

    Optional<PaymentCancel> findByPosTrx(String posTrx);

    Optional<PaymentCancel> findByOriginalPosTrxAndOriginalAttemptSeq(
            String originalPosTrx,
            int originalAttemptSeq
    );

    Optional<PaymentCancel> insertPendingCancel(CancelInsertParam param);

    Optional<PaymentCancel> updateCancelResult(CancelResultUpdateParam param);

    /**
     * UNKNOWN_TIMEOUT으로 남은 취소 row를 VAN Inquiry(CANCEL)의 확정 결과로 갱신한다.
     */
    Optional<PaymentCancel> updateUnknownTimeoutToFinal(CancelResultUpdateParam param);

}
