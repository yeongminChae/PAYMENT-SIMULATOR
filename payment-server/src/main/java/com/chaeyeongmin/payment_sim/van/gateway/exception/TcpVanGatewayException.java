package com.chaeyeongmin.payment_sim.van.gateway.exception;

/**
 * TCP VAN Gateway 어댑터에서 발생한 전문 변환/응답 검증 실패를 표현한다.
 */
public class TcpVanGatewayException extends RuntimeException {

    public TcpVanGatewayException(String message) {
        super(message);
    }

    public TcpVanGatewayException(String message, Throwable cause) {
        super(message, cause);
    }
}
