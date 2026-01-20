package com.chaeyeongmin.payment_sim.api.payment.service.impl;

import com.chaeyeongmin.payment_sim.api.payment.dto.InquiryRequest;
import com.chaeyeongmin.payment_sim.api.payment.dto.InquiryResponse;
import com.chaeyeongmin.payment_sim.api.payment.service.PaymentInquiryService;
import com.chaeyeongmin.payment_sim.common.api.ApiResponse;
import org.springframework.stereotype.Service;

@Service
public class PaymentInquiryServiceImpl implements PaymentInquiryService {
    @Override
    public ApiResponse<InquiryResponse> inquiry(InquiryRequest request) {
        // TODO: Q 프로세스 구현
        return ApiResponse.ok(new InquiryResponse());
    }
}