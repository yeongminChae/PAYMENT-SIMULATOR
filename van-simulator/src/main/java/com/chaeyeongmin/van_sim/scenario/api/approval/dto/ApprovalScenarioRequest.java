package com.chaeyeongmin.van_sim.scenario.api.approval.dto;

import com.chaeyeongmin.van_sim.scenario.domain.approval.ApprovalScenario;
import com.chaeyeongmin.van_sim.scenario.domain.approval.IssuerResult;
import com.chaeyeongmin.van_sim.scenario.domain.approval.TransportBehavior;

/**
 * 승인 테스트 시나리오 등록 API의 요청 본문이다.
 * <p>
 * 외부 입력 DTO를 도메인 모델인 {@link ApprovalScenario}로 변환하는 책임도 가진다.
 */
public record ApprovalScenarioRequest(
        IssuerResult issuerResult,
        TransportBehavior transportBehavior
) {

    public ApprovalScenario toScenario() {
        return new ApprovalScenario(issuerResult, transportBehavior);
    }
}
