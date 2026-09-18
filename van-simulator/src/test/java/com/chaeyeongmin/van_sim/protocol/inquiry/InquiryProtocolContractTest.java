package com.chaeyeongmin.van_sim.protocol.inquiry;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class InquiryProtocolContractTest {

    @Test
    void reversal_성공_응답_계약을_표현할_수_있다() {
        InquiryResponseMessage response = response(
                InquiryResponseStatus.REVERSED,
                "REVERSAL-APPROVAL-001",
                null
        );

        assertThat(response.targetType()).isEqualTo(InquiryTargetType.REVERSAL);
        assertThat(response.targetTrxNo()).isEqualTo("REVERSAL-TRX-001");
        assertThat(response.targetAttemptSeq()).isNull();
        assertThat(response.status()).isEqualTo(InquiryResponseStatus.REVERSED);
        assertThat(response.reversalApprovalNo()).isEqualTo("REVERSAL-APPROVAL-001");
    }

    @Test
    void reversal_거절_응답_계약을_표현할_수_있다() {
        InquiryResponseMessage response = response(
                InquiryResponseStatus.REVERSAL_DECLINED,
                null,
                "REVERSAL_DECLINED"
        );

        assertThat(response.targetType()).isEqualTo(InquiryTargetType.REVERSAL);
        assertThat(response.status()).isEqualTo(InquiryResponseStatus.REVERSAL_DECLINED);
        assertThat(response.reversalApprovalNo()).isNull();
        assertThat(response.declineCode()).isEqualTo("REVERSAL_DECLINED");
    }

    private static InquiryResponseMessage response(
            InquiryResponseStatus status,
            String reversalApprovalNo,
            String declineCode
    ) {
        return InquiryResponseMessage.builder()
                .protocolVersion("1")
                .messageType("INQUIRY_RESPONSE")
                .requestId("REQUEST-001")
                .targetType(InquiryTargetType.REVERSAL)
                .targetTrxNo("REVERSAL-TRX-001")
                .targetAttemptSeq(null)
                .resultCode(InquiryResultCode.SUCCESS)
                .vanTrxId("VAN-REVERSAL-001")
                .status(status)
                .approvalNo(null)
                .cancelApprovalNo(null)
                .reversalApprovalNo(reversalApprovalNo)
                .declineCode(declineCode)
                .build();
    }
}
