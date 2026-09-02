package com.chaeyeongmin.van_sim.transaction.cancel.tcp;

import com.chaeyeongmin.van_sim.ledger.cancel.status.CancelResultCode;
import com.chaeyeongmin.van_sim.ledger.cancel.status.VanCancelStatus;
import com.chaeyeongmin.van_sim.protocol.cancel.CancelRequestMessage;
import com.chaeyeongmin.van_sim.protocol.cancel.CancelResponseMessage;
import com.chaeyeongmin.van_sim.transaction.cancel.CancelService;
import com.chaeyeongmin.van_sim.transaction.cancel.service.command.CancelCommand;
import com.chaeyeongmin.van_sim.transaction.cancel.service.result.CancelResult;
import com.chaeyeongmin.van_sim.transaction.cancel.tcp.exception.CancelTcpMessageException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CancelTcpHandlerTest {

    @Mock
    private CancelService cancelService;

    private CancelTcpMessageMapper mapper;
    private ObjectMapper objectMapper;
    private CancelTcpHandler handler;

    @BeforeEach
    void setUp() {
        mapper = new CancelTcpMessageMapper();

        objectMapper = JsonMapper.builder()
                .addModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .build();

        handler = new CancelTcpHandler(
                objectMapper,
                mapper,
                cancelService
        );
    }

    @Test
    void 정상_CANCEL_payload를_처리하고_CANCEL_RESPONSE_payload를_반환한다() throws Exception {
        // given
        // request:
        // - Payment Server가 TCP로 보낼 CANCEL 요청 전문을 Java 객체로 만든 fixture다.
        // - 아직 JSON byte[]가 아니고, protocolVersion/messageType/requestId 같은 TCP 계약 필드와
        //   cancelPosTrx/original* 같은 취소 업무 필드를 모두 담고 있다.
        CancelRequestMessage request = validRequest();

        // result:
        // - CancelService.processCancel()이 반환한다고 가정하는 서비스 계층 결과다.
        // - 이 handler 테스트의 관심사는 실제 취소 원장 저장이 아니라,
        //   service result가 CANCEL_RESPONSE 전문으로 잘 변환되는지다.
        CancelResult result = successResult(request);

        // payload:
        // - 실제 TCP dispatcher가 CancelTcpHandler.handle()에 넘기는 JSON body byte[]에 해당한다.
        // - Spring TCP length header는 이미 제거된 상태라고 보고, handler는 이 payload를 request DTO로 역직렬화한다.
        byte[] payload = objectMapper.writeValueAsBytes(request);

        // mock service:
        // - handler가 어떤 CancelCommand를 만들든, service는 위에서 준비한 result를 돌려주게 한다.
        // - command 필드 검증은 ArgumentCaptor나 argThat으로 별도 assertion을 붙이면 된다.
        when(cancelService.processCancel(any(CancelCommand.class))).thenReturn(result);

        // responsePayload:
        // - handler 전체 흐름의 결과물이다.
        // - payload -> CancelRequestMessage -> CancelCommand -> CancelService -> CancelResult
        //   -> CancelResponseMessage -> JSON byte[] 변환이 끝난 값이다.
        byte[] responsePayload = handler.handle(payload);

        // response:
        // - assertion을 쉽게 하기 위해 응답 JSON byte[]를 다시 DTO로 읽은 값이다.
        // - 여기서 requestId 유지, messageType=CANCEL_RESPONSE, resultCode 전달 여부를 검증한다.
        CancelResponseMessage response = objectMapper.readValue(responsePayload, CancelResponseMessage.class);

        // then
        // ArgumentCaptor:
        // - mock service에 실제로 전달된 CancelCommand를 꺼내 보기 위한 Mockito 도구다.
        // - any(CancelCommand.class)만 쓰면 handler가 command를 잘못 만들어도 service mock이 result를 반환하므로
        //   JSON -> CancelRequestMessage -> CancelCommand 매핑 오류를 놓칠 수 있다.
        ArgumentCaptor<CancelCommand> captor = ArgumentCaptor.forClass(CancelCommand.class);

        // service가 정확히 1회 호출됐는지와 동시에, 그때 넘어간 command를 captor에 저장한다.
        verify(cancelService, times(1)).processCancel(captor.capture());

        // command:
        // - CancelTcpHandler가 CancelRequestMessage를 service 계층 입력으로 변환한 결과다.
        // - protocolVersion/messageType/requestId는 TCP 계층 필드라 command에 없고,
        //   취소 업무 처리에 필요한 거래번호/원승인 식별자/금액만 들어가야 한다.
        CancelCommand command = captor.getValue();
        assertThat(command.cancelPosTrx()).isEqualTo(request.cancelPosTrx());
        assertThat(command.originalPosTrx()).isEqualTo(request.originalPosTrx());
        assertThat(command.originalAttemptSeq()).isEqualTo(request.originalAttemptSeq());
        assertThat(command.originalVanTrxId()).isEqualTo(request.originalVanTrxId());
        assertThat(command.originalApprovalNo()).isEqualTo(request.originalApprovalNo());
        assertThat(command.amount()).isEqualTo(request.amount());

        assertThat(response.protocolVersion()).isEqualTo("1");
        assertThat(response.messageType()).isEqualTo("CANCEL_RESPONSE");
        assertThat(response.requestId()).isEqualTo(request.requestId());
        assertThat(response.cancelPosTrx()).isEqualTo(result.cancelPosTrx());
        assertThat(response.originalPosTrx()).isEqualTo(result.originalPosTrx());
        assertThat(response.originalAttemptSeq()).isEqualTo(result.originalAttemptSeq());
        assertThat(response.vanCancelTrxId()).isEqualTo(result.vanCancelTrxId());
        assertThat(response.cancelStatus()).isEqualTo(result.cancelStatus());
        assertThat(response.resultCode()).isEqualTo(result.resultCode());
        assertThat(response.cancelApprovalNo()).isEqualTo(result.cancelApprovalNo());
        assertThat(response.declineCode()).isEqualTo(result.declineCode());
    }

    @Test
    void protocolVersion이_1이_아니면_CancelTcpMessageException을_던지고_service를_호출하지_않는다() throws Exception {
        // given
        // request:
        // - messageType과 업무 필드는 정상이고 protocolVersion만 깨뜨린 요청이다.
        // - handler validation에서 막혀야 하므로 CancelCommand 변환과 service 호출까지 내려가면 안 된다.
        CancelRequestMessage request = requestWith(
                "2",
                "CANCEL",
                "REQ-CANCEL-001",
                "2301-20260808-9999-0002",
                "2301-20260808-9999-0001",
                1,
                "VAN-APPROVAL-001",
                "APPROVAL-001",
                10_000
        );

        // payload:
        // - JSON 자체는 정상이다.
        // - 따라서 기대 예외는 DESERIALIZE_FAILED가 아니라 CANCEL_TCP_REQUEST_INVALID다.
        byte[] payload = objectMapper.writeValueAsBytes(request);

        assertThatThrownBy(() -> {
            handler.handle(payload);
        })
            .isInstanceOf(CancelTcpMessageException.class)
            .hasMessage("CANCEL_TCP_REQUEST_INVALID");
        verifyNoInteractions(cancelService);
    }

    @Test
    void messageType이_CANCEL이_아니면_CancelTcpMessageException을_던지고_service를_호출하지_않는다() throws Exception {
        // given
        // request:
        // - protocolVersion은 지원 버전이지만 messageType이 CANCEL이 아닌 요청이다.
        // - dispatcher가 잘못 라우팅했거나 handler를 직접 호출한 경우에도 handler가 한 번 더 방어해야 한다.
        CancelRequestMessage request = requestWith(
                "1",
                "APPROVAL",
                "REQ-CANCEL-001",
                "2301-20260808-9999-0002",
                "2301-20260808-9999-0001",
                1,
                "VAN-APPROVAL-001",
                "APPROVAL-001",
                10_000
        );

        // payload:
        // - 역직렬화는 성공해야 하고, validation 단계에서 CANCEL_TCP_REQUEST_INVALID로 중단되어야 한다.
        byte[] payload = objectMapper.writeValueAsBytes(request);

        assertThatThrownBy(() -> {
            handler.handle(payload);
        })
                .isInstanceOf(CancelTcpMessageException.class)
                .hasMessage("CANCEL_TCP_REQUEST_INVALID");
        verifyNoInteractions(cancelService);
    }

    // 하나의 테스트 메서드를 invalidRequiredFields()가 제공하는 여러 입력값으로 반복 실행한다.
    // name = "{0}"은 Arguments.of(...)의 첫 번째 값(caseName)을 테스트 리포트 이름으로 쓰겠다는 뜻이다.
    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidRequiredFields")
    void 필수값이_invalid이면_CancelTcpMessageException을_던지고_service를_호출하지_않는다(
            String caseName, // - 어떤 필수 필드를 깨뜨린 케이스인지 테스트 리포트에 표시하기 위한 이름이다.
            CancelRequestMessage request
    ) throws Exception {
        // given
        // request:
        // - validRequest()와 같은 정상 전문에서 한 필드만 invalid하게 만든 요청이다.
        // - Cancel TCP에는 PAN/expiry가 없으므로 cancelPosTrx, original*, amount만 boundary에서 검증한다.
        // payload:
        // - JSON 문법은 정상이어야 한다. 이 테스트는 JSON 파싱 실패가 아니라 필드 validation 실패를 본다.
        byte[] payload = objectMapper.writeValueAsBytes(request);

        // then
        assertThatThrownBy(() -> {
            handler.handle(payload);
        })
                .isInstanceOf(CancelTcpMessageException.class)
                .hasMessage("CANCEL_TCP_REQUEST_INVALID");
        verifyNoInteractions(cancelService);
    }

    @Test
    void malformed_JSON이면_역직렬화_실패_예외를_던지고_service를_호출하지_않는다() {
        // given
        // payload:
        // - CancelRequestMessage 객체로 만들 수 없는 깨진 JSON byte[]다.
        // - 이 경우는 validation까지 가지 못하고 readCancelRequest()에서 DESERIALIZE_FAILED로 중단되어야 한다.
        // - request/result/response 변수는 존재하지 않는다. JSON을 요청 DTO로 읽는 것 자체가 실패하기 때문이다.
        byte[] payload = malformedJsonPayload();

        // then
        assertThatThrownBy(() -> {
            handler.handle(payload);
        })
                .isInstanceOf(CancelTcpMessageException.class)
                .hasMessage("CANCEL_TCP_REQUEST_DESERIALIZE_FAILED");
        verifyNoInteractions(cancelService);
    }

    /**
     * Cancel TCP handler가 막아야 하는 필수값 invalid 케이스들이다.
     *
     * <p>
     * Cancel 전문에는 PAN/expiry/cardLast4가 없다.
     * 이 테스트는 취소 업무에 필요한 거래번호, 원승인 식별자, 금액만 protocol boundary에서 검증한다.
     */
    private static Stream<Arguments> invalidRequiredFields() {
        return Stream.of(
                // requestId는 업무 원장 key는 아니지만, TCP 요청/응답 correlation에 필수다.
                // 비어 있으면 Payment Server가 어떤 요청의 응답인지 확인할 수 없다.
                Arguments.of("blank requestId", requestWith("1", "CANCEL", "  ", "2301-20260808-9999-0002", "2301-20260808-9999-0001", 1, "VAN-APPROVAL-001", "APPROVAL-001", 10_000)),

                // cancelPosTrx는 이번 취소 요청의 현거래번호다.
                // blank 값이나 존재하지 않는 날짜가 포함된 값은 VAN 취소 원장 key로 사용할 수 없다.
                Arguments.of("blank cancelPosTrx", requestWith("1", "CANCEL", "REQ-CANCEL-001", "  ", "2301-20260808-9999-0001", 1, "VAN-APPROVAL-001", "APPROVAL-001", 10_000)),
                Arguments.of("invalid cancelPosTrx date", requestWith("1", "CANCEL", "REQ-CANCEL-001", "2301-20260230-9999-0002", "2301-20260808-9999-0001", 1, "VAN-APPROVAL-001", "APPROVAL-001", 10_000)),

                // originalPosTrx는 취소 대상 원승인 거래번호다.
                // 형식이나 날짜가 깨지면 원승인 row lock/조회 대상으로 내려가면 안 된다.
                Arguments.of("invalid originalPosTrx", requestWith("1", "CANCEL", "REQ-CANCEL-001", "2301-20260808-9999-0002", "2301-20260808-9999-1", 1, "VAN-APPROVAL-001", "APPROVAL-001", 10_000)),
                Arguments.of("invalid originalPosTrx date", requestWith("1", "CANCEL", "REQ-CANCEL-001", "2301-20260808-9999-0002", "2301-20260230-9999-0001", 1, "VAN-APPROVAL-001", "APPROVAL-001", 10_000)),

                // attemptSeq는 원승인의 시도 순번이므로 1 이상의 값이어야 한다.
                Arguments.of("zero originalAttemptSeq", requestWith("1", "CANCEL", "REQ-CANCEL-001", "2301-20260808-9999-0002", "2301-20260808-9999-0001", 0, "VAN-APPROVAL-001", "APPROVAL-001", 10_000)),
                Arguments.of("negative originalAttemptSeq", requestWith("1", "CANCEL", "REQ-CANCEL-001", "2301-20260808-9999-0002", "2301-20260808-9999-0001", -1, "VAN-APPROVAL-001", "APPROVAL-001", 10_000)),

                // 원승인 VAN 거래번호와 승인번호는 원승인 payload 일치 검증에 필요하다.
                Arguments.of("blank originalVanTrxId", requestWith("1", "CANCEL", "REQ-CANCEL-001", "2301-20260808-9999-0002", "2301-20260808-9999-0001", 1, "  ", "APPROVAL-001", 10_000)),
                Arguments.of("blank originalApprovalNo", requestWith("1", "CANCEL", "REQ-CANCEL-001", "2301-20260808-9999-0002", "2301-20260808-9999-0001", 1, "VAN-APPROVAL-001", "  ", 10_000)),

                // 현재 Cancel은 전액취소만 지원하므로 amount는 양수여야 한다.
                Arguments.of("zero amount", requestWith("1", "CANCEL", "REQ-CANCEL-001", "2301-20260808-9999-0002", "2301-20260808-9999-0001", 1, "VAN-APPROVAL-001", "APPROVAL-001", 0)),
                Arguments.of("negative amount", requestWith("1", "CANCEL", "REQ-CANCEL-001", "2301-20260808-9999-0002", "2301-20260808-9999-0001", 1, "VAN-APPROVAL-001", "APPROVAL-001", -1))
        );
    }

    /**
     * 정상 취소 전문의 기준값이다.
     */
    private static CancelRequestMessage validRequest() {
        return requestWith(
                "1",
                "CANCEL",
                "REQ-CANCEL-001",
                "2301-20260808-9999-0002",
                "2301-20260808-9999-0001",
                1,
                "VAN-APPROVAL-001",
                "APPROVAL-001",
                10_000
        );
    }

    /**
     * 테스트 데이터 생성을 한곳으로 모아, 각 케이스가 어떤 필드를 깨뜨리는지만 보이게 한다.
     */
    private static CancelRequestMessage requestWith(
            String protocolVersion,
            String messageType,
            String requestId,
            String cancelPosTrx,
            String originalPosTrx,
            int originalAttemptSeq,
            String originalVanTrxId,
            String originalApprovalNo,
            int amount
    ) {
        return new CancelRequestMessage(
                protocolVersion,
                messageType,
                requestId,
                cancelPosTrx,
                originalPosTrx,
                originalAttemptSeq,
                originalVanTrxId,
                originalApprovalNo,
                amount
        );
    }

    /**
     * 정상 취소 성공 service result fixture다.
     */
    private static CancelResult successResult(CancelRequestMessage request) {
        return new CancelResult(
                "VAN-CANCEL-001",
                request.cancelPosTrx(),
                request.originalPosTrx(),
                request.originalAttemptSeq(),
                VanCancelStatus.CANCELLED,
                CancelResultCode.SUCCESS,
                "CANCEL-APPROVAL-001",
                null,
                LocalDateTime.of(2026, 9, 2, 10, 0)
        );
    }

    /**
     * ObjectMapper가 CancelRequestMessage로 읽을 수 없는 깨진 JSON payload다.
     */
    private static byte[] malformedJsonPayload() {
        return """
                {"protocolVersion":"1","messageType":"CANCEL",
                """.getBytes();
    }
}
