package com.chaeyeongmin.van_sim.transaction.cancel.service.command;

/**
 * VAN 취소 처리에 필요한 입력 명령이다.
 *
 * <p>
 * cancelPosTrx는 이번 취소 요청의 거래번호이고, original* 필드는 취소 대상 원승인을 검증하기 위한
 * 스냅샷이다. 원승인 row를 찾은 뒤 amount, originalVanTrxId, originalApprovalNo가 실제 원장과
 * 일치해야 정상 취소로 진행한다.
 */
public record CancelCommand(
        // 이번 취소 요청의 POS 거래번호. 멱등성/충돌 판단의 1차 키다.
        String cancelPosTrx,
        // 취소 대상 원승인의 POS 거래번호.
        String originalPosTrx,
        // 취소 대상 원승인의 attemptSeq.
        int originalAttemptSeq,
        // Payment가 알고 있는 원승인 VAN 거래번호. 원장 payload 검증에 사용한다.
        String originalVanTrxId,
        // Payment가 알고 있는 원승인 승인번호. 원장 payload 검증에 사용한다.
        String originalApprovalNo,
        // 취소 금액. 현재 정책에서는 원승인 금액과 같아야 하는 전체취소 금액이다.
        int amount
) {
}
