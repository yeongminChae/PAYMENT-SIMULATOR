package com.chaeyeongmin.payment_sim.api.payment.service;

import com.chaeyeongmin.payment_sim.infra.repository.PaymentEventLogRepository;
import com.chaeyeongmin.payment_sim.infra.repository.dto.PaymentEventLogInsertParam;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * PAYMENT_EVENT_LOG 기록 전용 서비스.
 */
@Service
@RequiredArgsConstructor
public class PaymentEventLogService {

    private final PaymentEventLogRepository repository;

    public void record(PaymentEventLogInsertParam event) {
        repository.insert(event);
    }
}
