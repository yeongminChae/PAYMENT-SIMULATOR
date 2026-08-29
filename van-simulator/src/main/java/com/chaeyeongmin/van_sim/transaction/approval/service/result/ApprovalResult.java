package com.chaeyeongmin.van_sim.transaction.approval.service.result;

import com.chaeyeongmin.van_sim.ledger.approval.status.VanApprovalStatus;

import java.time.LocalDateTime;

/**
 * 승인 서비스 처리 결과를 상위 계층으로 전달하는 결과 객체다.
 */
public record ApprovalResult(
        String vanTrxId,
        String posTrx,
        int attemptSeq,
        VanApprovalStatus status,
        String approvalNo,
        String declineCode,
        LocalDateTime processedAt
) {
}
