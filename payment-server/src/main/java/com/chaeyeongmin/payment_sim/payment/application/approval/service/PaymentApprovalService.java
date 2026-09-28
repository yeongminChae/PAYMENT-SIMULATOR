package com.chaeyeongmin.payment_sim.payment.application.approval.service;

import com.chaeyeongmin.payment_sim.payment.api.approval.ApproveRequest;
import com.chaeyeongmin.payment_sim.payment.api.approval.ApproveResponse;

public interface PaymentApprovalService {
    ApproveResponse approve(ApproveRequest request);
}