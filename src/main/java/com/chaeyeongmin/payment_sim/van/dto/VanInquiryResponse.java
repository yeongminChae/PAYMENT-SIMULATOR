package com.chaeyeongmin.payment_sim.van.dto;


import com.chaeyeongmin.payment_sim.api.payment.dto.enums.PaymentFinalStatus;
import lombok.Builder;

import java.time.LocalDateTime;

/**
 * [VAN Response] VAN 조회 응답
 *
 * finalStatus:
 * - APPROVED        : 조회로 승인 확정
 * - DECLINED        : 조회로 거절 확정
 * - UNKNOWN_TIMEOUT : 여전히 미확정
 */
@Builder
public record VanInquiryResponse(
        String posTrx,
        int attemptSeq,
        PaymentFinalStatus finalStatus,
        String approvalNo,
        String declineCode,
        String vanTrxId,
        String message,
        LocalDateTime respondedAt
) {
}
