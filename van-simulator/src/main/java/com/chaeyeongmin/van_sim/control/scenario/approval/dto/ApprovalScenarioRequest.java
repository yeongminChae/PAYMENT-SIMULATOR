package com.chaeyeongmin.van_sim.control.scenario.approval.dto;

import com.chaeyeongmin.van_sim.control.scenario.approval.model.ApprovalScenario;
import com.chaeyeongmin.van_sim.control.scenario.approval.model.IssuerResult;
import com.chaeyeongmin.van_sim.control.scenario.approval.model.TransportBehavior;

public record ApprovalScenarioRequest(
        IssuerResult issuerResult,
        TransportBehavior transportBehavior
) {

    public ApprovalScenario toScenario() {
        return new ApprovalScenario(issuerResult, transportBehavior);
    }
}
