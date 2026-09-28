package com.chaeyeongmin.payment_sim.van.client.dto;

import com.chaeyeongmin.payment_sim.van.client.dto.enums.VanDeclineCode;
import com.chaeyeongmin.payment_sim.van.client.tcp.protocol.inquiry.VanInquiryStatus;
import lombok.Builder;

import java.time.LocalDateTime;

/**
 * [VAN Response] VAN 조회 응답
 *
 * <p>approvalNo, cancelApprovalNo, reversalApprovalNo는 조회 대상과 확정 상태에 대응하는 필드만 사용한다.
 * 거절 상태에서는 대상과 관계없이 declineCode를 사용한다.
 * 이 DTO는 공용 Inquiry 계약을 표현하며 target별 업무 해석은 각 처리 흐름이 담당한다.
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
        String reversalApprovalNo,
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
