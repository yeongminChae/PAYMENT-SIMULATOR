package com.chaeyeongmin.payment_sim.api.payment.mapper;

import com.chaeyeongmin.payment_sim.api.payment.dto.enums.CancelResultStatus;
import com.chaeyeongmin.payment_sim.api.payment.dto.enums.PaymentFinalStatus;
import com.chaeyeongmin.payment_sim.api.payment.dto.response.ApproveResponse;
import com.chaeyeongmin.payment_sim.api.payment.dto.response.CancelResponse;
import com.chaeyeongmin.payment_sim.api.payment.dto.response.InquiryResponse;
import com.chaeyeongmin.payment_sim.common.api.ApiResponse;
import com.chaeyeongmin.payment_sim.common.api.ResultCode;
import org.springframework.stereotype.Component;

/**
 * 결제 API 업무 응답 DTO를 공통 API 응답 포맷으로 감싸는 Mapper.
 *
 * <p>
 * Service 계층은 승인/조회/취소의 업무 결과 DTO만 만든다.
 * 이 클래스는 각 DTO 안의 업무 상태를 보고 클라이언트에 내려줄 공통 {@link ResultCode}를 결정한 뒤,
 * {@link ApiResponse} 래퍼로 변환한다.
 *
 * <p>
 * 핵심 책임:
 * - ApproveResponse.finalStatus -> ApiResponse.result_code
 * - InquiryResponse.finalStatus -> ApiResponse.result_code
 * - CancelResponse.cancelStatus -> ApiResponse.result_code
 *
 * <p>
 * 주의:
 * - HTTP 상태 코드 매핑이 아니라 응답 본문의 result_code 매핑이다.
 * - 업무 상태 enum 값이 추가되면 이 클래스의 switch 매핑도 함께 검토해야 한다.
 */
@Component
public class PaymentApiResponseMapper {

    /**
     * 승인 API 응답 DTO를 공통 API 응답으로 변환한다.
     *
     * <p>
     * 승인 결과의 최종 상태(finalStatus)를 {@link ResultCode}로 바꾸고,
     * 원본 {@link ApproveResponse}는 data 필드에 그대로 담는다.
     */
    public ApiResponse<ApproveResponse> fromApprove(ApproveResponse response) {
        return ApiResponse.of(fromFinalStatus(response.finalStatus()), response);
    }

    /**
     * 조회 API 응답 DTO를 공통 API 응답으로 변환한다.
     *
     * <p>
     * 조회 결과도 승인과 같은 결제 최종 상태(finalStatus)를 사용하므로,
     * 승인 응답과 동일한 상태 매핑 규칙을 재사용한다.
     */
    public ApiResponse<InquiryResponse> fromInquiry(InquiryResponse response) {
        return ApiResponse.of(fromFinalStatus(response.finalStatus()), response);
    }

    /**
     * 취소 API 응답 DTO를 공통 API 응답으로 변환한다.
     *
     * <p>
     * 취소는 승인/조회와 다른 상태 체계를 사용하므로,
     * {@link CancelResultStatus}를 별도 {@link ResultCode} 매핑으로 변환한다.
     */
    public ApiResponse<CancelResponse> fromCancel(CancelResponse response) {
        return ApiResponse.of(fromCancelStatus(response.cancelStatus()), response);
    }

    /**
     * 승인/조회 업무 상태를 공통 result_code로 변환한다.
     *
     * <p>
     * PaymentFinalStatus는 승인과 조회 응답에서 공유하는 API 상태다.
     * enum switch에 default를 두지 않아, enum 값이 추가됐는데 매핑이 누락되면 컴파일 단계에서 발견되도록 한다.
     */
    private ResultCode fromFinalStatus(PaymentFinalStatus finalStatus) {
        return switch (finalStatus) {
            case APPROVED -> ResultCode.OK;
            case DECLINED -> ResultCode.DECLINED;
            case UNKNOWN_TIMEOUT -> ResultCode.UNKNOWN_TIMEOUT;
            case PROCESSING -> ResultCode.RETRY_LATER;
        };
    }

    /**
     * 취소 API 응답 상태를 공통 result_code로 변환한다.
     *
     * <p>
     * CancelResultStatus는 DB 저장 상태가 아니라 클라이언트에 내려주는 취소 결과 상태다.
     * 예를 들어 DB row가 CANCELLED인 기존 취소 건을 재응답할 때는
     * API 상태를 ALREADY_CANCELLED로 표현할 수 있다.
     *
     * <p>
     * enum switch에 default를 두지 않아, enum 값이 추가됐는데 매핑이 누락되면 컴파일 단계에서 발견되도록 한다.
     */
    private ResultCode fromCancelStatus(CancelResultStatus cancelStatus) {
        return switch (cancelStatus) {
            case CANCELLED -> ResultCode.OK;
            case ALREADY_CANCELLED -> ResultCode.ALREADY_CANCELLED;
            case CANCEL_DECLINED -> ResultCode.CANCEL_DECLINED;
            case CANCEL_NOT_ALLOWED -> ResultCode.CANCEL_NOT_ALLOWED;
            case RETRY_LATER -> ResultCode.RETRY_LATER;
        };
    }

}
