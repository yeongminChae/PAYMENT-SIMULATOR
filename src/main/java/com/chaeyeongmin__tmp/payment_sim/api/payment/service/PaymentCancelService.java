package com.chaeyeongmin.payment_sim.api.payment.service;

import com.chaeyeongmin.payment_sim.api.payment.dto.CancelRequest;
import com.chaeyeongmin.payment_sim.api.payment.dto.CancelResponse;
import com.chaeyeongmin.payment_sim.common.api.ApiResponse;

public interface PaymentCancelService {
    ApiResponse<CancelResponse> cancel(CancelRequest request);
}