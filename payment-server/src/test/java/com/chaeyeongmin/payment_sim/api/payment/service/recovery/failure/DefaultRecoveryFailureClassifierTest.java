package com.chaeyeongmin.payment_sim.api.payment.service.recovery.failure;

import com.chaeyeongmin.payment_sim.api.payment.service.recovery.exception.RecoveryInvariantViolationException;
import com.chaeyeongmin.payment_sim.van.gateway.exception.TcpVanGatewayException;
import com.chaeyeongmin.payment_sim.van.gateway.exception.VanGatewayRequestNotSentException;
import com.chaeyeongmin.payment_sim.van.gateway.exception.VanGatewayTimeoutException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultRecoveryFailureClassifierTest {

    private final DefaultRecoveryFailureClassifier classifier = new DefaultRecoveryFailureClassifier();

    @Test
    void requestNotSent_shouldBeRetryable() {
        assertThat(classifier.classify(new VanGatewayRequestNotSentException(new RuntimeException())))
                .isEqualTo(RecoveryFailureType.RETRYABLE);
    }

    @Test
    void timeout_shouldBeRetryable() {
        assertThat(classifier.classify(new VanGatewayTimeoutException(new RuntimeException())))
                .isEqualTo(RecoveryFailureType.RETRYABLE);
    }

    @Test
    void invariantViolation_shouldRequireManualReview() {
        assertThat(classifier.classify(new RecoveryInvariantViolationException("invariant")))
                .isEqualTo(RecoveryFailureType.MANUAL_REVIEW);
    }

    @Test
    void tcpGatewayException_shouldBeUnknown() {
        assertThat(classifier.classify(new TcpVanGatewayException("tcp")))
                .isEqualTo(RecoveryFailureType.UNKNOWN);
    }

    @Test
    void otherRuntimeException_shouldBeUnknown() {
        assertThat(classifier.classify(new RuntimeException("unexpected")))
                .isEqualTo(RecoveryFailureType.UNKNOWN);
    }
}
