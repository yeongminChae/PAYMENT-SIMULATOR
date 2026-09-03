package com.chaeyeongmin.van_sim.ledger.reversal.status;

/**
 * 현재 reversal 요청에 대한 응답 의미.
 *
 * <p>
 * {@link VanReversalStatus}는 저장된 원장 상태이고, 이 enum은 지금 요청이 신규 성공인지,
 * 기존 reversal 재응답인지, 어떤 사유로 거절됐는지를 나타낸다.
 */
public enum ReversalResultCode {
    SUCCESS,
    ALREADY_REVERSED,
    ORIGINAL_NOT_FOUND,
    ORIGINAL_NOT_REVERSIBLE,
    ORIGINAL_MISMATCH
}
