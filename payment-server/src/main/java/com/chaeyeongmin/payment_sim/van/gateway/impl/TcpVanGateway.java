package com.chaeyeongmin.payment_sim.van.gateway.impl;

import com.chaeyeongmin.payment_sim.van.gateway.VanGateway;
import com.chaeyeongmin.payment_sim.van.client.dto.*;
import com.chaeyeongmin.payment_sim.van.gateway.impl.tcp.TcpApprovalOperation;
import com.chaeyeongmin.payment_sim.van.gateway.impl.tcp.TcpCancelOperation;
import com.chaeyeongmin.payment_sim.van.gateway.impl.tcp.TcpInquiryOperation;
import com.chaeyeongmin.payment_sim.van.gateway.impl.tcp.TcpReversalOperation;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * {@link VanGateway}의 TCP 구현체다.
 *
 * <p>
 * Payment 계층에는 기존 {@link VanGateway} 계약을 유지하고,
 * 승인·조회·취소·망취소의 실제 TCP 프로토콜 처리는
 * 각 transaction-specific operation에 위임한다.
 *
 * <p>
 * 이 클래스는 업무별 TCP 구현 세부사항을 노출하지 않는 facade 역할만 수행한다.
 */
@Component
@ConditionalOnProperty(name = "payment.van.mode", havingValue = "tcp")
@RequiredArgsConstructor
public class TcpVanGateway implements VanGateway {

    private final TcpApprovalOperation approvalOperation;
    private final TcpInquiryOperation inquiryOperation;
    private final TcpCancelOperation cancelOperation;
    private final TcpReversalOperation reversalOperation;

    @Override
    public VanApproveResponse approve(VanApproveRequest request) {
        return approvalOperation.execute(request);
    }

    @Override
    public VanInquiryResponse inquiry(VanInquiryRequest request) {
        return inquiryOperation.execute(request);
    }

    @Override
    public VanCancelResponse cancel(VanCancelRequest request) {
        return cancelOperation.execute(request);
    }

    @Override
    public VanReversalResponse reversal(VanReversalRequest request) {
        return reversalOperation.execute(request);
    }

}
