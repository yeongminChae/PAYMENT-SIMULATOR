package com.chaeyeongmin.van_sim.transaction.inquiry.service.result;

import com.chaeyeongmin.van_sim.ledger.approval.status.VanApprovalStatus;

import java.time.LocalDateTime;

/**
 * VAN 승인 원장 조회 결과를 상위 계층으로 전달하는 결과 객체다.
 */
public record InquiryResult(
        String vanTrxId,
        String posTrx,
        int attemptSeq,
        VanApprovalStatus status,
        String approvalNo,
        String declineCode,
        LocalDateTime processedAt
) {
}
