package com.chaeyeongmin.payment_sim.infra.repository.dto;

import com.chaeyeongmin.payment_sim.domain.policy.CancelStatus;

/**
 * PAYMENT_CANCEL PENDING row를 최종 취소 결과로 확정할 때 사용하는 update 파라미터.
 *
 * <p>
 * VAN 취소 결과가 확정되면 CANCELLED 또는 CANCEL_DECLINED 상태와
 * 승인번호/거절코드를 함께 저장한다.
 */
public record CancelResultUpdateParam(
        String posTrx,
        String originalPosTrx,
        int originalAttemptSeq,
        CancelStatus cancelStatus,
        String cancelApprovalNo,
        String declineCode
) {
    /**
     * 취소 성공 확정 update 파라미터를 만든다.
     */
    public static CancelResultUpdateParam cancelled(
            String posTrx,
            String originalPosTrx,
            int originalAttemptSeq,
            String cancelApprovalNo
    ) {
        return new CancelResultUpdateParam(
                posTrx,
                originalPosTrx,
                originalAttemptSeq,
                CancelStatus.CANCELLED,
                cancelApprovalNo,
                null
        );
    }

    /**
     * 취소 거절 확정 update 파라미터를 만든다.
     */
    public static CancelResultUpdateParam declined(
            String posTrx,
            String originalPosTrx,
            int originalAttemptSeq,
            String declineCode
    ) {
        return new CancelResultUpdateParam(
                posTrx,
                originalPosTrx,
                originalAttemptSeq,
                CancelStatus.CANCEL_DECLINED,
                null,
                declineCode
        );
    }

    /**
     * MyBatis가 enum을 어떤 방식으로 바인딩하는지에 기대지 않고,
     * DB에 저장할 상태 값을 명시적으로 문자열화한다.
     */
    public String cancelStatusValue() {
        return cancelStatus.name();
    }

}
