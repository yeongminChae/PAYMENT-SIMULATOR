package com.chaeyeongmin.van_sim.ledger.cancel.status;

/**
 * 취소 요청에 대한 업무 결과 코드다.
 *
 * <p>
 * {@link VanCancelStatus}가 저장 원장의 상태라면, 이 enum은 현재 요청에 어떤 의미로 응답하는지를 나타낸다.
 */
public enum CancelResultCode {
    // 이번 요청이 신규 정상 취소를 생성했다.
    SUCCESS,
    // 같은 원승인이 이미 다른 cancelPosTrx로 취소되어, 기존 취소 결과를 재응답했다.
    ALREADY_CANCELLED,
    // 취소 대상 원승인 row를 찾지 못했다.
    ORIGINAL_NOT_FOUND,
    // 원승인이 APPROVED가 아니어서 취소 대상이 아니다.
    ORIGINAL_NOT_APPROVED,
    // 요청이 들고 온 원승인 금액/VAN 거래번호/승인번호가 VAN 원장과 다르다.
    ORIGINAL_MISMATCH
}
