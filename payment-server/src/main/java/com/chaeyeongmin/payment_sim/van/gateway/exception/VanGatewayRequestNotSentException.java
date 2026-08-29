package com.chaeyeongmin.payment_sim.van.gateway.exception;

/**
 * VAN request가 transport connection 생성 실패로 전송되지 않은 경우다.
 */
public class VanGatewayRequestNotSentException extends RuntimeException {

    public VanGatewayRequestNotSentException(Throwable cause) {
        super("VAN_REQUEST_NOT_SENT", cause);
    }
}
