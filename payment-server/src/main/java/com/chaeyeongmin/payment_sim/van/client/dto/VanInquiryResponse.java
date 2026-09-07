package com.chaeyeongmin.payment_sim.van.client.dto;

import com.chaeyeongmin.payment_sim.van.client.dto.enums.VanDeclineCode;
import com.chaeyeongmin.payment_sim.van.client.tcp.protocol.inquiry.VanInquiryStatus;
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
        VanInquiryTargetType targetType,
        String targetTrxNo,
        Integer targetAttemptSeq,
        VanInquiryResultCode resultCode,
        VanInquiryStatus status,
        String vanTrxId,
        String approvalNo,
        String cancelApprovalNo,
        VanDeclineCode declineCode,
        String message,
        LocalDateTime respondedAt
) {

    public String posTrx() {
        return targetTrxNo;
    }

    public int attemptSeq() {
        return targetAttemptSeq == null ? 0 : targetAttemptSeq;
    }
}
