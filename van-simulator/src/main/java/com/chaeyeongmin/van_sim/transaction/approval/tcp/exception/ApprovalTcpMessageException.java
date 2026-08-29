package com.chaeyeongmin.van_sim.transaction.approval.tcp.exception;

/**
 * 승인 TCP 전문이 유효하지 않거나 직렬화할 수 없을 때 발생하는 예외다.
 * <p>
 * 이 예외는 승인 업무 결과(APPROVED/DECLINED/UNKNOWN)를 표현하지 않는다.
 * JSON 역직렬화 실패, 필수 protocol field 누락, 응답 직렬화 실패처럼
 * TCP protocol boundary에서 요청 자체를 처리할 수 없을 때만 사용한다.
 */
public class ApprovalTcpMessageException extends RuntimeException {

    /**
     * validation 실패처럼 별도 원인 예외가 없는 protocol 오류에 사용한다.
     */
    public ApprovalTcpMessageException(String message) {
        super(message);
    }

    /**
     * JSON parsing/serialization 실패처럼 하위 예외를 보존해야 하는 오류에 사용한다.
     */
    public ApprovalTcpMessageException(String message, Throwable cause) {
        super(message, cause);
    }

}
