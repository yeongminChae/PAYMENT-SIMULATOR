package com.chaeyeongmin.payment_sim.api.payment.service.impl;

import com.chaeyeongmin.payment_sim.api.payment.dto.request.CancelInquiryRequest;
import com.chaeyeongmin.payment_sim.api.payment.dto.response.CancelResponse;
import com.chaeyeongmin.payment_sim.api.payment.service.PaymentCancelInquiryService;
import com.chaeyeongmin.payment_sim.api.payment.service.transaction.PaymentCancelTransactionService;
import com.chaeyeongmin.payment_sim.common.api.ResultCode;
import com.chaeyeongmin.payment_sim.common.exception.BusinessException;
import com.chaeyeongmin.payment_sim.domain.model.PaymentCancel;
import com.chaeyeongmin.payment_sim.infra.repository.PaymentCancelRepository;
import com.chaeyeongmin.payment_sim.van.client.assembler.VanInquiryAssembler;
import com.chaeyeongmin.payment_sim.van.client.dto.VanInquiryRequest;
import com.chaeyeongmin.payment_sim.van.client.dto.VanInquiryResponse;
import com.chaeyeongmin.payment_sim.van.client.dto.VanInquiryResultCode;
import com.chaeyeongmin.payment_sim.van.gateway.VanGateway;
import com.chaeyeongmin.payment_sim.van.gateway.exception.VanGatewayTimeoutException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class PaymentCancelInquiryServiceImpl implements PaymentCancelInquiryService {

    private final PaymentCancelTransactionService transactionService;
    private final PaymentCancelRepository cancelRepository;
    private final VanInquiryAssembler assembler;
    private final VanGateway gateway;

    @Override
    public CancelResponse inquiry(CancelInquiryRequest request) {

        // cancel inquiry는 cancel posTrx(CURRENT_TRX_NO) 기준이다.
        // row 자체가 없으면 VAN에도 물어볼 대상이 없으므로 여기서 NOT_FOUND로 끝낸다.
        PaymentCancel cancel = cancelRepository.findByPosTrx(request.posTrx())
                .orElseThrow(() -> new BusinessException(
                        ResultCode.NOT_FOUND,
                        "조회 대상 취소 거래가 존재하지 않습니다."
                ));

        // 이미 DB에 확정된 상태는 DB를 정본으로 응답한다.
        // UNKNOWN_TIMEOUT만 VAN Inquiry(CANCEL) 대상이며, 새 VAN cancel 호출은 하지 않는다.
        return switch (cancel.cancelStatus()) {
            case PENDING -> retryLater(cancel);
            case CANCELLED -> cancelled(cancel);
            case CANCEL_DECLINED -> declined(cancel);
            case UNKNOWN_TIMEOUT -> resolveUnknownTimeout(cancel);
        };

    }

    private CancelResponse retryLater(PaymentCancel cancel) {
        return CancelResponse.retryLater(
                cancel.posTrx(),
                cancel.originalPosTrx(),
                cancel.originalAttemptSeq()
        );
    }

    private CancelResponse cancelled(PaymentCancel cancel) {
        return CancelResponse.cancelled(
                cancel.posTrx(),
                cancel.originalPosTrx(),
                cancel.originalAttemptSeq(),
                cancel.cancelApprovalNo()
        );
    }

    private CancelResponse declined(PaymentCancel cancel) {
        return CancelResponse.declined(
                cancel.posTrx(),
                cancel.originalPosTrx(),
                cancel.originalAttemptSeq(),
                cancel.declineCode()
        );
    }

    private CancelResponse resolveUnknownTimeout(PaymentCancel cancel) {
        // R5 공용 Inquiry protocol에서 CANCEL 조회는 targetAttemptSeq를 보내지 않는다.
        // assembler가 approval 전용 값(vanTrxId/cardLast4)도 비워서 TCP boundary로 넘긴다.
        VanInquiryRequest request = assembler.getCancelInquiryRequest(cancel.posTrx());

        final VanInquiryResponse response;
        try {
            response = gateway.inquiry(request);

        } catch (VanGatewayTimeoutException e) {
            // 조회 자체가 timeout.
            // 기존 UNKNOWN_TIMEOUT 사실은 바뀌지 않는다.
            return retryLater(cancel);
        }

        // VAN 원장 부재만으로 취소 실패를 단정하지 않는다.
        // Payment의 UNKNOWN_TIMEOUT 상태도 그대로 유지한다.
        if (response.resultCode() == VanInquiryResultCode.NOT_FOUND) return retryLater(cancel);

        // DB 상태 전이는 transaction service에 맡겨 네트워크 I/O와 DB transaction 경계를 분리한다.
        return transactionService.finalizeCancelInquiry(cancel, response);
    }

}
