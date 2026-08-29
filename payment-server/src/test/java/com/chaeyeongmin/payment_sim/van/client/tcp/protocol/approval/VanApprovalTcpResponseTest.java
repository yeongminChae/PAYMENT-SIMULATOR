package com.chaeyeongmin.payment_sim.van.client.tcp.protocol.approval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;

class VanApprovalTcpResponseTest {

    private final ObjectMapper objectMapper = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build();

    @Test
    void 승인응답을_JSON을_명세에_맞는_전문객체로_역직렬화한다() throws Exception {
        String json = """
                {
                "protocolVersion" : "1",
                "messageType" : "APPROVAL_RESPONSE",
                "requestId" : "abc123",
                "posTrx" : "2301-20260808-9999-0001",
                "attemptSeq" : 1,
                "vanTrxId" : "VAN-20260808-000001",
                "status" : "APPROVED",
                "approvalNo" : "12345678",
                "declineCode" : null,
                "respondedAt" : "2026-08-08T18:30:00"
                }
                """;

        VanApprovalTcpResponse response = objectMapper.readValue(json, VanApprovalTcpResponse.class);

        assertThat(response.protocolVersion()).isEqualTo("1");
        assertThat(response.messageType()).isEqualTo("APPROVAL_RESPONSE");
        assertThat(response.requestId()).isEqualTo("abc123");
        assertThat(response.posTrx()).isEqualTo("2301-20260808-9999-0001");
        assertThat(response.attemptSeq()).isEqualTo(1);
        assertThat(response.vanTrxId()).isEqualTo("VAN-20260808-000001");
        assertThat(response.status()).isEqualTo(VanApprovalStatus.APPROVED);
        assertThat(response.approvalNo()).isEqualTo("12345678");
        assertThat(response.declineCode()).isNull();
        assertThat(response.respondedAt())
                .isEqualTo(LocalDateTime.of(2026, 8, 8, 18, 30, 0));

    }

}
