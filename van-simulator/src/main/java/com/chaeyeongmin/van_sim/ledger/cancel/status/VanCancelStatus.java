package com.chaeyeongmin.van_sim.ledger.cancel.status;

/**
 * VAN 취소 원장에 저장되는 최종 상태다.
 *
 * <p>
 * 현재 VAN simulator의 취소는 동기 처리 모델이므로 PENDING 상태를 원장에 남기지 않는다.
 * 정상 취소면 CANCELLED, 정책/원승인 검증 실패면 CANCEL_DECLINED로 저장한다.
 */
public enum VanCancelStatus {
    // 취소가 정상 승인되어 cancelApprovalNo가 존재하는 상태.
    CANCELLED,
    // 취소가 거절되어 declineCode가 존재하는 상태.
    CANCEL_DECLINED
}
