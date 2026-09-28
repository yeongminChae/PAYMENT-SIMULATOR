package com.chaeyeongmin.payment_sim.payment.application.event;

import com.chaeyeongmin.payment_sim.infra.repository.dto.PaymentEventLogInsertParam;

public record PaymentEventLogRequestedEvent(
        PaymentEventLogInsertParam log
) {
}