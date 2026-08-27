package com.chaeyeongmin.van_sim.transaction.tcp.exception;

/**
 * VAN TCP 요청의 messageType을 판별할 수 없거나 지원하지 않을 때 발생하는 예외다.
 */
public class VanTcpMessageException extends RuntimeException {

    public VanTcpMessageException(String message) {
        super(message);
    }

    public VanTcpMessageException(String message, Throwable cause) {
        super(message, cause);
    }
}
