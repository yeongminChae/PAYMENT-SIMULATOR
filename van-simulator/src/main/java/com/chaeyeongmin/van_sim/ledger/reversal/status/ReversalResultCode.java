package com.chaeyeongmin.van_sim.ledger.reversal.status;

/**
 * 현재 reversal 요청에 대한 응답 의미.
 *
 * <p>
 * {@link VanReversalStatus}는 저장된 원장 상태이고, 이 enum은 지금 요청이 신규 성공인지,
 * 기존 reversal 재응답인지, 어떤 사유로 거절됐는지를 나타낸다.
 */
public enum ReversalResultCode {
    /**
     * 현재 요청이 신규 reversal owner가 되어 REVERSED 원장을 저장했다.
     */
    SUCCESS,

    /**
     * 같은 원승인에 이미 REVERSED 원장이 있어 새 row를 만들지 않고 기존 사실을 재응답했다.
     */
    ALREADY_REVERSED,

    // 같은 원승인이 이미 다른 cancelPosTrx로 취소되어, 기존 취소 결과를 재응답했다.
    ALREADY_CANCELLED,

    /**
     * reversal 대상 원승인 row가 VAN 승인 원장에 없다.
     */
    ORIGINAL_NOT_FOUND,

    /**
     * 원승인 row는 있지만 DECLINED 상태라 reversal 대상이 아니다.
     */
    ORIGINAL_NOT_REVERSIBLE,

    /**
     * 원승인 row는 있지만 요청 금액이 원승인 금액과 다르다.
     */
    ORIGINAL_MISMATCH
}
