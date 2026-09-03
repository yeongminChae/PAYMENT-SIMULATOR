package com.chaeyeongmin.van_sim.control.scenario.cancel;

import com.chaeyeongmin.van_sim.control.scenario.cancel.dto.CancelScenarioRequest;
import com.chaeyeongmin.van_sim.control.scenario.cancel.model.CancelScenario;
import com.chaeyeongmin.van_sim.control.scenario.cancel.registry.CancelScenarioRegistry;
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
