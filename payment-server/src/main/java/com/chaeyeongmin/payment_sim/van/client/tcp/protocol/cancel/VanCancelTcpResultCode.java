package com.chaeyeongmin.payment_sim.van.client.tcp.protocol.cancel;

/**
 * TCP Cancel 응답의 업무 결과 코드다.
 *
 * <p>
 * cancelStatus가 VAN 취소 원장의 저장 상태라면, resultCode는 현재 Cancel 요청 관점의 결과 의미다.
 * 예를 들어 이미 취소된 원승인을 다른 cancelPosTrx로 다시 취소하면 cancelStatus는 CANCELLED,
 * resultCode는 ALREADY_CANCELLED가 될 수 있다.
 */
public enum VanCancelTcpResultCode {
    // 이번 요청이 신규 취소를 정상 생성했다.
    SUCCESS,
    // 같은 원승인이 이미 취소되어 기존 취소 결과를 반환했다.
    ALREADY_CANCELLED,
    // 취소 대상 원승인을 찾지 못했다.
    ORIGINAL_NOT_FOUND,
    // 원승인이 APPROVED 상태가 아니라 취소할 수 없다.
    ORIGINAL_NOT_APPROVED,
    // 요청의 원승인 거래번호/승인번호/금액이 VAN 원장과 일치하지 않는다.
    ORIGINAL_MISMATCH
}
