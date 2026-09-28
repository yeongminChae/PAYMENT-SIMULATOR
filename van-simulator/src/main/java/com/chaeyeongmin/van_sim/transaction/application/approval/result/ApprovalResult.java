package com.chaeyeongmin.van_sim.transaction.application.approval.result;

import com.chaeyeongmin.van_sim.transaction.domain.approval.VanApprovalStatus;

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
