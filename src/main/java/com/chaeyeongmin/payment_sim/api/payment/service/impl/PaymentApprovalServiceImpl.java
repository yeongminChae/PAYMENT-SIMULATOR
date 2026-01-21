package com.chaeyeongmin.payment_sim.api.payment.service.impl;

import com.chaeyeongmin.payment_sim.api.payment.dto.ApproveRequest;
import com.chaeyeongmin.payment_sim.api.payment.dto.ApproveResponse;
import com.chaeyeongmin.payment_sim.api.payment.service.PaymentApprovalService;
import com.chaeyeongmin.payment_sim.common.api.ApiResponse;
import org.springframework.stereotype.Service;

@Service
public class PaymentApprovalServiceImpl implements PaymentApprovalService {
    @Override
    public ApiResponse<ApproveResponse> approve(ApproveRequest request) {
        // TODO: A 프로세스 구현
        return ApiResponse.ok(new ApproveResponse());
    }
}