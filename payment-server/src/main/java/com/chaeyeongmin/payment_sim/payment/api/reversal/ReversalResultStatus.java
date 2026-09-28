package com.chaeyeongmin.payment_sim.payment.api.reversal;

/**
 * Reversal API 응답 의미.
 */
public enum ReversalResultStatus {
    REVERSED,
    REVERSAL_DECLINED,
    ALREADY_REVERSED,
    REVERSAL_NOT_ALLOWED,
    RETRY_LATER
}
