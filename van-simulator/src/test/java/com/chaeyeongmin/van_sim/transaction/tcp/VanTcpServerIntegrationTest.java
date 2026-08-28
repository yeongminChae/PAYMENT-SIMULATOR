package com.chaeyeongmin.van_sim.transaction.tcp;

import com.chaeyeongmin.van_sim.control.scenario.approval.model.ApprovalScenario;
import com.chaeyeongmin.van_sim.control.scenario.approval.model.IssuerResult;
import com.chaeyeongmin.van_sim.control.scenario.approval.model.TransportBehavior;
import com.chaeyeongmin.van_sim.control.scenario.approval.registry.ApprovalScenarioRegistry;
import com.chaeyeongmin.van_sim.ledger.approval.entity.VanApproval;
import com.chaeyeongmin.van_sim.ledger.approval.repository.VanApprovalRepository;
import com.chaeyeongmin.van_sim.ledger.approval.status.VanApprovalStatus;
import com.chaeyeongmin.van_sim.protocol.approval.ApprovalRequestMessage;
import com.chaeyeongmin.van_sim.protocol.approval.ApprovalResponseMessage;
import com.chaeyeongmin.van_sim.protocol.approval.ApprovalResponseStatus;
import com.chaeyeongmin.van_sim.protocol.inquiry.InquiryRequestMessage;
import com.chaeyeongmin.van_sim.protocol.inquiry.InquiryResponseMessage;
import com.chaeyeongmin.van_sim.protocol.inquiry.InquiryResponseStatus;
import com.chaeyeongmin.van_sim.support.PostgresTestcontainersConfig;
import com.chaeyeongmin.van_sim.transaction.approval.service.command.ApprovalCommand;
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
import java.io.EOFException;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    @Autowired
    private ApprovalScenarioRegistry scenarioRegistry;

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

    @Test
    void 승인_완료후_DROP_RESPONSE면_응답은_없지만_APPROVED_원장은_남는다() throws Exception {
        // given
        // TCP 승인 요청 생성
        ApprovalRequestMessage request = new ApprovalRequestMessage(
                "1",
                "APPROVAL",
                "REQ-TCP-001",
                "2301-20260808-9999-0002",
                1,
                10_000,
                "1234567812345678",
                "2812"
        );

        // APPROVED + DROP_RESPONSE scenario 등록
        ApprovalScenario scenario = new ApprovalScenario(
                IssuerResult.APPROVED,
                TransportBehavior.DROP_RESPONSE
        );

        scenarioRegistry.register(request.posTrx(), scenario);

        byte[] requestPayload = objectMapper.writeValueAsBytes(request);

        // when
        // 실제 socket 요청
        assertThatThrownBy(() -> sendLengthPrefixedRequest(requestPayload))
                .isInstanceOfAny(
                        SocketTimeoutException.class,
                        EOFException.class
                );

        // then
        // 응답을 받지 못했어도 VAN 승인 원장은 이미 저장되어 있어야 한다.
        VanApproval saved = repository.findByPosTrxAndAttemptSeq(
                        request.posTrx(),
                        request.attemptSeq()
                )
                .orElseThrow();

        // VAN DB는 APPROVED인지 확인
        assertThat(saved.getApprovalStatus()).isEqualTo(VanApprovalStatus.APPROVED);
        assertThat(saved.getApprovalNo()).isNotBlank();
        assertThat(saved.getVanTrxId()).isNotBlank();

    }

    @Test
    void length_prefixed_JSON_Inquiry_요청으로_저장된_APPROVED_원장을_조회한다() throws Exception {
        // given: Inquiry가 조회할 기존 승인 원장
        VanApproval approval = VanApproval.builder()
                .vanTrxId("VAN-INQUIRY-TCP-001")
                .posTrx("2301-20260808-9999-0003")
                .attemptSeq(1)
                .amount(10_000)
                .cardBin("12345678")
                .cardLast4("5678")
                .approvalStatus(VanApprovalStatus.APPROVED)
                .approvalNo("APPROVAL-INQUIRY-001")
                .processedAt(LocalDateTime.of(2026, 8, 27, 10, 0))
                .build();
        repository.saveAndFlush(approval);

        InquiryRequestMessage request = new InquiryRequestMessage(
                "1",
                "INQUIRY",
                "REQ-INQUIRY-TCP-001",
                approval.getPosTrx(),
                approval.getAttemptSeq()
        );

        // when
        byte[] responsePayload = sendLengthPrefixedRequest(
                objectMapper.writeValueAsBytes(request)
        );
        InquiryResponseMessage response =
                objectMapper.readValue(responsePayload, InquiryResponseMessage.class);

        // then
        assertThat(response.protocolVersion()).isEqualTo("1");
        assertThat(response.messageType()).isEqualTo("INQUIRY_RESPONSE");
        assertThat(response.requestId()).isEqualTo(request.requestId());
        assertThat(response.posTrx()).isEqualTo(request.posTrx());
        assertThat(response.attemptSeq()).isEqualTo(request.attemptSeq());
        assertThat(response.status()).isEqualTo(InquiryResponseStatus.APPROVED);
        assertThat(response.vanTrxId()).isEqualTo("VAN-INQUIRY-TCP-001");
        assertThat(response.approvalNo()).isEqualTo("APPROVAL-INQUIRY-001");
        assertThat(repository.count()).isEqualTo(1);
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
