package com.chaeyeongmin.payment_sim.api.payment.service.recovery.policy;


import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/** 기본 재시도 간격(1분, 5분, 30분)과 최대 재시도 횟수 3회를 적용한다. */
@Component
public class DefaultRecoveryRetryPolicy implements RecoveryRetryPolicy {

    private static final int MAX_RETRY_COUNT = 3;

    @Override
    public boolean canRetry(int retryCount) {
        if (retryCount < 0) {
            // 음수는 저장된 task 데이터나 호출 흐름이 잘못된 경우이므로 즉시 거부한다.
            throw new IllegalArgumentException("retryCount must not be negative: " + retryCount);
        }

        return retryCount < MAX_RETRY_COUNT;
    }

    @Override
    public LocalDateTime nextRetryAt(int retryCount, LocalDateTime now) {
        if (canRetry(retryCount) == false) {
            // 재시도 한도를 넘긴 task의 시각을 계산하는 것은 호출 순서가 잘못된 경우다.
            throw new IllegalStateException("Recovery retry exhausted: retryCount=" + retryCount);
        }

        return switch (retryCount) {
            case 0 -> now.plusMinutes(1);
            case 1 -> now.plusMinutes(5);
            case 2 -> now.plusMinutes(30);
            // canRetry를 먼저 확인하므로 정상 흐름에서는 도달하지 않는다.
            default -> throw new IllegalStateException("Unsupported retryCount: " + retryCount);
        };

    }

}
