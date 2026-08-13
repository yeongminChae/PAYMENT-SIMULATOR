package com.chaeyeongmin.van_sim.transaction.approval.service.exception;

/**
 * 동일한 거래키로 이미 처리된 승인 원장과 재요청 내용이 다를 때 발생하는 예외다.
 */
public class ApprovalRequestConflictException extends RuntimeException {

    public ApprovalRequestConflictException(String message) {
        super(message);
    }
}
