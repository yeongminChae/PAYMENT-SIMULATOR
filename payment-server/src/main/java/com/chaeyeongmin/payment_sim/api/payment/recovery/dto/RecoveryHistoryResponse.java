package com.chaeyeongmin.payment_sim.api.payment.recovery.dto;

import java.time.LocalDateTime;

/**
 * Recovery Task를 한 번 실행한 기록을 관리자 API에 보여주는 응답이다.
 *
 * @param historyId 실행 이력 ID
 * @param tryNo 해당 Task를 실제 실행한 순번
 * @param result 실행 결과; 아직 끝나지 않았으면 null
 * @param errorCode 실패 또는 운영자 확인 사유; 없으면 null
 * @param startedAt 실행 시작 시각
 * @param finishedAt 실행 종료 시각; 아직 끝나지 않았으면 null
 */
public record RecoveryHistoryResponse(
        Long historyId,
        int tryNo,
        String result,
        String errorCode,
        LocalDateTime startedAt,
        LocalDateTime finishedAt
) {
}
