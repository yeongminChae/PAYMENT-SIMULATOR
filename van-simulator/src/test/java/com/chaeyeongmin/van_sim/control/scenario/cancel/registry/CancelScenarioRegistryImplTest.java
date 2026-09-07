package com.chaeyeongmin.van_sim.control.scenario.cancel.registry;

import com.chaeyeongmin.van_sim.control.scenario.cancel.model.CancelScenario;
import com.chaeyeongmin.van_sim.control.scenario.cancel.model.CancelTransportBehavior;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class CancelScenarioRegistryImplTest {

    private static final String CANCEL_POS_TRX = "2301-20260808-9999-0001";

    private CancelScenarioRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new CancelScenarioRegistryImpl();
    }

    @Test
    @DisplayName("1. 등록 후 조회")
    void 등록한_취소_시나리오를_조회한다() {
        // given
        CancelScenario scenario = new CancelScenario(CancelTransportBehavior.NORMAL);

        // when
        registry.register(CANCEL_POS_TRX, scenario);

        // then
        Optional<CancelScenario> foundScenario = registry.find(CANCEL_POS_TRX);
        assertThat(foundScenario).isPresent();
        assertThat(foundScenario.get()).isEqualTo(scenario);
    }

    @Test
    @DisplayName("2. 같은 cancelPosTrx 재등록")
    void 같은_cancelPosTrx를_재등록하면_덮어쓴다() {
        // given
        CancelScenario cancelScenario = new CancelScenario(CancelTransportBehavior.NORMAL);
        registry.register(CANCEL_POS_TRX, cancelScenario);

        // when
        CancelScenario declineScenario = new CancelScenario(CancelTransportBehavior.DROP_RESPONSE);
        registry.register(CANCEL_POS_TRX, declineScenario);

        // then
        Optional<CancelScenario> foundScenario = registry.find(CANCEL_POS_TRX);
        assertThat(foundScenario).isPresent();
        assertThat(foundScenario.get()).isEqualTo(declineScenario);

    }

    @Test
    @DisplayName("3. 삭제 후 조회")
    void 삭제한_취소_시나리오는_조회되지_않는다() {
        // given
        CancelScenario scenario = new CancelScenario(CancelTransportBehavior.NORMAL);
        registry.register(CANCEL_POS_TRX, scenario);

        // when
        registry.remove(CANCEL_POS_TRX);

        // then
        Optional<CancelScenario> foundScenario = registry.find(CANCEL_POS_TRX);
        assertThat(foundScenario).isEmpty();

    }

}
