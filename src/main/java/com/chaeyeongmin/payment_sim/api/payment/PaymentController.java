package com.chaeyeongmin.payment_sim.api.payment;

import com.chaeyeongmin.payment_sim.api.payment.dto.*;
import com.chaeyeongmin.payment_sim.api.payment.service.PaymentApprovalService;
import com.chaeyeongmin.payment_sim.api.payment.service.PaymentCancelService;
import com.chaeyeongmin.payment_sim.api.payment.service.PaymentInquiryService;
import com.chaeyeongmin.payment_sim.common.api.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Payment API 진입점(Controller).
 * <p>
 * 역할
 * - /api/v1/payments 하위의 승인/조회/취소 API 요청을 수신한다.
 * - 비즈니스 로직은 수행하지 않고, 요청 DTO를 Service에 위임한다(컨트롤러는 얇게 유지).
 * - 모든 응답은 ApiResponse 래퍼로 반환하며, 업무 결과는 result_code로 표현한다(HTTP 200 기본 전략).
 * <p>
 * 호출 흐름
 * - Client(Postman/POS) -> PaymentController -> (Approval/Inquiry/Cancel)Service -> Repository/VAN Gateway
 * <p>
 * 주의사항
 * - 입력 검증(필수값/형식)은 가능한 Controller 레벨에서 1차 수행(또는 Validator 컴포넌트로 위임).
 * - 예외는 GlobalExceptionHandler에서 result_code로 매핑되도록 BusinessException 사용을 권장한다.
 */

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/payments")
public class PaymentController {

    private final PaymentApprovalService approvalService;
    private final PaymentInquiryService inquiryService;
    private final PaymentCancelService cancelService;

    @PostMapping("/approve")
    public ApiResponse<ApproveResponse> approve(@RequestBody ApproveRequest request) {
        return approvalService.approve(request);
    }

    @PostMapping("/inquiry")
    public ApiResponse<InquiryResponse> inquiry(@RequestBody InquiryRequest request) {
        return inquiryService.inquiry(request);
    }

    @PostMapping("/cancel")
    public ApiResponse<CancelResponse> cancel(@RequestBody CancelRequest request) {
        return cancelService.cancel(request);
    }
}