package com.chaeyeongmin.payment_sim.api.payment.mapper;

import com.chaeyeongmin.payment_sim.api.payment.dto.card.CardSummary;
import com.chaeyeongmin.payment_sim.api.payment.dto.response.ApproveResponse;
import com.chaeyeongmin.payment_sim.api.payment.dto.response.CancelResponse;
import com.chaeyeongmin.payment_sim.api.payment.dto.response.InquiryResponse;
import com.chaeyeongmin.payment_sim.common.api.ApiResponse;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PaymentApiResponseMapperTest {

    private final PaymentApiResponseMapper mapper = new PaymentApiResponseMapper();
    private final CardSummary cardSummary = new CardSummary("42424242", "4242", null);

    @Test
    void fromApprove_shouldMapFinalStatusToResultCode() {
        assertApproveResultCode("OK", ApproveResponse.approved("trx", 1, "A123456789", cardSummary));
        assertApproveResultCode("DECLINED", ApproveResponse.declined("trx", 1, "05", cardSummary));
        assertApproveResultCode("UNKNOWN_TIMEOUT", ApproveResponse.unknownTimeout("trx", 1, "TIMEOUT", cardSummary));
        assertApproveResultCode("RETRY_LATER", ApproveResponse.retryLater("trx", 1, cardSummary));
    }

    @Test
    void fromInquiry_shouldMapFinalStatusToResultCode() {
        assertInquiryResultCode("OK", InquiryResponse.approved("trx", 1, "A123456789", cardSummary));
        assertInquiryResultCode("DECLINED", InquiryResponse.declined("trx", 1, "05", cardSummary));
        assertInquiryResultCode("UNKNOWN_TIMEOUT", InquiryResponse.unknownTimeout("trx", 1, "TIMEOUT", cardSummary));
        assertInquiryResultCode("RETRY_LATER", InquiryResponse.retryLater("trx", 1, cardSummary));
    }

    @Test
    void fromCancel_shouldMapCancelStatusToResultCode() {
        assertCancelResultCode("OK", CancelResponse.cancelled("trx", "origin", 1, "C123456789"));
        assertCancelResultCode("ALREADY_CANCELLED", CancelResponse.alreadyCancelled("trx", "origin", 1, "C123456789"));
        assertCancelResultCode("CANCEL_DECLINED", CancelResponse.declined("trx", "origin", 1, "05"));
        assertCancelResultCode("CANCEL_NOT_ALLOWED", CancelResponse.cancelNotAllowed("trx", "origin", 1, "ORIGINAL_NOT_APPROVED"));
        assertCancelResultCode("RETRY_LATER", CancelResponse.retryLater("trx", "origin", 1));
    }

    private void assertApproveResultCode(String expected, ApproveResponse response) {
        ApiResponse<ApproveResponse> apiResponse = mapper.fromApprove(response);
        assertEquals(expected, apiResponse.getResult_code());
        assertEquals(response, apiResponse.getData());
    }

    private void assertInquiryResultCode(String expected, InquiryResponse response) {
        ApiResponse<InquiryResponse> apiResponse = mapper.fromInquiry(response);
        assertEquals(expected, apiResponse.getResult_code());
        assertEquals(response, apiResponse.getData());
    }

    private void assertCancelResultCode(String expected, CancelResponse response) {
        ApiResponse<CancelResponse> apiResponse = mapper.fromCancel(response);
        assertEquals(expected, apiResponse.getResult_code());
        assertEquals(response, apiResponse.getData());
    }
}
