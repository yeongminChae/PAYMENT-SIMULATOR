package com.chaeyeongmin.payment_sim.van.gateway;

import com.chaeyeongmin.payment_sim.van.dto.*;

public interface VanGateway {
    VanApproveResponse approve(VanApproveRequest request);

    VanInquiryResponse inquiry(VanInquiryRequest request);

    VanCancelResponse cancel(VanCancelRequest request);
}