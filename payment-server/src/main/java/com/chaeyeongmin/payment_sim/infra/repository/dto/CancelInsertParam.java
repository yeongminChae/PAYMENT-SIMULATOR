package com.chaeyeongmin.payment_sim.infra.repository.dto;

import com.chaeyeongmin.payment_sim.domain.policy.CancelStatus;

/**
 * PAYMENT_CANCEL PENDING row 생성에 사용하는 insert 파라미터.
 *
 * <p>
 * 신규 취소 요청은 VAN 호출 전에 먼저 PENDING row를 저장해
 * 동일 원거래에 대한 중복 취소 호출을 DB unique 제약으로 방어한다.
 */
public record CancelInsertParam(
        String posTrx,
        String originalPosTrx,
        int originalAttemptSeq,
        CancelStatus cancelStatus
) {
    /**
     * 최초 취소 접수 상태인 PENDING insert 파라미터를 만든다.
     */
    public static CancelInsertParam pending(
            String posTrx,
            String originalPosTrx,
            int originalAttemptSeq
    ) {
        return new CancelInsertParam(
                posTrx,
                originalPosTrx,
                originalAttemptSeq,
                CancelStatus.PENDING
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
