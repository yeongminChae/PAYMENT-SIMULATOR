package com.chaeyeongmin.payment_sim.infra.repository.impl;

import com.chaeyeongmin.payment_sim.infra.mybatis.mapper.PaymentEventLogMapper;
import com.chaeyeongmin.payment_sim.infra.repository.PaymentEventLogRepository;
import com.chaeyeongmin.payment_sim.infra.repository.dto.PaymentEventLogInsertParam;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/**
 * PAYMENT_EVENT_LOG 저장소 구현.
 */
@Repository
@RequiredArgsConstructor
public class PaymentEventLogRepositoryImpl implements PaymentEventLogRepository {

    private final PaymentEventLogMapper mapper;

    @Override
    public void insert(PaymentEventLogInsertParam event) {
        mapper.insert(event);
    }

}
