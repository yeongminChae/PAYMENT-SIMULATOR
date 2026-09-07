package com.chaeyeongmin.payment_sim.api.payment.service;

import com.chaeyeongmin.payment_sim.api.payment.dto.request.ReversalRequest;
import com.chaeyeongmin.payment_sim.api.payment.dto.response.ReversalResponse;

public interface PaymentReversalService {

    ReversalResponse reversal(ReversalRequest request);
}
