package com.chaeyeongmin.payment_sim.api.payment.integration;

import com.chaeyeongmin.payment_sim.api.payment.dto.card.CardInput;
import com.chaeyeongmin.payment_sim.api.payment.dto.request.ApproveRequest;
import com.chaeyeongmin.payment_sim.van.gateway.VanGateway;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

import java.net.ServerSocket;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class PaymentApprovalConnectionRefusedIntegrationTest {

    private static final String POS_TRX = "2376-20260828-9991-2401";
    private static final int CLOSED_PORT = findClosedLocalPort();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoSpyBean
    private VanGateway vanGateway;

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",
                () -> "jdbc:sqlite:./build/payment-approval-connection-refused-test.db");
        registry.add("payment.van.mode", () -> "tcp");
        registry.add("payment.van.tcp.host", () -> "127.0.0.1");
        registry.add("payment.van.tcp.port", () -> CLOSED_PORT);
        registry.add("payment.van.tcp.connect-timeout-ms", () -> 500);
        registry.add("payment.van.tcp.read-timeout-ms", () -> 1000);
    }

    @BeforeEach
    @AfterEach
    void cleanup() {
        jdbcTemplate.update("DELETE FROM PAYMENT_EVENT_LOG WHERE POS_TRX = ?", POS_TRX);
        jdbcTemplate.update("DELETE FROM PAYMENT_EXTERNAL_INFO WHERE POS_TRX = ?", POS_TRX);
        jdbcTemplate.update("DELETE FROM PAYMENT_ATTEMPT WHERE POS_TRX = ?", POS_TRX);
        jdbcTemplate.update("DELETE FROM PAYMENT_ATTEMPT_SEQ WHERE POS_TRX = ?", POS_TRX);
    }

    @Test
    void connection_refused_후_PROCESSING과_external_info를_정리하고_sequence는_유지한다() throws Exception {
        ApproveRequest request = request();
        String requestJson = objectMapper.writeValueAsString(request);

        String firstResponse = mockMvc.perform(post("/api/v1/payments/approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson))
                .andExpect(status().isInternalServerError())
                .andReturn().getResponse().getContentAsString();
        assertThat(objectMapper.readTree(firstResponse).path("result_code").asText())
                .isEqualTo("INTERNAL_ERROR");

        assertThat(count("PAYMENT_ATTEMPT")).isZero();
        assertThat(count("PAYMENT_EXTERNAL_INFO")).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT LAST_SEQ FROM PAYMENT_ATTEMPT_SEQ WHERE POS_TRX = ?",
                Integer.class,
                POS_TRX
        )).isEqualTo(1);
        verify(vanGateway, times(1)).approve(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void 전송전_connection_refused라면_동일_재요청에서_VAN을_다시_호출해야_한다() throws Exception {
        String requestJson = objectMapper.writeValueAsString(request());

        mockMvc.perform(post("/api/v1/payments/approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson))
                .andExpect(status().isInternalServerError());

        mockMvc.perform(post("/api/v1/payments/approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson))
                .andExpect(status().isInternalServerError());

        verify(vanGateway, times(2)).approve(org.mockito.ArgumentMatchers.any());
        assertThat(count("PAYMENT_ATTEMPT")).isZero();
        assertThat(count("PAYMENT_EXTERNAL_INFO")).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT LAST_SEQ FROM PAYMENT_ATTEMPT_SEQ WHERE POS_TRX = ?",
                Integer.class,
                POS_TRX
        )).isEqualTo(2);
    }

    private int count(String table) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE POS_TRX = ?",
                Integer.class,
                POS_TRX
        );
    }

    private static ApproveRequest request() {
        return new ApproveRequest(
                POS_TRX,
                10_000,
                new CardInput("4111111111111111", "2812")
        );
    }

    private static int findClosedLocalPort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
