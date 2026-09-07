package com.chaeyeongmin.van_sim.control.scenario.cancel.dto;

import com.chaeyeongmin.van_sim.control.scenario.cancel.model.CancelScenario;
import com.chaeyeongmin.van_sim.control.scenario.cancel.model.CancelTransportBehavior;

public record CancelScenarioRequest(
        CancelTransportBehavior transportBehavior
) {

    public CancelScenario toScenario() {
        return new CancelScenario(transportBehavior);
    }
}
