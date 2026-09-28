package com.chaeyeongmin.payment_sim.payment.application.event.service.impl;

import com.chaeyeongmin.payment_sim.payment.application.event.service.PaymentEventLogService;
import com.chaeyeongmin.payment_sim.infra.repository.PaymentEventLogRepository;
import com.chaeyeongmin.payment_sim.infra.repository.dto.PaymentEventLogInsertParam;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class PaymentEventLogServiceImpl implements PaymentEventLogService {

    private final PaymentEventLogRepository repository;

    @Override
    public void record(PaymentEventLogInsertParam event) {
        repository.insert(event);
    }
}
