package com.chaeyeongmin.payment_sim.payment.application.event.service;

import com.chaeyeongmin.payment_sim.infra.repository.dto.PaymentEventLogInsertParam;

/**
 * PAYMENT_EVENT_LOG 기록 전용 서비스.
 */
public interface PaymentEventLogService {
    void record(PaymentEventLogInsertParam event);
}
