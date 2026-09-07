package com.chaeyeongmin.van_sim.transaction.cancel.service.exception;

/**
 * 동일한 취소 거래키로 이미 처리된 취소 원장과 재요청 내용이 다를 때 발생하는 예외다.
 */
public class CancelRequestConflictException extends RuntimeException {

    public CancelRequestConflictException(String message) {
        super(message);
    }
}
