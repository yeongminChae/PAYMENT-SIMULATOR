package com.chaeyeongmin.payment_sim.van.client.tcp.protocol.reversal;

/**
 * TCP Reversal 응답의 업무 결과 코드다.
 */
public enum VanReversalTcpResultCode {
    SUCCESS,
    ALREADY_REVERSED,
    ORIGINAL_NOT_FOUND,
    ORIGINAL_NOT_REVERSIBLE,
    ORIGINAL_MISMATCH
}
