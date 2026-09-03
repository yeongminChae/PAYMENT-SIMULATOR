package com.chaeyeongmin.van_sim.transaction.reversal.service.command;

/**
 * Reversal 서비스 계층 입력 모델.
 *
 * <p>
 * Reversal은 장애 복구 거래이므로 originalApprovalNo/originalVanTrxId를 받지 않는다.
 * 원승인 식별자는 originalPosTrx + originalAttemptSeq이고, 금액 일치 여부만 검증 대상이다.
 */
public record ReversalCommand(
        String reversalPosTrx,
        String originalPosTrx,
        int originalAttemptSeq,
        int amount
) {
}
