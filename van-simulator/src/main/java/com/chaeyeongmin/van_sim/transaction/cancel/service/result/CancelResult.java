package com.chaeyeongmin.van_sim.transaction.cancel.service.result;

import com.chaeyeongmin.van_sim.ledger.cancel.status.CancelResultCode;
import com.chaeyeongmin.van_sim.ledger.cancel.status.VanCancelStatus;

import java.time.LocalDateTime;

/**
 * VAN 취소 처리 결과다.
 *
 * <p>
 * cancelStatus는 원장에 저장되는 최종 상태이고, resultCode는 현재 요청 관점의 응답 코드다.
 * 예를 들어 같은 원승인이 이미 취소된 뒤 다른 cancelPosTrx가 들어오면 cancelStatus는 CANCELLED지만
 * resultCode는 ALREADY_CANCELLED가 된다.
 */
public record CancelResult(
        // VAN 내부 취소 거래 추적키.
        String vanCancelTrxId,
        // 이번 요청의 취소 POS 거래번호. ALREADY_CANCELLED 응답에서도 현재 요청 correlation을 유지한다.
        String cancelPosTrx,
        // 취소 대상 원승인 POS 거래번호.
        String originalPosTrx,
        // 취소 대상 원승인 attemptSeq.
        int originalAttemptSeq,
        // VAN 취소 원장의 저장 상태.
        VanCancelStatus cancelStatus,
        // 현재 요청에 돌려줄 업무 결과 코드.
        CancelResultCode resultCode,
        // 정상 취소 성공 시 발급되는 취소 승인번호.
        String cancelApprovalNo,
        // 취소 거절 시 원인을 나타내는 코드.
        String declineCode,
        // VAN 취소 원장 처리 시각.
        LocalDateTime processedAt
) {
}
