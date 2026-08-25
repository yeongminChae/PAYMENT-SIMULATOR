package com.chaeyeongmin.payment_sim.van.client.tcp.exception;

/**
 * VAN TCP transport 송수신 실패를 표현한다.
 */
public class VanTcpClientException extends RuntimeException {

    public VanTcpClientException(String message) {
        super(message);
    }

    public VanTcpClientException(String message, Throwable cause) {
        super(message, cause);
    }

}
