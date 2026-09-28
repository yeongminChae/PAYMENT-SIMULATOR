package com.chaeyeongmin.van_sim.scenario.application.cancel;

import com.chaeyeongmin.van_sim.scenario.domain.cancel.CancelScenario;
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
