package com.chaeyeongmin.payment_sim.domain.model;

import java.time.LocalDateTime;

/**
 * Recovery Worker가 task 처리를 한 번 시도한 기록이다.
 *
 * <p>{@code tryNo}는 RETRY_WAIT 횟수인 retryCount와 다르며, 실제 실행을 시작할 때마다 1씩 증가한다.
 * 시작 시에는 result, errorCode, finishedAt을 비워 두며 후속 완료 처리에서 채울 수 있다.
 */
public record RecoveryHistory(
        Long id,
        Long recoveryTaskId,
        int tryNo,
        String result,
        String errorCode,
        LocalDateTime startedAt,
        LocalDateTime finishedAt
) {
}
