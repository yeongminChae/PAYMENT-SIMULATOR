package com.chaeyeongmin.van_sim.transaction.approval.service.support;

/**
 * [Support]
 * VAN 승인 성공 시 응답에 포함할 승인번호 생성 정책을 추상화한다.
 * <p>
 * 목적:
 * - 승인번호 포맷/채번 방식이 바뀌어도 승인 서비스 흐름이 영향을 받지 않게 한다.
 * - 테스트에서는 고정 승인번호를 주입해 승인 결과를 예측 가능하게 만든다.
 */
public interface ApprovalNumberGenerator {
    String generate();
}
