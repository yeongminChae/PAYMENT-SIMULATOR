package com.chaeyeongmin.payment_sim.payment.application.inquiry.service;

import com.chaeyeongmin.payment_sim.payment.api.inquiry.InquiryRequest;
import com.chaeyeongmin.payment_sim.payment.api.inquiry.InquiryResponse;

public interface PaymentInquiryService {
    InquiryResponse inquiry(InquiryRequest request);
}