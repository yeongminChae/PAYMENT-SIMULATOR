package com.chaeyeongmin.payment_sim.infra.repository.impl;

import com.chaeyeongmin.payment_sim.domain.model.PaymentAttempt;
import com.chaeyeongmin.payment_sim.infra.mybatis.mapper.PaymentInquiryMapper;
import com.chaeyeongmin.payment_sim.infra.repository.PaymentInquiryRepository;
import com.chaeyeongmin.payment_sim.infra.repository.dto.AttemptResultUpdateParam;
import com.chaeyeongmin.payment_sim.infra.repository.dto.PaymentAttemptUpdatedRow;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class PaymentInquiryRepositoryImpl implements PaymentInquiryRepository {

    private final PaymentInquiryMapper mapper;

    @Override
    public Optional<PaymentAttempt> findByPosTrxAndAttemptSeq(String posTrx, int attemptSeq) {
        return mapper.findByPosTrxAndAttemptSeq(posTrx, attemptSeq);
    }

    @Override
    public Optional<PaymentAttemptUpdatedRow> updateUnknownToFinal(AttemptResultUpdateParam param) {
        return mapper.updateUnknownToFinal(param);
    }

}
