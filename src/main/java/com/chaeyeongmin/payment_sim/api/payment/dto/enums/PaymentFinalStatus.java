package com.chaeyeongmin.payment_sim.api.payment.dto.enums;

public enum PaymentFinalStatus {
    PROCESSING,       // 처리중 (DB FINAL_STATUS = NULL 대응)
    APPROVED,
    DECLINED,
    UNKNOWN_TIMEOUT
}
