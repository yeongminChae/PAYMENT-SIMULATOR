package com.chaeyeongmin.payment_sim.api.payment.service;

import com.chaeyeongmin.payment_sim.api.payment.dto.request.CancelInquiryRequest;
import com.chaeyeongmin.payment_sim.api.payment.dto.response.CancelResponse;

/**
 * 취소 UNKNOWN_TIMEOUT 복구 조회 use case.
 *
 * <p>
 * 일반 취소 요청과 달리 새 취소를 만들거나 VAN cancel을 재호출하지 않는다.
 * 저장된 PAYMENT_CANCEL 상태를 먼저 정본으로 보고, UNKNOWN_TIMEOUT일 때만
 * VAN Inquiry(CANCEL)로 외부 원장 상태를 확인한다.
 */
public interface PaymentCancelInquiryService {

    /**
     * cancel posTrx 기준으로 저장된 취소 상태를 조회하거나,
     * UNKNOWN_TIMEOUT이면 VAN Inquiry(CANCEL) 결과로 복구를 시도한다.
     */
    CancelResponse inquiry(CancelInquiryRequest request);
}
