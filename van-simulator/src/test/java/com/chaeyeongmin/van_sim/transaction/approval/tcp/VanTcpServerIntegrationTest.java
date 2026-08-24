package com.chaeyeongmin.van_sim.transaction.approval.tcp;

import com.chaeyeongmin.van_sim.ledger.approval.entity.VanApproval;
import com.chaeyeongmin.van_sim.ledger.approval.repository.VanApprovalRepository;
import com.chaeyeongmin.van_sim.ledger.approval.status.VanApprovalStatus;
import com.chaeyeongmin.van_sim.protocol.approval.ApprovalRequestMessage;
import com.chaeyeongmin.van_sim.protocol.approval.ApprovalResponseMessage;
import com.chaeyeongmin.van_sim.protocol.approval.ApprovalResponseStatus;
import com.chaeyeongmin.van_sim.support.PostgresTestcontainersConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.integration.ip.tcp.connection.TcpNetServerConnectionFactory;
import org.springframework.test.context.ActiveProfiles;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.Socket;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "van.tcp.port=0")
@ActiveProfiles("postgres")
@Import(PostgresTestcontainersConfig.class)
class VanTcpServerIntegrationTest {

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private TcpNetServerConnectionFactory vanTcpServerConnectionFactory;

    @Autowired
    private VanApprovalRepository repository;

    /**
     * 테스트 간 승인 원장 중복을 막기 위해 기존 PostgreSQL 통합 테스트와 동일하게 원장을 비운다.
     */
    @BeforeEach
    void setUp() {
        repository.deleteAll();
    }

    @Test
    void length_prefixed_JSON_승인_요청을_TCP로_수신하고_승인_응답과_원장을_반환한다() throws Exception {
        // given
        ApprovalRequestMessage request = new ApprovalRequestMessage(
                "1",
                "APPROVAL",
                "REQ-TCP-001",
                "2301-20260808-9999-0001",
                1,
                10_000,
                "1234567812345678",
                "2812"
        );

        byte[] requestPayload = objectMapper.writeValueAsBytes(request);

        // when
        // 실제 Socket client로 4-byte length header와 JSON payload를 직접 전송한다.
        byte[] responsePayload = sendLengthPrefixedRequest(requestPayload);
        ApprovalResponseMessage response =
                objectMapper.readValue(responsePayload, ApprovalResponseMessage.class);

        // then
        assertThat(response.protocolVersion()).isEqualTo("1");
        assertThat(response.messageType()).isEqualTo("APPROVAL_RESPONSE");
        assertThat(response.requestId()).isEqualTo(request.requestId());
        assertThat(response.posTrx()).isEqualTo(request.posTrx());
        assertThat(response.attemptSeq()).isEqualTo(request.attemptSeq());
        assertThat(response.status()).isEqualTo(ApprovalResponseStatus.APPROVED);
        assertThat(response.vanTrxId()).isNotBlank();
        assertThat(response.approvalNo()).isNotBlank();

        // TCP 요청이 ApprovalTcpHandler와 ApprovalService를 거쳐 실제 승인 원장까지 저장됐는지 확인한다.
        VanApproval saved = repository.findByPosTrxAndAttemptSeq(
                        request.posTrx(),
                        request.attemptSeq()
                )
                .orElseThrow();

        assertThat(saved.getApprovalStatus()).isEqualTo(VanApprovalStatus.APPROVED);
    }

    /**
     * 테스트용 실제 TCP client 역할을 한다.
     * <p>
     * 서버가 port=0으로 뜨기 때문에 connection factory에서 OS가 할당한 실제 port를 조회한다.
     */
    private byte[] sendLengthPrefixedRequest(byte[] requestPayload) throws Exception {
        try (Socket socket = new Socket("localhost", vanTcpServerConnectionFactory.getPort());
             DataOutputStream output = new DataOutputStream(socket.getOutputStream());
             DataInputStream input = new DataInputStream(socket.getInputStream())) {

            // 테스트가 무한 대기하는 걸 막는 안전장치
            socket.setSoTimeout(3_000);

            // 요청 framing: [4-byte Big Endian payload length][UTF-8 JSON payload]
            output.writeInt(requestPayload.length);
            output.write(requestPayload);
            output.flush();

            // 응답 framing도 동일하게 4-byte Big Endian length를 먼저 읽고, 해당 길이만큼 payload를 읽는다.
            int responseLength = input.readInt();

            byte[] responsePayload = new byte[responseLength];
            input.readFully(responsePayload);

            return responsePayload;
        }

    }
}
