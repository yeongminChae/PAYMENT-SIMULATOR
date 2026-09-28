package com.chaeyeongmin.van_sim.scenario.api.cancel;

import com.chaeyeongmin.van_sim.scenario.api.cancel.dto.CancelScenarioRequest;
import com.chaeyeongmin.van_sim.scenario.domain.cancel.CancelScenario;
import com.chaeyeongmin.van_sim.scenario.application.cancel.CancelScenarioRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/internal/test-scenarios/cancels")
public class CancelScenarioController {

    private final CancelScenarioRegistry registry;

    @PutMapping("/{cancelPosTrx}")
    public CancelScenario register(
            @PathVariable String cancelPosTrx,
            @RequestBody CancelScenarioRequest request
    ) {
        CancelScenario scenario = request.toScenario();
        registry.register(cancelPosTrx, scenario);

        return scenario;
    }

    @GetMapping("/{cancelPosTrx}")
    public ResponseEntity<CancelScenario> find(@PathVariable String cancelPosTrx) {
        return ResponseEntity.of(registry.find(cancelPosTrx));
    }

    @DeleteMapping("/{cancelPosTrx}")
    public ResponseEntity<Void> remove(@PathVariable String cancelPosTrx) {
        registry.remove(cancelPosTrx);

        return ResponseEntity.noContent().build();
    }

}
