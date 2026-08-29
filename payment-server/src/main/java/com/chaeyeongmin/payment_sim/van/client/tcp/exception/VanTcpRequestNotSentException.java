package com.chaeyeongmin.payment_sim.van.client.tcp.exception;

/**
 * TCP connection을 만들지 못해 request payload 전송 전에 실패한 경우다.
 */
public class VanTcpRequestNotSentException extends VanTcpClientException {

    public VanTcpRequestNotSentException(Throwable cause) {
        super("VAN_TCP_REQUEST_NOT_SENT", cause);
    }
}
