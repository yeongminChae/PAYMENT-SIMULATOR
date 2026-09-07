package com.chaeyeongmin.van_sim.transaction.reversal.tcp;

import com.chaeyeongmin.van_sim.ledger.reversal.status.ReversalResultCode;
import com.chaeyeongmin.van_sim.ledger.reversal.status.VanReversalStatus;
import com.chaeyeongmin.van_sim.protocol.reversal.ReversalRequestMessage;
import com.chaeyeongmin.van_sim.protocol.reversal.ReversalResponseMessage;
import com.chaeyeongmin.van_sim.transaction.reversal.service.command.ReversalCommand;
import com.chaeyeongmin.van_sim.transaction.reversal.service.result.ReversalResult;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class ReversalTcpMessageMapperTest {

    private final ReversalTcpMessageMapper mapper = new ReversalTcpMessageMapper();

    @Test
    void REVERSAL_request를_command로_변환한다() {
        ReversalRequestMessage request = validRequest();

        ReversalCommand command = mapper.toCommand(request);

        assertThat(command.reversalPosTrx()).isEqualTo(request.reversalPosTrx());
        assertThat(command.originalPosTrx()).isEqualTo(request.originalPosTrx());
        assertThat(command.originalAttemptSeq()).isEqualTo(request.originalAttemptSeq());
        assertThat(command.amount()).isEqualTo(request.amount());
    }

    @Test
    void SUCCESS_결과를_REVERSAL_RESPONSE로_변환한다() {
        ReversalRequestMessage request = validRequest();
        ReversalResult result = result(
                request.reversalPosTrx(),
                VanReversalStatus.REVERSED,
                ReversalResultCode.SUCCESS,
                "REVERSAL-APPROVAL-001",
                null
        );

        ReversalResponseMessage response = mapper.toResponse(request, result);

        assertCommon(response, request, result);
        assertThat(response.reversalStatus()).isEqualTo(VanReversalStatus.REVERSED);
        assertThat(response.resultCode()).isEqualTo(ReversalResultCode.SUCCESS);
        assertThat(response.reversalApprovalNo()).isEqualTo("REVERSAL-APPROVAL-001");
        assertThat(response.declineCode()).isNull();
    }

    @Test
    void ALREADY_REVERSED_결과도_현재_요청_correlation을_유지한다() {
        ReversalRequestMessage request = validRequest();
        ReversalResult result = result(
                request.reversalPosTrx(),
                VanReversalStatus.REVERSED,
                ReversalResultCode.ALREADY_REVERSED,
                "REVERSAL-APPROVAL-001",
                null
        );

        ReversalResponseMessage response = mapper.toResponse(request, result);

        assertCommon(response, request, result);
        assertThat(response.requestId()).isEqualTo(request.requestId());
        assertThat(response.reversalPosTrx()).isEqualTo(request.reversalPosTrx());
        assertThat(response.resultCode()).isEqualTo(ReversalResultCode.ALREADY_REVERSED);
    }

    @Test
    void REVERSAL_DECLINED_상태_결과를_응답으로_변환한다() {
        ReversalRequestMessage request = validRequest();
        ReversalResult result = result(
                request.reversalPosTrx(),
                VanReversalStatus.REVERSAL_DECLINED,
                ReversalResultCode.ORIGINAL_MISMATCH,
                null,
                "R003"
        );

        ReversalResponseMessage response = mapper.toResponse(request, result);

        assertCommon(response, request, result);
        assertThat(response.reversalStatus()).isEqualTo(VanReversalStatus.REVERSAL_DECLINED);
        assertThat(response.resultCode()).isEqualTo(ReversalResultCode.ORIGINAL_MISMATCH);
        assertThat(response.reversalApprovalNo()).isNull();
        assertThat(response.declineCode()).isEqualTo("R003");
    }

    private static void assertCommon(
            ReversalResponseMessage response,
            ReversalRequestMessage request,
            ReversalResult result
    ) {
        assertThat(response.protocolVersion()).isEqualTo("1");
        assertThat(response.messageType()).isEqualTo("REVERSAL_RESPONSE");
        assertThat(response.requestId()).isEqualTo(request.requestId());
        assertThat(response.reversalPosTrx()).isEqualTo(result.reversalPosTrx());
        assertThat(response.originalPosTrx()).isEqualTo(result.originalPosTrx());
        assertThat(response.originalAttemptSeq()).isEqualTo(result.originalAttemptSeq());
        assertThat(response.vanReversalTrxId()).isEqualTo(result.vanReversalTrxId());
    }

    private static ReversalRequestMessage validRequest() {
        return new ReversalRequestMessage(
                "1",
                "REVERSAL",
                "REQ-REVERSAL-001",
                "2301-20260808-9999-0002",
                "2301-20260808-9999-0001",
                1,
                10_000
        );
    }

    private static ReversalResult result(
            String reversalPosTrx,
            VanReversalStatus status,
            ReversalResultCode resultCode,
            String reversalApprovalNo,
            String declineCode
    ) {
        return new ReversalResult(
                "VAN-REVERSAL-001",
                reversalPosTrx,
                "2301-20260808-9999-0001",
                1,
                10_000,
                status,
                resultCode,
                reversalApprovalNo,
                declineCode,
                LocalDateTime.of(2026, 9, 4, 10, 0)
        );
    }
}
