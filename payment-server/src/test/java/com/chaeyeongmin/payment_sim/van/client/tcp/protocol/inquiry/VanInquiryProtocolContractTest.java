package com.chaeyeongmin.payment_sim.van.client.tcp.protocol.inquiry;

import com.chaeyeongmin.payment_sim.van.client.dto.VanInquiryResultCode;
import com.chaeyeongmin.payment_sim.van.client.dto.VanInquiryResponse;
import com.chaeyeongmin.payment_sim.van.client.dto.VanInquiryTargetType;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class VanInquiryProtocolContractTest {

    @Test
    void reversal_업무_응답_DTO가_reversalApprovalNo를_표현할_수_있다() {
        VanInquiryResponse response = VanInquiryResponse.builder()
                .targetType(VanInquiryTargetType.REVERSAL)
                .targetTrxNo("REVERSAL-TRX-001")
                .targetAttemptSeq(null)
                .resultCode(VanInquiryResultCode.SUCCESS)
                .status(VanInquiryStatus.REVERSED)
                .reversalApprovalNo("REVERSAL-APPROVAL-001")
                .build();

        assertThat(response.targetType()).isEqualTo(VanInquiryTargetType.REVERSAL);
        assertThat(response.status()).isEqualTo(VanInquiryStatus.REVERSED);
        assertThat(response.reversalApprovalNo()).isEqualTo("REVERSAL-APPROVAL-001");
    }

    @Test
    void reversal_성공_응답_계약을_표현할_수_있다() {
        VanInquiryTcpResponse response = response(
                VanInquiryStatus.REVERSED,
                "REVERSAL-APPROVAL-001",
                null
        );

        assertThat(response.targetType()).isEqualTo(VanInquiryTargetType.REVERSAL);
        assertThat(response.targetTrxNo()).isEqualTo("REVERSAL-TRX-001");
        assertThat(response.targetAttemptSeq()).isNull();
        assertThat(response.status()).isEqualTo(VanInquiryStatus.REVERSED);
        assertThat(response.reversalApprovalNo()).isEqualTo("REVERSAL-APPROVAL-001");
    }

    @Test
    void reversal_거절_응답_계약을_표현할_수_있다() {
        VanInquiryTcpResponse response = response(
                VanInquiryStatus.REVERSAL_DECLINED,
                null,
                "REVERSAL_DECLINED"
        );

        assertThat(response.targetType()).isEqualTo(VanInquiryTargetType.REVERSAL);
        assertThat(response.status()).isEqualTo(VanInquiryStatus.REVERSAL_DECLINED);
        assertThat(response.reversalApprovalNo()).isNull();
        assertThat(response.declineCode()).isEqualTo("REVERSAL_DECLINED");
    }

    private static VanInquiryTcpResponse response(
            VanInquiryStatus status,
            String reversalApprovalNo,
            String declineCode
    ) {
        return new VanInquiryTcpResponse(
                "1",
                "INQUIRY_RESPONSE",
                "REQUEST-001",
                VanInquiryTargetType.REVERSAL,
                "REVERSAL-TRX-001",
                null,
                VanInquiryResultCode.SUCCESS,
                "VAN-REVERSAL-001",
                status,
                null,
                null,
                reversalApprovalNo,
                declineCode,
                LocalDateTime.of(2026, 9, 11, 10, 0)
        );
    }
}
