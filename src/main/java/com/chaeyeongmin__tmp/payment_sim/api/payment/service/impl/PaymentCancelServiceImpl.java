package com.chaeyeongmin.payment_sim.api.payment.service.impl;

import com.chaeyeongmin.payment_sim.api.payment.dto.CancelRequest;
import com.chaeyeongmin.payment_sim.api.payment.dto.CancelResponse;
import com.chaeyeongmin.payment_sim.api.payment.service.PaymentCancelService;
import com.chaeyeongmin.payment_sim.common.api.ApiResponse;
import org.springframework.stereotype.Service;

@Service
public class PaymentCancelServiceImpl implements PaymentCancelService {
    @Override
    public ApiResponse<CancelResponse> cancel(CancelRequest request) {
        // TODO: C 프로세스 구현
        return ApiResponse.ok(new CancelResponse());
    }
}