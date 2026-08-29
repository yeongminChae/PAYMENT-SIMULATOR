package com.chaeyeongmin.van_sim.transaction.approval.tcp;

import com.chaeyeongmin.van_sim.ledger.approval.status.VanApprovalStatus;
import com.chaeyeongmin.van_sim.protocol.approval.ApprovalRequestMessage;
import com.chaeyeongmin.van_sim.protocol.approval.ApprovalResponseMessage;
import com.chaeyeongmin.van_sim.protocol.approval.ApprovalResponseStatus;
import com.chaeyeongmin.van_sim.transaction.approval.service.command.ApprovalCommand;
import com.chaeyeongmin.van_sim.transaction.approval.service.result.ApprovalResult;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class ApprovalTcpMessageMapperTest {

    private final ApprovalTcpMessageMapper mapper = new ApprovalTcpMessageMapper();

    @Test
    void 승인_요청_전문을_Command로_변환하면_BIN과_last4만_전달된다() {
        // given: PAN 전체 값이 포함된 승인 요청 전문
        ApprovalRequestMessage request = newRequest();

        // when
        ApprovalCommand command = mapper.toCommand(request);

        // then: 서비스 계층에는 카드 BIN과 마지막 4자리만 전달한다.
        assertThat(command.posTrx()).isEqualTo(request.posTrx());
        assertThat(command.attemptSeq()).isEqualTo(request.attemptSeq());
        assertThat(command.amount()).isEqualTo(request.amount());
        assertThat(command.cardBin()).isEqualTo("12345678");
        assertThat(command.cardLast4()).isEqualTo("5678");
    }

    @Test
    void 승인_결과를_응답_전문으로_변환한다() {
        // given: 승인 완료 처리 결과
        ApprovalRequestMessage request = newRequest();
        ApprovalResult result = newResult(
                VanApprovalStatus.APPROVED,
                "APPROVAL-TEST-001",
                null
        );

        // when
        ApprovalResponseMessage response = mapper.toResponse(request, result);

        // then: 요청 식별자와 승인 결과 값을 응답 전문에 유지한다.
        assertThat(response.protocolVersion()).isEqualTo("1");
        assertThat(response.messageType()).isEqualTo("APPROVAL_RESPONSE");
        assertThat(response.requestId()).isEqualTo(request.requestId());
        assertThat(response.posTrx()).isEqualTo(request.posTrx());
        assertThat(response.attemptSeq()).isEqualTo(request.attemptSeq());
        assertThat(response.vanTrxId()).isEqualTo(result.vanTrxId());
        assertThat(response.status()).isEqualTo(ApprovalResponseStatus.APPROVED);
        assertThat(response.approvalNo()).isEqualTo(result.approvalNo());
        assertThat(response.declineCode()).isNull();
        assertThat(response.respondedAt()).isNotNull();
    }

    @Test
    void 승인_결과_상태를_응답_프로토콜_상태로_변환한다() {
        ApprovalRequestMessage request = newRequest();

        assertThat(mapper.toResponse(
                request,
                newResult(VanApprovalStatus.APPROVED, "APPROVAL-TEST-001", null)
        ).status()).isEqualTo(ApprovalResponseStatus.APPROVED);

        assertThat(mapper.toResponse(
                request,
                newResult(VanApprovalStatus.DECLINED, null, "D001")
        ).status()).isEqualTo(ApprovalResponseStatus.DECLINED);

        assertThat(mapper.toResponse(
                request,
                newResult(VanApprovalStatus.UNKNOWN, null, null)
        ).status()).isEqualTo(ApprovalResponseStatus.UNKNOWN);
    }

    private ApprovalRequestMessage newRequest() {
        return new ApprovalRequestMessage(
                "1",
                "APPROVAL",
                "REQ-TEST-001",
                "2301-20260808-9999-0001",
                1,
                10_000,
                "1234567812345678",
                "2812"
        );
    }

    private ApprovalResult newResult(
            VanApprovalStatus status,
            String approvalNo,
            String declineCode
    ) {
        return new ApprovalResult(
                "VAN-TEST-001",
                "2301-20260808-9999-0001",
                1,
                status,
                approvalNo,
                declineCode,
                LocalDateTime.of(2026, 8, 13, 10, 0)
        );
    }

}
