package com.chaeyeongmin.van_sim.ledger.reversal.status;

/**
 * VAN reversal 원장의 최종 상태.
 *
 * <p>
 * Reversal은 정상 취소가 아니라 장애 복구 거래다.
 * 원승인 원장을 수정하지 않고 van_reversal에 별도 사실로 남긴다.
 */
public enum VanReversalStatus {
    /**
     * 원승인에 대한 reversal이 성공했다.
     */
    REVERSED,

    /**
     * 원승인이 없거나, reversal 대상이 아니거나, 요청 payload가 맞지 않아 reversal이 거절됐다.
     */
    REVERSAL_DECLINED
}
