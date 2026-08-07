package com.chaeyeongmin.payment_sim.infra.repository.impl;

import com.chaeyeongmin.payment_sim.infra.mybatis.mapper.PaymentExternalInfoMapper;
import com.chaeyeongmin.payment_sim.infra.repository.PaymentExternalInfoRepository;
import com.chaeyeongmin.payment_sim.infra.repository.dto.PaymentExternalInfoInsertParam;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/**
 * PAYMENT_EXTERNAL_INFO MyBatis 저장소 구현.
 */
@Repository
@RequiredArgsConstructor
public class PaymentExternalInfoRepositoryMyBatis implements PaymentExternalInfoRepository {

    private final PaymentExternalInfoMapper mapper;

    @Override
    public void insert(PaymentExternalInfoInsertParam externalInfo) {
        mapper.insert(externalInfo);
    }
}
