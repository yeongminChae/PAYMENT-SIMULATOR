package com.chaeyeongmin.payment_sim.van.client.assembler;

import com.chaeyeongmin.payment_sim.van.client.dto.VanInquiryRequest;
import com.chaeyeongmin.payment_sim.van.client.dto.VanInquiryTargetType;
import com.chaeyeongmin.payment_sim.van.client.policy.VanTraceIdPolicy;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class VanInquiryAssemblerTest {

    private final VanInquiryAssembler assembler = new VanInquiryAssembler(new VanTraceIdPolicy());

    @Test
    void 승인_조회_요청은_APPROVAL_target_계약으로_생성한다() {
        VanInquiryRequest request = assembler.getVanInquiryRequest(
                "2301-20260808-9999-0001",
                1,
                "4242",
                "VAN-APPROVAL-001"
        );

        assertThat(request.targetType()).isEqualTo(VanInquiryTargetType.APPROVAL);
        assertThat(request.targetTrxNo()).isEqualTo("2301-20260808-9999-0001");
        assertThat(request.targetAttemptSeq()).isEqualTo(1);
        assertThat(request.vanTrxId()).isEqualTo("VAN-APPROVAL-001");
        assertThat(request.cardLast4()).isEqualTo("4242");
    }

    @Test
    void 취소_조회_요청은_CANCEL_target_계약으로_생성하고_승인_전용값은_비운다() {
        VanInquiryRequest request = assembler.getCancelInquiryRequest(
                "2301-20260808-9999-0002"
        );

        assertThat(request.targetType()).isEqualTo(VanInquiryTargetType.CANCEL);
        assertThat(request.targetTrxNo()).isEqualTo("2301-20260808-9999-0002");
        assertThat(request.targetAttemptSeq()).isNull();
        assertThat(request.vanTrxId()).isNull();
        assertThat(request.cardLast4()).isNull();
    }
}
