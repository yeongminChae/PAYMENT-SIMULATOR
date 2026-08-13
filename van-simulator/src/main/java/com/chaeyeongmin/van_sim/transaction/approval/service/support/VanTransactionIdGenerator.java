package com.chaeyeongmin.van_sim.transaction.approval.service.support;

/**
 * [Support]
 * VAN 내부 거래번호(vanTrxId) 생성 정책을 추상화한다.
 * <p>
 * 목적:
 * - VAN 거래번호 포맷/채번 방식을 승인 서비스에서 분리한다.
 * - 테스트에서는 고정 거래번호를 주입해 저장 원장과 응답을 검증하기 쉽게 만든다.
 */
public interface VanTransactionIdGenerator {
    String generate();
}
