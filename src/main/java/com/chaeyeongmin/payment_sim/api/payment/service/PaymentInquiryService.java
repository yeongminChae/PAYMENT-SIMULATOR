package com.chaeyeongmin.payment_sim.api.payment.service;

import com.chaeyeongmin.payment_sim.api.payment.dto.request.InquiryRequest;
import com.chaeyeongmin.payment_sim.api.payment.dto.response.InquiryResponse;

public interface PaymentInquiryService {
    InquiryResponse inquiry(InquiryRequest request);
}