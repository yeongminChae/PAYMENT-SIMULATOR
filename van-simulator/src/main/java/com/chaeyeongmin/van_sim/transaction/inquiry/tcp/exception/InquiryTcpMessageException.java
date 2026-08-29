package com.chaeyeongmin.van_sim.transaction.inquiry.tcp.exception;

/**
 * Inquiry TCP 전문이 유효하지 않거나 직렬화할 수 없을 때 발생하는 예외다.
 * <p>
 * JSON 역직렬화 실패, 필수 protocol field 누락, 응답 직렬화 실패처럼
 * Inquiry TCP protocol boundary 안에서 발생한 문제를 표현한다.
 * VAN 승인 원장 조회 결과가 APPROVED/DECLINED/UNKNOWN인 것과는 별개로,
 * 이 예외는 "전문 자체를 정상 처리할 수 없음"을 의미한다.
 */
public class InquiryTcpMessageException extends RuntimeException {

    public InquiryTcpMessageException(String message) {
        super(message);
    }

    public InquiryTcpMessageException(String message, Throwable cause) {
        super(message, cause);
    }
}
