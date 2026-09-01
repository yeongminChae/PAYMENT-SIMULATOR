package com.chaeyeongmin.van_sim.transaction.cancel.service.support;

/**
 * VAN 취소 성공 시 응답에 포함할 취소 승인번호 생성 정책을 추상화한다.
 */
public interface CancelApprovalNumberGenerator {
    String generate();
}
