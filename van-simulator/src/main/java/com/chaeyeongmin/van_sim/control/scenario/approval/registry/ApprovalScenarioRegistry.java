package com.chaeyeongmin.van_sim.control.scenario.approval.registry;

import com.chaeyeongmin.van_sim.control.scenario.approval.model.ApprovalScenario;

import java.util.Optional;

/**
 * POS 거래번호별 승인 테스트 시나리오 저장소의 계약이다.
 */
public interface ApprovalScenarioRegistry {

    void register(String posTrx, ApprovalScenario scenario);

    Optional<ApprovalScenario> find(String posTrx);

    void remove(String posTrx);
}
