package com.chaeyeongmin.van_sim.control.scenario.approval.registry;

import com.chaeyeongmin.van_sim.control.scenario.approval.model.ApprovalScenario;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Component
public class ApprovalScenarioRegistryImpl implements ApprovalScenarioRegistry {

    private final ConcurrentMap<String, ApprovalScenario> scenarios = new ConcurrentHashMap<>();

    @Override
    public void register(String posTrx, ApprovalScenario scenario) {
        scenarios.put(posTrx, scenario);
    }

    @Override
    public Optional<ApprovalScenario> find(String posTrx) {
        return Optional.ofNullable(scenarios.get(posTrx));
    }

    @Override
    public void remove(String posTrx) {
        scenarios.remove(posTrx);
    }
}
