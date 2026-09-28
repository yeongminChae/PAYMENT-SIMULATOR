package com.chaeyeongmin.van_sim.scenario.application.approval;

import com.chaeyeongmin.van_sim.scenario.domain.approval.ApprovalScenario;

import java.util.Optional;

/**
 * POS 거래번호별 승인 테스트 시나리오 저장소의 계약이다.
 */
public interface ApprovalScenarioRegistry {

    void register(String posTrx, ApprovalScenario scenario);

    Optional<ApprovalScenario> find(String posTrx);

    void remove(String posTrx);
}
