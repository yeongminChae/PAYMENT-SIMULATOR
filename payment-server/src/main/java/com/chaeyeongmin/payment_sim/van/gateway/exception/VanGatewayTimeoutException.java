package com.chaeyeongmin.payment_sim.van.gateway.exception;

public class VanGatewayTimeoutException extends RuntimeException {

    public VanGatewayTimeoutException(Throwable cause) {
        super("VAN_APPROVAL_RESPONSE_TIMEOUT", cause);
    }

}
