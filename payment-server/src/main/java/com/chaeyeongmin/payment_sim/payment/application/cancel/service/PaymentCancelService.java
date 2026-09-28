package com.chaeyeongmin.payment_sim.payment.application.cancel.service;

import com.chaeyeongmin.payment_sim.payment.api.cancel.CancelRequest;
import com.chaeyeongmin.payment_sim.payment.api.cancel.CancelResponse;
import com.chaeyeongmin.payment_sim.common.api.ApiResponse;

public interface PaymentCancelService {
    CancelResponse cancel(CancelRequest request);
}