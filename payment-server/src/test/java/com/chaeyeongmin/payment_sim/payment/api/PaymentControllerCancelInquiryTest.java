package com.chaeyeongmin.payment_sim.payment.api;

import com.chaeyeongmin.payment_sim.payment.api.cancel.CancelResultStatus;
import com.chaeyeongmin.payment_sim.payment.api.inquiry.CancelInquiryRequest;
import com.chaeyeongmin.payment_sim.payment.api.cancel.CancelResponse;
import com.chaeyeongmin.payment_sim.payment.api.mapper.PaymentApiResponseMapper;
import com.chaeyeongmin.payment_sim.payment.application.approval.service.PaymentApprovalService;
import com.chaeyeongmin.payment_sim.payment.application.inquiry.service.PaymentCancelInquiryService;
import com.chaeyeongmin.payment_sim.payment.application.cancel.service.PaymentCancelService;
import com.chaeyeongmin.payment_sim.payment.application.inquiry.service.PaymentInquiryService;
import com.chaeyeongmin.payment_sim.payment.application.reversal.service.PaymentReversalService;
import com.chaeyeongmin.payment_sim.common.api.ApiResponse;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * /cancel/inquiry HTTP boundary가 기존 /cancel service로 흐르지 않는지 확인한다.
 */
class PaymentControllerCancelInquiryTest {

    @Test
    void cancel_inquiry_요청을_PaymentCancelInquiryService로_위임하고_CancelResponse를_반환한다() {
        PaymentApprovalService approvalService = mock(PaymentApprovalService.class);
        PaymentInquiryService inquiryService = mock(PaymentInquiryService.class);
        PaymentCancelService cancelService = mock(PaymentCancelService.class);
        PaymentCancelInquiryService cancelInquiryService = mock(PaymentCancelInquiryService.class);
        PaymentReversalService reversalService = mock(PaymentReversalService.class);
        PaymentController controller = new PaymentController(
                approvalService,
                inquiryService,
                cancelService,
                cancelInquiryService,
                reversalService,
                new PaymentApiResponseMapper()
        );

        CancelInquiryRequest request = new CancelInquiryRequest("2301-20260808-9999-0002");
        CancelResponse serviceResponse = CancelResponse.retryLater(
                request.posTrx(),
                "2301-20260808-9999-0001",
                1
        );
        when(cancelInquiryService.inquire(request)).thenReturn(serviceResponse);

        ApiResponse<CancelResponse> response = controller.cancelInquiry(request);

        assertThat(response.getResult_code()).isEqualTo("RETRY_LATER");
        assertThat(response.getData()).isSameAs(serviceResponse);
        assertThat(response.getData().cancelStatus()).isEqualTo(CancelResultStatus.RETRY_LATER);
        verify(cancelInquiryService).inquire(request);
    }
}
