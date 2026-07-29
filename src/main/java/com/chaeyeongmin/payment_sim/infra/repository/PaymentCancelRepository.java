package com.chaeyeongmin.payment_sim.infra.repository;

import com.chaeyeongmin.payment_sim.domain.model.PaymentCancel;
import com.chaeyeongmin.payment_sim.infra.repository.dto.CancelInsertParam;
import com.chaeyeongmin.payment_sim.infra.repository.dto.CancelResultUpdateParam;

import java.util.Optional;

public interface PaymentCancelRepository {

    Optional<PaymentCancel> findByPosTrx(String posTrx);

    Optional<PaymentCancel> findByOriginalPosTrxAndOriginalAttemptSeq(
            String originalPosTrx,
            int originalAttemptSeq
    );

    Optional<PaymentCancel> insertPendingCancel(CancelInsertParam param);

    Optional<PaymentCancel> updateCancelResult(CancelResultUpdateParam param);

}
