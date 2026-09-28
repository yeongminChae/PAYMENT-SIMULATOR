package com.chaeyeongmin.van_sim.scenario.application.cancel;

import com.chaeyeongmin.van_sim.scenario.domain.cancel.CancelScenario;

import java.util.Optional;

public interface CancelScenarioRegistry {

    void register(String cancelPosTrx, CancelScenario scenario);

    Optional<CancelScenario> find(String cancelPosTrx);

    void remove(String cancelPosTrx);
}
