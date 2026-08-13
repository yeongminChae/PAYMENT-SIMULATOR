package com.chaeyeongmin.van_sim.transaction.approval.tcp.exception;

/**
 * 승인 TCP 전문의 역직렬화 또는 직렬화에 실패했을 때 발생하는 예외다.
 */
public class ApprovalTcpMessageException extends RuntimeException {

    public ApprovalTcpMessageException(String message, Throwable cause) {
        super(message, cause);
    }

}
