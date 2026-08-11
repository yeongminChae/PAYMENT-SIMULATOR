package com.chaeyeongmin.van_sim.protocol.approval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;

class ApprovalResponseMessageTest {

    private final ObjectMapper objectMapper = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build();

    @Test
    void 승인응답_전문객체를_JSON으로_직렬화한다() throws Exception {

        ApprovalResponseMessage response = new ApprovalResponseMessage(
                "1",
                "APPROVAL_RESPONSE",
                "abc123",
                "2301-20260808-9999-0001",
                1,
                "VAN-20260808-000001",
                ApprovalStatus.APPROVED,
                "12345678",
                null,
                LocalDateTime.of(2026, 8, 8, 18, 30, 0)
        );

        String json = objectMapper.writeValueAsString(response);

        JsonNode root = objectMapper.readTree(json);
        assertThat(root.get("protocolVersion").asText()).isEqualTo("1");
        assertThat(root.get("messageType").asText()).isEqualTo("APPROVAL_RESPONSE");
        assertThat(root.get("requestId").asText()).isEqualTo("abc123");
        assertThat(root.get("posTrx").asText()).isEqualTo("2301-20260808-9999-0001");
        assertThat(root.get("attemptSeq").asInt()).isEqualTo(1);
        assertThat(root.get("vanTrxId").asText()).isEqualTo("VAN-20260808-000001");
        assertThat(root.get("status").asText()).isEqualTo("APPROVED");
        assertThat(root.get("approvalNo").asText()).isEqualTo("12345678");
        assertThat(root.get("declineCode").isNull()).isTrue();
        assertThat(root.get("respondedAt").asText()).isEqualTo("2026-08-08T18:30:00");
        assertThat(root.size()).isEqualTo(10);

    }

}
