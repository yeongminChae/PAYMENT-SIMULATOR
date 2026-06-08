package com.chaeyeongmin.payment_sim.api.payment.mapper;

import com.chaeyeongmin.payment_sim.api.payment.dto.enums.PaymentFinalStatus;
import com.chaeyeongmin.payment_sim.api.payment.dto.response.ApproveResponse;
import com.chaeyeongmin.payment_sim.api.payment.dto.response.CancelResponse;
import com.chaeyeongmin.payment_sim.api.payment.dto.response.InquiryResponse;
import com.chaeyeongmin.payment_sim.common.api.ApiResponse;
import com.chaeyeongmin.payment_sim.common.api.ResultCode;
import org.springframework.stereotype.Component;

@Component
public class PaymentApiResponseMapper {

    public ApiResponse<ApproveResponse> fromApprove(ApproveResponse response) {
        return ApiResponse.of(fromFinalStatus(response.finalStatus()), response);
    }

    public ApiResponse<InquiryResponse> fromInquiry(InquiryResponse response) {
        return ApiResponse.of(fromFinalStatus(response.finalStatus()), response);
    }

    public ApiResponse<CancelResponse> fromCancel(CancelResponse response) {
        return ApiResponse.of(fromCancelStatus(response.cancelStatus()), response);
    }

    private ResultCode fromFinalStatus(PaymentFinalStatus finalStatus) {
        return switch (finalStatus) {
            case APPROVED -> ResultCode.OK;
            case DECLINED -> ResultCode.DECLINED;
            case UNKNOWN_TIMEOUT -> ResultCode.UNKNOWN_TIMEOUT;
            case PROCESSING -> ResultCode.RETRY_LATER;
        };
    }

    private ResultCode fromCancelStatus(String cancelStatus) {
        return switch (cancelStatus) {
            case "CANCELLED" -> ResultCode.OK;
            case "CANCEL_DECLINED" -> ResultCode.CANCEL_DECLINED;
            case "CANCEL_NOT_ALLOWED" -> ResultCode.CANCEL_NOT_ALLOWED;
            case "PENDING" -> ResultCode.RETRY_LATER;
            // cancelStatus는 서비스가 만든 내부 응답 상태이므로 미지원 값은 사용자 입력 오류가 아니다.
            default -> throw new IllegalStateException("Unsupported cancelStatus: " + cancelStatus);
        };
    }
}
