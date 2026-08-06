package com.chaeyeongmin.payment_sim.infra.repository.impl;

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
    public Optional<PaymentAttemptUpdatedRow> updateUnknownToFinal(AttemptResultUpdateParam param) {
        return mapper.updateUnknownToFinal(param);
    }

}
