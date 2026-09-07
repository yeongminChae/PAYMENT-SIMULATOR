package com.chaeyeongmin.van_sim.transaction.cancel.tcp.exception;

public class CancelTcpMessageException extends RuntimeException {
    /**
     * validation 실패처럼 별도 원인 예외가 없는 protocol 오류에 사용한다.
     */
    public CancelTcpMessageException(String message) {
        super(message);
    }

    /**
     * JSON parsing/serialization 실패처럼 하위 예외를 보존해야 하는 오류에 사용한다.
     */
    public CancelTcpMessageException(String message, Throwable cause) {
        super(message, cause);
    }

}
