package com.chaeyeongmin.van_sim.transaction.tcp;

import com.chaeyeongmin.van_sim.control.scenario.approval.model.ApprovalScenario;
import com.chaeyeongmin.van_sim.control.scenario.approval.model.IssuerResult;
import com.chaeyeongmin.van_sim.control.scenario.approval.model.TransportBehavior;
import com.chaeyeongmin.van_sim.control.scenario.approval.registry.ApprovalScenarioRegistry;
import com.chaeyeongmin.van_sim.ledger.approval.entity.VanApproval;
import com.chaeyeongmin.van_sim.ledger.approval.repository.VanApprovalRepository;
import com.chaeyeongmin.van_sim.ledger.approval.status.VanApprovalStatus;
import com.chaeyeongmin.van_sim.ledger.cancel.entity.VanCancel;
import com.chaeyeongmin.van_sim.ledger.cancel.repository.VanCancelRepository;
import com.chaeyeongmin.van_sim.ledger.cancel.status.CancelResultCode;
import com.chaeyeongmin.van_sim.ledger.cancel.status.VanCancelStatus;
import com.chaeyeongmin.van_sim.protocol.approval.ApprovalRequestMessage;
import com.chaeyeongmin.van_sim.protocol.approval.ApprovalResponseMessage;
import com.chaeyeongmin.van_sim.protocol.approval.ApprovalResponseStatus;
import com.chaeyeongmin.van_sim.protocol.cancel.CancelRequestMessage;
import com.chaeyeongmin.van_sim.protocol.cancel.CancelResponseMessage;
import com.chaeyeongmin.van_sim.protocol.inquiry.InquiryRequestMessage;
import com.chaeyeongmin.van_sim.protocol.inquiry.InquiryResultCode;
import com.chaeyeongmin.van_sim.protocol.inquiry.InquiryResponseMessage;
import com.chaeyeongmin.van_sim.protocol.inquiry.InquiryResponseStatus;
import com.chaeyeongmin.van_sim.protocol.inquiry.InquiryTargetType;
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
    private VanApprovalRepository approvalRepository;

    @Autowired
    private VanCancelRepository cancelRepository;

    @Autowired
    private ApprovalScenarioRegistry scenarioRegistry;

    /**
     * 테스트 간 승인 원장 중복을 막기 위해 기존 PostgreSQL 통합 테스트와 동일하게 원장을 비운다.
     */
    @BeforeEach
    void setUp() {
        approvalRepository.deleteAll();
        cancelRepository.deleteAll();
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
        VanApproval saved = approvalRepository.findByPosTrxAndAttemptSeq(
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
        VanApproval saved = approvalRepository.findByPosTrxAndAttemptSeq(
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

        VanApproval approval =
            approvedOriginalApproval("VAN-INQUIRY-TCP-001", "APPROVAL-INQUIRY-001");
        approvalRepository.saveAndFlush(approval);

        InquiryRequestMessage request = new InquiryRequestMessage(
                "1",
                "INQUIRY",
                "REQ-INQUIRY-TCP-001",
                InquiryTargetType.APPROVAL,
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
        assertThat(response.targetType()).isEqualTo(request.targetType());
        assertThat(response.targetTrxNo()).isEqualTo(request.targetTrxNo());
        assertThat(response.targetAttemptSeq()).isEqualTo(request.targetAttemptSeq());
        assertThat(response.resultCode()).isEqualTo(InquiryResultCode.SUCCESS);
        assertThat(response.status()).isEqualTo(InquiryResponseStatus.APPROVED);
        assertThat(response.vanTrxId()).isEqualTo("VAN-INQUIRY-TCP-001");
        assertThat(response.approvalNo()).isEqualTo("APPROVAL-INQUIRY-001");
        assertThat(response.cancelApprovalNo()).isNull();
        assertThat(approvalRepository.count()).isEqualTo(1);
    }

    @Test
    void length_prefixed_JSON_CANCEL_요청을_TCP로_처리하고_CANCELLED_응답과_원장을_생성한다() throws Exception {
        // given
        VanApproval originalApproval =
                approvedOriginalApproval("VAN-APPROVAL-CANCEL-001", "APPROVAL-CANCEL-001");
        approvalRepository.saveAndFlush(originalApproval);

        CancelRequestMessage request = validCancelRequest();

        /*
        시작 전에 approvedOriginalApproval()을 saveAndFlush()하는 이유는
        취소 대상 원승인이 실제 PostgreSQL에 존재해야 하기 때문.
        취소 서비스는 이 row를 FOR UPDATE로 잡고 검증하니까, 단순 mock이 아니라 실제 DB row가 필요.
         */
        byte[] requestPayload = objectMapper.writeValueAsBytes(request);

        // when
        byte[] responsePayload = sendLengthPrefixedRequest(requestPayload);

        CancelResponseMessage response =
                objectMapper.readValue(
                        responsePayload,
                        CancelResponseMessage.class
                );

        // then - TCP response
        assertThat(response.protocolVersion()).isEqualTo("1");
        assertThat(response.messageType()).isEqualTo("CANCEL_RESPONSE");
        assertThat(response.requestId()).isEqualTo(request.requestId());
        assertThat(response.cancelPosTrx()).isEqualTo(request.cancelPosTrx());
        assertThat(response.originalPosTrx()).isEqualTo(request.originalPosTrx());
        assertThat(response.originalAttemptSeq()).isEqualTo(request.originalAttemptSeq());
        assertThat(response.cancelStatus()).isEqualTo(VanCancelStatus.CANCELLED);
        assertThat(response.resultCode()).isEqualTo(CancelResultCode.SUCCESS);
        assertThat(response.vanCancelTrxId()).isNotBlank();
        assertThat(response.cancelApprovalNo()).isNotBlank();
        assertThat(response.declineCode()).isNull();

        // then - VAN Cancel ledger
        assertThat(cancelRepository.count()).isEqualTo(1);

        VanCancel storedCancel =
                cancelRepository
                        .findByOriginalPosTrxAndOriginalAttemptSeq(
                                request.originalPosTrx(),
                                request.originalAttemptSeq()
                        )
                        .orElseThrow();

        assertThat(storedCancel.getCancelPosTrx()).isEqualTo(request.cancelPosTrx());
        assertThat(storedCancel.getCancelStatus()).isEqualTo(VanCancelStatus.CANCELLED);
        assertThat(storedCancel.getVanCancelTrxId()).isEqualTo(response.vanCancelTrxId());
        assertThat(storedCancel.getCancelApprovalNo()).isEqualTo(response.cancelApprovalNo());
        assertThat(storedCancel.getDeclineCode()).isNull();

        // Cancel은 기존 Approval fact를 변경하지 않는다.
        VanApproval storedOriginal =
                approvalRepository.findByPosTrxAndAttemptSeq(
                        request.originalPosTrx(),
                        request.originalAttemptSeq()
                ).orElseThrow();

        assertThat(storedOriginal.getApprovalStatus()).isEqualTo(VanApprovalStatus.APPROVED);
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

    /**
     * Cancel TCP 테스트에서 취소 대상이 되는 원승인 원장 fixture다.
     *
     * <p>
     * CancelServiceImpl은 CANCEL 요청을 처리할 때 originalPosTrx + originalAttemptSeq로 이 row를 찾고,
     * PESSIMISTIC_WRITE lock을 잡은 뒤 APPROVED 상태인지 확인한다.
     *
     * <p>
     * vanTrxNo와 approvalNo를 파라미터로 받는 이유:
     * - Inquiry 테스트와 Cancel 테스트가 같은 helper를 재사용하되 서로 다른 VAN 거래번호/승인번호를 쓰게 한다.
     * - Cancel 요청의 originalVanTrxId/originalApprovalNo가 이 값과 일치해야 ORIGINAL_MISMATCH가 아니라
     *   정상 CANCELLED 흐름으로 내려간다.
     */
    private static VanApproval approvedOriginalApproval(String vanTrxNo, String approvalNo) {
        return VanApproval.builder()
                // 원승인을 VAN 내부에서 식별하는 거래번호. Cancel 요청의 originalVanTrxId와 같아야 한다.
                .vanTrxId(vanTrxNo)
                // 원승인의 POS 거래번호. validCancelRequest().originalPosTrx와 같은 값이다.
                .posTrx("2301-20260808-9999-0001")
                // 원승인의 승인 시도 순번. validCancelRequest().originalAttemptSeq와 같은 값이다.
                .attemptSeq(1)
                // 현재 Cancel은 전액취소만 검증하므로 요청 amount와 원승인 amount가 같아야 한다.
                .amount(10_000)
                .cardBin("12345678")
                .cardLast4("5678")
                // Cancel 성공 경로는 APPROVED 원승인에 대해서만 열린다.
                .approvalStatus(VanApprovalStatus.APPROVED)
                // 원승인 승인번호. Cancel 요청의 originalApprovalNo와 같아야 한다.
                .approvalNo(approvalNo)
                .processedAt(LocalDateTime.of(2026, 9, 2, 10, 0))
                .build();
    }

    /**
     * 실제 TCP로 전송할 정상 CANCEL 요청 전문 fixture다.
     *
     * <p>
     * 이 객체는 ObjectMapper로 JSON byte[]가 된 뒤 length-prefixed TCP payload로 전송된다.
     * Dispatcher는 messageType=CANCEL을 보고 CancelTcpHandler로 라우팅하고,
     * handler는 이 값을 CancelCommand로 변환해 CancelService.processCancel()을 호출한다.
     *
     * <p>
     * 카드번호/PAN/expiry는 포함하지 않는다.
     * Payment Server가 카드 일치 검증을 끝낸 뒤 VAN에는 원승인 식별 정보만 전달한다는 Cancel TCP 계약을 따른다.
     */
    private static CancelRequestMessage validCancelRequest() {
        return new CancelRequestMessage(
                // Cancel TCP protocol version. handler validation에서 "1"인지 확인한다.
                "1",
                // Dispatcher가 CancelTcpHandler로 라우팅하는 기준값이다.
                "CANCEL",
                // TCP 요청/응답 correlation ID. 응답에서 그대로 echo되어야 한다.
                "REQ-CANCEL-TCP-001",
                // 이번 취소 요청의 POS 거래번호. 저장될 van_cancel.cancel_pos_trx가 된다.
                "2301-20260808-9999-0002",
                // 취소 대상 원승인 POS 거래번호. approvedOriginalApproval().posTrx와 같아야 한다.
                "2301-20260808-9999-0001",
                // 취소 대상 원승인 attemptSeq. approvedOriginalApproval().attemptSeq와 같아야 한다.
                1,
                // 취소 대상 원승인 VAN 거래번호. approvedOriginalApproval("VAN-APPROVAL-CANCEL-001", ...)와 같아야 한다.
                "VAN-APPROVAL-CANCEL-001",
                // 취소 대상 원승인 승인번호. approvedOriginalApproval(..., "APPROVAL-CANCEL-001")와 같아야 한다.
                "APPROVAL-CANCEL-001",
                // 취소 금액. 원승인 amount와 일치해야 정상 CANCELLED로 처리된다.
                10_000
        );
    }

}
