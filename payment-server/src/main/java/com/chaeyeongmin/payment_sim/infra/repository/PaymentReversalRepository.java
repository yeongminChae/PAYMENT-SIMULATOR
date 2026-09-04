package com.chaeyeongmin.payment_sim.infra.repository;

import com.chaeyeongmin.payment_sim.domain.model.PaymentReversal;
import com.chaeyeongmin.payment_sim.infra.repository.dto.ReversalInsertParam;
import com.chaeyeongmin.payment_sim.infra.repository.dto.ReversalResultUpdateParam;

import java.util.Optional;

public interface PaymentReversalRepository {

    Optional<PaymentReversal> findByReversalPosTrx(String reversalPosTrx);

    Optional<PaymentReversal> findByOriginalPosTrxAndOriginalAttemptSeq(
            String originalPosTrx,
            int originalAttemptSeq
    );

    Optional<PaymentReversal> insertPendingReversal(ReversalInsertParam param);

    Optional<PaymentReversal> updateReversalResult(ReversalResultUpdateParam param);

    int deletePendingReversal(String reversalPosTrx, String originalPosTrx, int originalAttemptSeq);
}
