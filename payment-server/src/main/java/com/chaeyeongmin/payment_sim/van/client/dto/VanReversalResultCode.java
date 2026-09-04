package com.chaeyeongmin.payment_sim.van.client.dto;

/**
 * Payment 업무 계층이 보는 VAN reversal 결과 코드.
 */
public enum VanReversalResultCode {
    SUCCESS,
    ALREADY_REVERSED,
    ORIGINAL_NOT_FOUND,
    ORIGINAL_NOT_REVERSIBLE,
    ORIGINAL_MISMATCH
}
