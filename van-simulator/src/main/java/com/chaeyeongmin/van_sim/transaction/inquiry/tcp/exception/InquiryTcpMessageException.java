package com.chaeyeongmin.van_sim.transaction.inquiry.tcp.exception;

/**
 * Inquiry TCP 전문이 유효하지 않거나 직렬화할 수 없을 때 발생하는 예외다.
 */
public class InquiryTcpMessageException extends RuntimeException {

    public InquiryTcpMessageException(String message) {
        super(message);
    }

    public InquiryTcpMessageException(String message, Throwable cause) {
        super(message, cause);
    }
}
