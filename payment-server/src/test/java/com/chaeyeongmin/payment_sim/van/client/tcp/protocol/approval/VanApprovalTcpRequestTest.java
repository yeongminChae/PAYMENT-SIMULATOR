package com.chaeyeongmin.payment_sim.van.client.tcp.protocol.approval;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.ObjectMapper;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.junit.jupiter.api.Assertions.*;

class VanApprovalTcpRequestTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void 승인요청을_명세에_맞는_JSON으로_직렬화한다() throws Exception {
        VanApprovalTcpRequest request = new VanApprovalTcpRequest(
                "1",
                "APPROVAL",
                "abc123",
                "2301-20260808-9999-0001",
                1,
                10_000,
                "1234567890123456",
                "2808"
        );

        String json = objectMapper.writeValueAsString(request);

        JsonNode root = objectMapper.readTree(json);

        assertThat(root.get("protocolVersion").asText()).isEqualTo("1");
        assertThat(root.get("messageType").asText()).isEqualTo("APPROVAL");
        assertThat(root.get("requestId").asText()).isEqualTo("abc123");
        assertThat(root.get("posTrx").asText()).isEqualTo("2301-20260808-9999-0001");
        assertThat(root.get("attemptSeq").asInt()).isEqualTo(1);
        assertThat(root.get("amount").asInt()).isEqualTo(10_000);
        assertThat(root.get("pan").asText()).isEqualTo("1234567890123456");
        assertThat(root.get("expiryYyMm").asText()).isEqualTo("2808");
        assertThat(root.size()).isEqualTo(8);

    }

}
