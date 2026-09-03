package com.chaeyeongmin.van_sim.control.scenario.cancel.registry;

import com.chaeyeongmin.van_sim.control.scenario.cancel.model.CancelScenario;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Component
public class CancelScenarioRegistryImpl implements CancelScenarioRegistry {

    private final ConcurrentMap<String, CancelScenario> scenarios = new ConcurrentHashMap<>();


    @Override
    public void register(String cancelPosTrx, CancelScenario scenario) {
        scenarios.put(cancelPosTrx, scenario);
    }

    @Override
    public Optional<CancelScenario> find(String cancelPosTrx) {
        return Optional.ofNullable(scenarios.get(cancelPosTrx));
    }

    @Override
    public void remove(String cancelPosTrx) {
        scenarios.remove(cancelPosTrx);
    }

}
