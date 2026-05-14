package com.chaeyeongmin.payment_sim.infra.repository;

import com.chaeyeongmin.payment_sim.domain.model.PaymentAttempt;

import java.util.Optional;

public interface PaymentInquiryRepository {
    Optional<PaymentAttempt> findByPosTrxAndAttemptSeq(String posTrx, int attemptSeq);
}
