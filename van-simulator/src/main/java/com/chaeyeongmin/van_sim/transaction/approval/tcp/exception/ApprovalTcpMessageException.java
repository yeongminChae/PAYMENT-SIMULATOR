package com.chaeyeongmin.van_sim.transaction.approval.tcp.exception;

/**
 * 승인 TCP 전문이 유효하지 않거나 직렬화할 수 없을 때 발생하는 예외다.
 */
public class ApprovalTcpMessageException extends RuntimeException {

    public ApprovalTcpMessageException(String message) {
        super(message);
    }

    public ApprovalTcpMessageException(String message, Throwable cause) {
        super(message, cause);
    }

}
