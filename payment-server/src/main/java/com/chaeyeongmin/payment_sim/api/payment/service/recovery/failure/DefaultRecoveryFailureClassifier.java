package com.chaeyeongmin.payment_sim.api.payment.service.recovery.failure;

import com.chaeyeongmin.payment_sim.api.payment.service.recovery.exception.RecoveryInvariantViolationException;
import com.chaeyeongmin.payment_sim.van.gateway.exception.TcpVanGatewayException;
import com.chaeyeongmin.payment_sim.van.gateway.exception.VanGatewayRequestNotSentException;
import com.chaeyeongmin.payment_sim.van.gateway.exception.VanGatewayTimeoutException;
import org.springframework.stereotype.Component;

@Component
public class DefaultRecoveryFailureClassifier implements RecoveryFailureClassifier {

    @Override
    public RecoveryFailureType classify(Throwable throwable) {

        if (throwable instanceof VanGatewayRequestNotSentException || throwable instanceof VanGatewayTimeoutException) {
            return RecoveryFailureType.RETRYABLE;
        }

        if (throwable instanceof RecoveryInvariantViolationException) {
            return RecoveryFailureType.MANUAL_REVIEW;
        }

        return RecoveryFailureType.UNKNOWN;
    }
}
