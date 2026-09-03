package com.chaeyeongmin.van_sim.transaction.reversal.service.exception;

/**
 * 같은 reversalPosTrx가 서로 다른 payload로 재사용된 경우의 충돌 예외다.
 */
public class ReversalRequestConflictException extends RuntimeException {

    public ReversalRequestConflictException(String message) {
        super(message);
    }
}
