package com.chaeyeongmin.payment_sim.infra.repository;

import com.chaeyeongmin.payment_sim.infra.repository.dto.AttemptResultUpdateParam;
import com.chaeyeongmin.payment_sim.infra.repository.dto.PaymentAttemptUpdatedRow;

import java.util.Optional;

public interface PaymentInquiryRepository {
    Optional<PaymentAttemptUpdatedRow> updateUnknownToFinal(AttemptResultUpdateParam param);

}
