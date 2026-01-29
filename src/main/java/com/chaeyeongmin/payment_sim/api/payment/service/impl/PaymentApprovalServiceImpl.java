package com.chaeyeongmin.payment_sim.api.payment.service.impl;

import com.chaeyeongmin.payment_sim.api.payment.dto.ApproveRequest;
import com.chaeyeongmin.payment_sim.api.payment.dto.ApproveResponse;
import com.chaeyeongmin.payment_sim.api.payment.service.PaymentApprovalService;
import com.chaeyeongmin.payment_sim.api.payment.validate.ApproveRequestValidator;
import com.chaeyeongmin.payment_sim.infra.repository.PaymentAttemptRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * [Service]
 * 결제 승인(Approve) 유스케이스의 “오케스트레이션(흐름 제어)”을 담당한다.
 * <p>
 * 책임(Responsibility)
 * - (IMP-A2) 승인 요청의 입력 유효성 검증을 1곳으로 묶어 수행한다.
 * - (IMP-A3) attempt_seq(승인 시도 번호)를 서버에서 발급/확보하고 DB에 기록한다.
 * - (향후) 중복/처리중 분기, VAN 호출, 타임아웃 후속조회, 최종결과 저장/재응답 등을 순서대로 연결한다.
 * <p>
 * 비고(현재 단계)
 * - MVP 단계에서는 validate() 통과 시 attempt 발급/저장까지 먼저 완성한다.
 * - INVALID는 “DB/VAN을 타지 않고” 즉시 종료되어야 하므로 validate()에서 예외로 끊는 방식을 권장한다.
 */
@Service
@RequiredArgsConstructor
public class PaymentApprovalServiceImpl implements PaymentApprovalService {

    private final PaymentAttemptRepository paymentAttemptRepository;
    private final ApproveRequestValidator validator;

    @Override
    public ApproveResponse approve(ApproveRequest request) {

        // A2: 요청 유효성 검증 (실패 시 예외)
        validator.validate(request);

        // TODO A3: attempt 확보/저장
        return new ApproveResponse();
    }

}