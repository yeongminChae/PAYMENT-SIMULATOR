package com.chaeyeongmin.payment_sim.van.client.tcp.exception;

public class VanTcpResponseTimeoutException extends VanTcpClientException {

    public VanTcpResponseTimeoutException() {
        super("VAN_TCP_RESPONSE_TIMEOUT");
    }

    public VanTcpResponseTimeoutException(Throwable cause) {
        super("VAN_TCP_RESPONSE_TIMEOUT", cause);
    }

}
