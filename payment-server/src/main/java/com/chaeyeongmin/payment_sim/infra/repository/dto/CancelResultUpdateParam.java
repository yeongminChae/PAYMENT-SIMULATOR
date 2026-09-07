package com.chaeyeongmin.payment_sim.infra.repository.dto;

import com.chaeyeongmin.payment_sim.domain.policy.CancelStatus;

/**
 * PAYMENT_CANCEL PENDING row를 최종 취소 결과로 확정할 때 사용하는 update 파라미터.
 *
 * <p>
 * VAN 취소 결과가 확정되면 CANCELLED 또는 CANCEL_DECLINED 상태와
 * VAN 취소 거래번호, 승인번호/거절코드를 함께 저장한다.
 * timeout처럼 VAN 처리 여부를 알 수 없는 경우에는 UNKNOWN_TIMEOUT과 TIMEOUT declineCode를 저장한다.
 */
public record CancelResultUpdateParam(
        String posTrx,
        String originalPosTrx,
        int originalAttemptSeq,
        CancelStatus cancelStatus,
        String vanCancelTrxId,
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
            String vanCancelTrxId,
            String cancelApprovalNo
    ) {
        return new CancelResultUpdateParam(
                posTrx,
                originalPosTrx,
                originalAttemptSeq,
                CancelStatus.CANCELLED,
                vanCancelTrxId,
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
            String vanCancelTrxId,
            String declineCode
    ) {
        return new CancelResultUpdateParam(
                posTrx,
                originalPosTrx,
                originalAttemptSeq,
                CancelStatus.CANCEL_DECLINED,
                vanCancelTrxId,
                null,
                declineCode
        );
    }

    /**
     * 취소 UNKNOWN_TIMEOUT 확정 update 파라미터를 만든다.
     *
     * <p>
     * VAN timeout은 취소 성공/거절을 알 수 없으므로 VAN 거래번호와 취소 승인번호를 비워 둔다.
     * declineCode에는 관측 가능한 원인인 TIMEOUT을 남겨 이벤트와 후속 응답에서 미확정 사유를 추적할 수 있게 한다.
     */
    public static CancelResultUpdateParam unknownTimeout(
            String posTrx,
            String originalPosTrx,
            int originalAttemptSeq
    ) {
        return new CancelResultUpdateParam(
                posTrx,
                originalPosTrx,
                originalAttemptSeq,
                CancelStatus.UNKNOWN_TIMEOUT,
                null,
                null,
                "TIMEOUT"
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
