package com.chaeyeongmin.payment_sim.van.client.dto;

import com.chaeyeongmin.payment_sim.domain.policy.CancelStatus;
import com.chaeyeongmin.payment_sim.van.client.dto.enums.VanDeclineCode;
import lombok.Builder;

import java.time.LocalDateTime;

/**
 * [VAN Response] 취소 응답 DTO
 *
 * <p>
 * C6: VAN 시뮬레이터 -> 결제서버 취소 응답
 *
 * <p>
 * cancelStatus:
 * - CANCELLED        : 취소 성공
 * - CANCEL_DECLINED : 취소 거절
 * - PENDING          : 취소 결과 미확정/타임아웃
 *
 * <p>
 * declineCode:
 * - VAN 계층에서는 VanDeclineCode enum을 유지한다.
 * - DB/API 응답 저장 시 서비스 계층에서 String code로 변환한다.
 */
@Builder
public record VanCancelResponse(
        String posTrx,
        String originalPosTrx,
        int originalAttemptSeq,
        CancelStatus cancelStatus,
        String cancelApprovalNo,
        VanDeclineCode declineCode,
        String vanTrxId,
        String message,
        LocalDateTime respondedAt
) {
}