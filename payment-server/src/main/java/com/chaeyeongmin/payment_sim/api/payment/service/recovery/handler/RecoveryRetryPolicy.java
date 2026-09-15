package com.chaeyeongmin.payment_sim.api.payment.service.recovery.handler;

import java.time.LocalDateTime;

public interface RecoveryRetryPolicy {

    /**
     * 현재 retryCount 기준으로 추가 자동 재시도가 가능한지 판단한다.
     *
     * retryCount 의미:
     * "이 task가 과거에 RETRY_WAIT로 전환된 횟수"
     *
     * 예:
     * 0 -> 최초 실행 중
     * 1 -> 1회 retry 예정/수행
     * 2 -> 2회 retry
     * 3 -> 마지막 retry
     */
    boolean canRetry(int retryCount);

    /**
     * 현재 retryCount 상태에서 이번 실행이 STILL_UNRESOLVED로 끝났을 때
     * 다음 재시도 가능 시각을 계산한다.
     *
     * retryCount = 0 -> +1분
     * retryCount = 1 -> +5분
     * retryCount = 2 -> +30분
     *
     * retryCount >= 3이면 더 이상 호출하면 안 된다.
     */
    LocalDateTime nextRetryAt(int retryCount, LocalDateTime now);

}
