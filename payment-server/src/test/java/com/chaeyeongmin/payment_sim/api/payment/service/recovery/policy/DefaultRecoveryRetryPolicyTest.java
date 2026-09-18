package com.chaeyeongmin.payment_sim.api.payment.service.recovery.policy;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultRecoveryRetryPolicyTest {

    private final DefaultRecoveryRetryPolicy policy = new DefaultRecoveryRetryPolicy();

    @ParameterizedTest
    @CsvSource({
            "0, true",
            "1, true",
            "2, true",
            "3, false"
    })
    void canRetry_returnsExpectedResult(int retryCount, boolean expected) {
        assertThat(policy.canRetry(retryCount)).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({
            "0, 1",
            "1, 5",
            "2, 30"
    })
    void nextRetryAt_returnsExpectedBackoff(int retryCount, long expectedMinutes) {
        LocalDateTime now = LocalDateTime.of(2026, 9, 16, 16, 0);

        assertThat(policy.nextRetryAt(retryCount, now)).isEqualTo(now.plusMinutes(expectedMinutes));
    }
}
