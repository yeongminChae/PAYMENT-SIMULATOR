package com.chaeyeongmin.van_sim.protocol.approval;

import org.junit.jupiter.api.Test;
import org.testcontainers.shaded.com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;

class ApprovalRequestMessageTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void 승인요청_JSON을_전문객체로_역직렬화한다() throws Exception {
        String json = """
                {
                  "protocolVersion": "1",
                  "messageType": "APPROVAL",
                  "requestId": "abc123",
                  "posTrx": "202608080001",
                  "attemptSeq": 1,
                  "amount": 10000,
                  "pan": "1234567890123456",
                  "expiryYyMm": "2808"
                }
                """;

        ApprovalRequestMessage request = objectMapper.readValue(json, ApprovalRequestMessage.class);

        assertThat(request.protocolVersion()).isEqualTo("1");
        assertThat(request.messageType()).isEqualTo("APPROVAL");
        assertThat(request.requestId()).isEqualTo("abc123");
        assertThat(request.posTrx()).isEqualTo("202608080001");
        assertThat(request.attemptSeq()).isEqualTo(1);
        assertThat(request.amount()).isEqualTo(10_000);
        assertThat(request.pan()).isEqualTo("1234567890123456");
        assertThat(request.expiryYyMm()).isEqualTo("2808");

    }

}