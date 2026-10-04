package com.chaeyeongmin.payment_sim.payment.application.reversal.service;

import com.chaeyeongmin.payment_sim.payment.api.reversal.ReversalRequest;
import com.chaeyeongmin.payment_sim.payment.api.reversal.ReversalResponse;

public interface PaymentReversalService {

    ReversalResponse reverse(ReversalRequest request);
}
