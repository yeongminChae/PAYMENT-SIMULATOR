package com.chaeyeongmin.payment_sim.api.payment.event;

import com.chaeyeongmin.payment_sim.infra.repository.dto.PaymentEventLogInsertParam;

public record PaymentEventLogRequestedEvent(
        PaymentEventLogInsertParam log
) {
}