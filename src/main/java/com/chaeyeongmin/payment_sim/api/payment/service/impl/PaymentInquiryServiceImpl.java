package com.chaeyeongmin.payment_sim.api.payment.service.impl;

import com.chaeyeongmin.payment_sim.api.payment.dto.InquiryRequest;
import com.chaeyeongmin.payment_sim.api.payment.dto.InquiryResponse;
import com.chaeyeongmin.payment_sim.api.payment.service.PaymentInquiryService;
import com.chaeyeongmin.payment_sim.common.api.ApiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentInquiryServiceImpl implements PaymentInquiryService {
    @Override
    public ApiResponse<InquiryResponse> inquiry(InquiryRequest request) {
        // TODO: Q 프로세스 구현
        InquiryResponse resp =
            new InquiryResponse(null,0, null, null, null, null);

        return ApiResponse.ok(resp);
    }

}