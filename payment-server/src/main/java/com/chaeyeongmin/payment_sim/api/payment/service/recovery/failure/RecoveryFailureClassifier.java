package com.chaeyeongmin.payment_sim.api.payment.service.recovery.failure;

public interface RecoveryFailureClassifier {
    RecoveryFailureType classify(Throwable throwable);
}
