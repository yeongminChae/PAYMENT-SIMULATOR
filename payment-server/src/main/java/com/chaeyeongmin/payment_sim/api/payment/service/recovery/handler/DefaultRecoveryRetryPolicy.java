package com.chaeyeongmin.payment_sim.api.payment.service.recovery.handler;


import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Component
public class DefaultRecoveryRetryPolicy implements RecoveryRetryPolicy {

    private static final int MAX_RETRY_COUNT = 3;

    @Override
    public boolean canRetry(int retryCount) {
        if (retryCount < 0) {
            // 입력 자체가 잘못됨 → IllegalArgumentException
            throw new IllegalArgumentException("retryCount must not be negative: " + retryCount);
        }

        return retryCount < MAX_RETRY_COUNT;
    }

    @Override
    public LocalDateTime nextRetryAt(int retryCount, LocalDateTime now) {
        if (canRetry(retryCount) == false) {
            // 값은 정상인데, 이미 retry exhausted 상태에서 이 메서드를 호출한 흐름이 잘못됨 → IllegalStateException
            throw new IllegalStateException("Recovery retry exhausted: retryCount=" + retryCount);
        }

        return switch (retryCount) {
            case 0 -> now.plusMinutes(1);
            case 1 -> now.plusMinutes(5);
            case 2 -> now.plusMinutes(30);
            // 값은 정상인데, 이미 retry exhausted 상태에서 이 메서드를 호출한 흐름이 잘못됨 → IllegalStateException
            default -> throw new IllegalStateException("Unsupported retryCount: " + retryCount);
        };

    }

}
