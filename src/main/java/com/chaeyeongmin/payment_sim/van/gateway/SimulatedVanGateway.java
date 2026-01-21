package com.chaeyeongmin.payment_sim.van.gateway;

import com.chaeyeongmin.payment_sim.van.dto.*;
import org.springframework.stereotype.Component;

@Component
public class SimulatedVanGateway implements VanGateway {

    @Override
    public VanApproveResponse approve(VanApproveRequest request) {
        // TODO: 시뮬레이터 규칙 구현
        return new VanApproveResponse();
    }

    @Override
    public VanInquiryResponse inquiry(VanInquiryRequest request) {
        // TODO: 시뮬레이터 규칙 구현
        return new VanInquiryResponse();
    }

    @Override
    public VanCancelResponse cancel(VanCancelRequest request) {
        // TODO: 시뮬레이터 규칙 구현
        return new VanCancelResponse();
    }
}