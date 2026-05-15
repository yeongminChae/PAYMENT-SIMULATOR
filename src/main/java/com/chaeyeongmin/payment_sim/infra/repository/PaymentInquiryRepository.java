package com.chaeyeongmin.payment_sim.infra.repository;

import com.chaeyeongmin.payment_sim.domain.model.PaymentAttempt;
import com.chaeyeongmin.payment_sim.infra.repository.dto.AttemptResultUpdateParam;
import com.chaeyeongmin.payment_sim.infra.repository.dto.PaymentAttemptUpdatedRow;

import java.util.Optional;

public interface PaymentInquiryRepository {
    Optional<PaymentAttempt> findByPosTrxAndAttemptSeq(String posTrx, int attemptSeq);

    Optional<PaymentAttemptUpdatedRow> updateUnknownToFinal(AttemptResultUpdateParam param);

}
