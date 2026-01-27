package com.chaeyeongmin.payment_sim.api.payment.service;

import com.chaeyeongmin.payment_sim.api.payment.dto.ApproveRequest;
import com.chaeyeongmin.payment_sim.api.payment.dto.ApproveResponse;

public interface PaymentApprovalService {
    ApproveResponse approve(ApproveRequest request);
}