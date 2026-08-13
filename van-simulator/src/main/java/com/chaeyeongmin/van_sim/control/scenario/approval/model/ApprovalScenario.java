package com.chaeyeongmin.van_sim.control.scenario.approval.model;

/**
 * 특정 POS 거래번호에 적용할 승인 시뮬레이션 규칙이다.
 * <p>
 * 발급사 업무 결과와 VAN 통신 계층의 동작을 함께 표현한다.
 */
public record ApprovalScenario(
        IssuerResult issuerResult,
        TransportBehavior transportBehavior
) {
}
