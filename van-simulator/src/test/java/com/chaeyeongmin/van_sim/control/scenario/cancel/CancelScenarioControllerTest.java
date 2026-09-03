package com.chaeyeongmin.van_sim.control.scenario.cancel;

import com.chaeyeongmin.van_sim.control.scenario.cancel.model.CancelScenario;
import com.chaeyeongmin.van_sim.control.scenario.cancel.model.CancelTransportBehavior;
import com.chaeyeongmin.van_sim.control.scenario.cancel.registry.CancelScenarioRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class CancelScenarioControllerTest {

    private static final String CANCEL_POS_TRX = "2301-20260808-9999-0001";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private CancelScenarioRegistry registry;

    @BeforeEach
    void setUp() {
        registry.remove(CANCEL_POS_TRX);
    }
    
    @Test
    @DisplayName("PUT /internal/test-scenarios/cancels/{cancelPosTrx}")
    void 취소_시나리오를_HTTP_PUT으로_등록한다() throws Exception {
        // given
        String body = """
                {
                  "transportBehavior": "DROP_RESPONSE"
                }
                """;

        // when
        mockMvc.perform(
                    put("/internal/test-scenarios/cancels/{cancelPosTrx}", CANCEL_POS_TRX)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.transportBehavior").value("DROP_RESPONSE"));

        // then
        CancelScenario scenario = registry.find(CANCEL_POS_TRX).orElseThrow();
        assertThat(scenario.transportBehavior()).isEqualTo(CancelTransportBehavior.DROP_RESPONSE);
    }

    @Test
    @DisplayName("GET /internal/test-scenarios/cancels/{cancelPosTrx}")
    void 취소_시나리오를_HTTP_GET으로_조회한다() throws Exception {
        registry.register(
                CANCEL_POS_TRX,
                new CancelScenario(CancelTransportBehavior.DROP_RESPONSE)
        );

        mockMvc.perform(get("/internal/test-scenarios/cancels/{cancelPosTrx}", CANCEL_POS_TRX))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transportBehavior").value("DROP_RESPONSE"));
    }

    @Test
    @DisplayName("DELETE /internal/test-scenarios/cancels/{cancelPosTrx}")
    void 취소_시나리오를_HTTP_DELETE로_삭제한다() throws Exception {
        registry.register(
                CANCEL_POS_TRX,
                new CancelScenario(CancelTransportBehavior.DROP_RESPONSE)
        );

        mockMvc.perform(delete("/internal/test-scenarios/cancels/{cancelPosTrx}", CANCEL_POS_TRX))
                .andExpect(status().isNoContent());

        assertThat(registry.find(CANCEL_POS_TRX)).isEmpty();
    }

}
