package com.chaeyeongmin.van_sim.control.scenario.approval;

import com.chaeyeongmin.van_sim.control.scenario.approval.dto.ApprovalScenarioRequest;
import com.chaeyeongmin.van_sim.control.scenario.approval.model.ApprovalScenario;
import com.chaeyeongmin.van_sim.control.scenario.approval.registry.ApprovalScenarioRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 승인 처리 결과를 테스트 시나리오로 제어하기 위한 내부용 REST API다.
 * <p>
 * posTrx 단위로 발급사 결과와 통신 동작을 등록, 조회, 삭제한다.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/internal/test-scenarios/approvals")
public class ApprovalScenarioController {

    private final ApprovalScenarioRegistry registry;

    @PutMapping("/{posTrx}")
    public ApprovalScenario register(
            @PathVariable String posTrx,
            @RequestBody ApprovalScenarioRequest request
    ) {
        ApprovalScenario scenario = request.toScenario();
        registry.register(posTrx, scenario);
        return scenario;
    }

    @GetMapping("/{posTrx}")
    public ResponseEntity<ApprovalScenario> find(@PathVariable String posTrx) {
        return ResponseEntity.of(registry.find(posTrx));
    }

    @DeleteMapping("/{posTrx}")
    public ResponseEntity<Void> remove(@PathVariable String posTrx) {
        registry.remove(posTrx);
        return ResponseEntity.noContent().build();
    }
}
