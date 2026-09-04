package com.chaeyeongmin.van_sim.transaction.reversal.service.command;

/**
 * Reversal 서비스 계층 입력 모델.
 *
 * <p>
 * Reversal은 장애 복구 거래이므로 originalApprovalNo/originalVanTrxId를 받지 않는다.
 * 원승인 식별자는 originalPosTrx + originalAttemptSeq이고, 금액 일치 여부만 검증 대상이다.
 */
public record ReversalCommand(
        // 이번 reversal 요청의 POS 거래번호. 멱등 replay와 conflict 판단의 기준이다.
        String reversalPosTrx,

        // reversal 대상 원승인의 POS 거래번호.
        String originalPosTrx,

        // reversal 대상 원승인의 attemptSeq.
        int originalAttemptSeq,

        // reversal 요청 금액. 원승인 금액과 같아야 한다.
        int amount
) {
}
