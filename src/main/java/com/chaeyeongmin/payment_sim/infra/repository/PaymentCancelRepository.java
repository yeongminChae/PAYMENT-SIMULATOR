package com.chaeyeongmin.payment_sim.infra.repository;

import com.chaeyeongmin.payment_sim.domain.model.PaymentAttempt;
import com.chaeyeongmin.payment_sim.domain.model.PaymentCancel;

import java.util.Optional;

public interface PaymentCancelRepository {

    Optional<PaymentAttempt> findOriginalAttempt(String posTrx, int attemptSeq);

    Optional<PaymentCancel> findByOriginalPosTrxAndOriginalAttemptSeq(
            String originalPosTrx,
            int originalAttemptSeq
    );
}