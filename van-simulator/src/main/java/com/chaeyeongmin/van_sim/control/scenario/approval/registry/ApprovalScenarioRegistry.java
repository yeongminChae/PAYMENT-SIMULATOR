package com.chaeyeongmin.van_sim.control.scenario.approval.registry;

import com.chaeyeongmin.van_sim.control.scenario.approval.model.ApprovalScenario;

import java.util.Optional;

public interface ApprovalScenarioRegistry {

    void register(String posTrx, ApprovalScenario scenario);

    Optional<ApprovalScenario> find(String posTrx);

    void remove(String posTrx);
}
