package com.chaeyeongmin.van_sim.control.scenario.approval;

import com.chaeyeongmin.van_sim.control.scenario.approval.model.ApprovalScenario;
import com.chaeyeongmin.van_sim.control.scenario.approval.model.IssuerResult;
import com.chaeyeongmin.van_sim.control.scenario.approval.model.TransportBehavior;
import com.chaeyeongmin.van_sim.control.scenario.approval.registry.ApprovalScenarioRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.http.MediaType;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@AutoConfigureMockMvc
class ApprovalScenarioControllerTest {

    private static final String POS_TRX = "2301-20260808-9999-0001";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ApprovalScenarioRegistry registry;

    @BeforeEach
    void setUp() {
        registry.remove(POS_TRX);
    }

    @Test
    void 승인_시나리오를_HTTP로_등록한다() throws Exception {
        // given
        String body = """
                {
                  "issuerResult": "DECLINED",
                  "transportBehavior": "NORMAL"
                }
                """;

        // when
        mockMvc.perform(
                put("/internal/test-scenarios/approvals/{posTrx}", POS_TRX)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                        .andExpect(status().isOk())
                ;

        // then
        ApprovalScenario scenario = registry.find(POS_TRX).orElseThrow();
        assertThat(scenario.issuerResult()).isEqualTo(IssuerResult.DECLINED);
        assertThat(scenario.transportBehavior()).isEqualTo(TransportBehavior.NORMAL);

    }

}
