package com.chaeyeongmin.van_sim.control.scenario.approval.registry;

import com.chaeyeongmin.van_sim.control.scenario.approval.model.ApprovalScenario;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 메모리에 승인 테스트 시나리오를 보관하는 레지스트리 구현체다.
 * <p>
 * 동시 요청이 들어오는 테스트 환경에서도 안전하게 조회/갱신할 수 있도록 ConcurrentMap을 사용한다.
 */
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
