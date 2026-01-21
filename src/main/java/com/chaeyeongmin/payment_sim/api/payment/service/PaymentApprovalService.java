package com.chaeyeongmin.payment_sim.api.payment.service;

import com.chaeyeongmin.payment_sim.api.payment.dto.ApproveRequest;
import com.chaeyeongmin.payment_sim.api.payment.dto.ApproveResponse;
import com.chaeyeongmin.payment_sim.common.api.ApiResponse;

public interface PaymentApprovalService {
    ApiResponse<ApproveResponse> approve(ApproveRequest request);
}