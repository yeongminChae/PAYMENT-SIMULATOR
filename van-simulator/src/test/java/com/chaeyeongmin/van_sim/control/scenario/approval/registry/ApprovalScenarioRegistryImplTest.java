package com.chaeyeongmin.van_sim.control.scenario.approval.registry;

import com.chaeyeongmin.van_sim.control.scenario.approval.model.ApprovalScenario;
import com.chaeyeongmin.van_sim.control.scenario.approval.model.IssuerResult;
import com.chaeyeongmin.van_sim.control.scenario.approval.model.TransportBehavior;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class ApprovalScenarioRegistryImplTest {

    private static final String POS_TRX = "2301-20260808-9999-0001";

    private ApprovalScenarioRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new ApprovalScenarioRegistryImpl();
    }

    @Test
    @DisplayName("1. 등록 후 조회")
    void 등록한_시나리오를_조회한다() {
        // given
        ApprovalScenario scenario = new ApprovalScenario(IssuerResult.APPROVED, TransportBehavior.NORMAL);

        // when
        registry.register(POS_TRX, scenario);

        // then
        Optional<ApprovalScenario> foundScenario = registry.find(POS_TRX);
        assertThat(foundScenario).isPresent();
        assertThat(foundScenario.get()).isEqualTo(scenario);

    }

    @Test
    @DisplayName("2. 같은 posTrx 재등록")
    void 같은_posTrx를_재등록하면_덮어쓴다() {
    // given
        ApprovalScenario approvalScenario = new ApprovalScenario(IssuerResult.APPROVED, TransportBehavior.NORMAL);
        registry.register(POS_TRX, approvalScenario);

        // when
        ApprovalScenario declineScenario = new ApprovalScenario(IssuerResult.DECLINED, TransportBehavior.NORMAL);
        registry.register(POS_TRX, declineScenario);

        // then
        Optional<ApprovalScenario> foundScenario = registry.find(POS_TRX);
        assertThat(foundScenario).isPresent();
        assertThat(foundScenario.get()).isEqualTo(declineScenario);
        assertThat(foundScenario.get().issuerResult()).isEqualTo(IssuerResult.DECLINED);

    }

    @Test
    @DisplayName("3. 삭제 후 조회")
    void 삭제한_시나리오는_조회되지_않는다() {
        // given
        ApprovalScenario scenario = new ApprovalScenario(IssuerResult.APPROVED, TransportBehavior.NORMAL);
        registry.register(POS_TRX, scenario);

        // when
        registry.remove(POS_TRX);

        // then
        Optional<ApprovalScenario> foundScenario = registry.find(POS_TRX);
        assertThat(foundScenario).isEmpty();

    }

}
