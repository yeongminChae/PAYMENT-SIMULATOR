package com.chaeyeongmin.payment_sim.api.payment.service;

import com.chaeyeongmin.payment_sim.api.payment.dto.InquiryRequest;
import com.chaeyeongmin.payment_sim.api.payment.dto.InquiryResponse;
import com.chaeyeongmin.payment_sim.common.api.ApiResponse;

public interface PaymentInquiryService {
    ApiResponse<InquiryResponse> inquiry(InquiryRequest request);
}