package com.chaeyeongmin.van_sim.scenario.api.cancel.dto;

import com.chaeyeongmin.van_sim.scenario.domain.cancel.CancelScenario;
import com.chaeyeongmin.van_sim.scenario.domain.cancel.CancelTransportBehavior;

public record CancelScenarioRequest(
        CancelTransportBehavior transportBehavior
) {

    public CancelScenario toScenario() {
        return new CancelScenario(transportBehavior);
    }
}
