package com.chaeyeongmin.van_sim.control.scenario.cancel.registry;

import com.chaeyeongmin.van_sim.control.scenario.cancel.model.CancelScenario;

import java.util.Optional;

public interface CancelScenarioRegistry {

    void register(String cancelPosTrx, CancelScenario scenario);

    Optional<CancelScenario> find(String cancelPosTrx);

    void remove(String cancelPosTrx);
}
