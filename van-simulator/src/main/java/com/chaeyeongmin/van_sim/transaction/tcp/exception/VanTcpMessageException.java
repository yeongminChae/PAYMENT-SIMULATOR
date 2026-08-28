package com.chaeyeongmin.van_sim.transaction.tcp.exception;

/**
 * VAN TCP 요청의 messageType을 판별할 수 없거나 지원하지 않을 때 발생하는 예외다.
 * <p>
 * dispatcher 단계에서 던지는 공통 TCP 라우팅 예외다.
 * 이 예외가 발생했다는 것은 아직 APPROVAL/INQUIRY 개별 핸들러까지 요청이 도달하지 못했다는 뜻이다.
 * 개별 전문의 세부 validation 실패는 각 핸들러 전용 예외가 담당한다.
 */
public class VanTcpMessageException extends RuntimeException {

    public VanTcpMessageException(String message) {
        super(message);
    }

    public VanTcpMessageException(String message, Throwable cause) {
        super(message, cause);
    }
}
