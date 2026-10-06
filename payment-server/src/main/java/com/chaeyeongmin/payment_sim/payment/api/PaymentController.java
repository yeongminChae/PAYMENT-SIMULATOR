package com.chaeyeongmin.payment_sim.payment.api;

import com.chaeyeongmin.payment_sim.payment.api.common.CardInput;
import com.chaeyeongmin.payment_sim.payment.api.approval.ApproveRequest;
import com.chaeyeongmin.payment_sim.payment.api.inquiry.CancelInquiryRequest;
import com.chaeyeongmin.payment_sim.payment.api.cancel.CancelRequest;
import com.chaeyeongmin.payment_sim.payment.api.inquiry.InquiryRequest;
import com.chaeyeongmin.payment_sim.payment.api.reversal.ReversalRequest;
import com.chaeyeongmin.payment_sim.payment.api.approval.ApproveResponse;
import com.chaeyeongmin.payment_sim.payment.api.cancel.CancelResponse;
import com.chaeyeongmin.payment_sim.payment.api.inquiry.InquiryResponse;
import com.chaeyeongmin.payment_sim.payment.api.reversal.ReversalResponse;
import com.chaeyeongmin.payment_sim.payment.api.mapper.PaymentApiResponseMapper;
import com.chaeyeongmin.payment_sim.payment.application.approval.service.PaymentApprovalService;
import com.chaeyeongmin.payment_sim.payment.application.inquiry.service.PaymentCancelInquiryService;
import com.chaeyeongmin.payment_sim.payment.application.cancel.service.PaymentCancelService;
import com.chaeyeongmin.payment_sim.payment.application.inquiry.service.PaymentInquiryService;
import com.chaeyeongmin.payment_sim.payment.application.reversal.service.PaymentReversalService;
import com.chaeyeongmin.payment_sim.common.api.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
@Slf4j
@RequestMapping("/api/v1/payments")
public class PaymentController {

    private final PaymentApprovalService approvalService;
    private final PaymentInquiryService inquiryService;
    private final PaymentCancelService cancelService;
    private final PaymentCancelInquiryService cancelInquiryService;
    private final PaymentReversalService reversalService;
    private final PaymentApiResponseMapper responseMapper;

    @PostMapping("/approve")
    public ApiResponse<ApproveResponse> approve(
            @Valid @RequestBody ApproveRequest request
    ) {

        // 요청 로깅
        CardInput card = request.getCard();
        log.info("[APPROVE] req PosTrX={} CardBin={} CardLast4={} Amount={}",
                request.getPosTrx(), card.bin8(), card.last4(), request.getAmount());

        ApproveResponse res = approvalService.approve(request);

        // 응답 로깅
        log.info("[APPROVE] res posTrx={}, attemptSeq={}, approvalNo={}, declineCode={}, cardSummary={}"
                , res.posTrx(), res.attemptSeq(), res.approvalNo(), res.declineCode(), res.cardSummary());

        return responseMapper.fromApprove(res);
    }

    @PostMapping("/inquiry")
    public ApiResponse<InquiryResponse> inquiry(
            @Valid @RequestBody InquiryRequest request
    ) {
        log.info("[INQUIRY] req posTrx={}, attemptSeq={}", request.posTrx(), request.attemptSeq());

        InquiryResponse res = inquiryService.inquire(request);

        log.info("[INQUIRY] res posTrx={}, attemptSeq={}, finalStatus={}, approvalNo={}, declineCode={}",
                res.posTrx(), res.attemptSeq(), res.finalStatus(), res.approvalNo(), res.declineCode());

        return responseMapper.fromInquiry(res);
    }

    @PostMapping("/cancel")
    public ApiResponse<CancelResponse> cancel(
            @Valid @RequestBody CancelRequest request
    ) {
        log.info("[cancel][C1] request received. posTrx={}, originalPosTrx={}, originalAttemptSeq={}",
                request.posTrx(),
                request.originalPosTrx(),
                request.originalAttemptSeq()
        );

        CancelResponse response = cancelService.cancel(request);

        log.info("[cancel][C1] response. posTrx={}, originalPosTrx={}, originalAttemptSeq={}, cancelStatus={}",
                response.posTrx(),
                response.originalPosTrx(),
                response.originalAttemptSeq(),
                response.cancelStatus()
        );

        return responseMapper.fromCancel(response);
    }

    @PostMapping("/cancel/inquiry")
    public ApiResponse<CancelResponse> cancelInquiry(
            @Valid @RequestBody CancelInquiryRequest request
    ) {
        // 취소 조회는 기존 /cancel 재요청과 의미가 다르다.
        // 이미 CANCELLED인 row도 ALREADY_CANCELLED가 아니라 inquiry 결과인 CANCELLED로 내려간다.
        log.info("[cancel-inquiry] request received. posTrx={}", request.posTrx());

        CancelResponse response = cancelInquiryService.inquire(request);

        log.info("[cancel-inquiry] response. posTrx={}, originalPosTrx={}, originalAttemptSeq={}, cancelStatus={}",
                response.posTrx(),
                response.originalPosTrx(),
                response.originalAttemptSeq(),
                response.cancelStatus()
        );

        return responseMapper.fromCancel(response);
    }

    @PostMapping("/reversal")
    public ApiResponse<ReversalResponse> reversal(
            @Valid @RequestBody ReversalRequest request
    ) {
        log.info("[reversal] request received. reversalPosTrx={}, originalPosTrx={}, originalAttemptSeq={}",
                request.reversalPosTrx(),
                request.originalPosTrx(),
                request.originalAttemptSeq()
        );

        ReversalResponse response = reversalService.reverse(request);

        log.info("[reversal] response. reversalPosTrx={}, originalPosTrx={}, originalAttemptSeq={}, reversalStatus={}",
                response.reversalPosTrx(),
                response.originalPosTrx(),
                response.originalAttemptSeq(),
                response.reversalStatus()
        );

        return responseMapper.fromReversal(response);
    }
}
