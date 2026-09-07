package com.chaeyeongmin.van_sim.transaction.reversal.tcp.exception;

/**
 * Reversal TCP 전문이 유효하지 않거나 직렬화할 수 없을 때 발생하는 예외다.
 */
public class ReversalTcpMessageException extends RuntimeException {

    public ReversalTcpMessageException(String message) {
        super(message);
    }

    public ReversalTcpMessageException(String message, Throwable cause) {
        super(message, cause);
    }
}
