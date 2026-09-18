package com.chaeyeongmin.payment_sim.api.payment.service.recovery.failure;

/** Handler 실행 중 발생한 예외를 Worker가 처리할 수 있는 실패 종류로 분류한다. */
public interface RecoveryFailureClassifier {

    /**
     * 예외가 자동 재시도 대상인지, 운영자 확인 대상인지, 그대로 전파할 대상인지 판단한다.
     */
    RecoveryFailureType classify(Throwable throwable);
}
