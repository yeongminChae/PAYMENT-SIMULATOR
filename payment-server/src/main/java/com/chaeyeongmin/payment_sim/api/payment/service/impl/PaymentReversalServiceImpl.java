package com.chaeyeongmin.payment_sim.api.payment.service.impl;

import com.chaeyeongmin.payment_sim.api.payment.dto.request.ReversalRequest;
import com.chaeyeongmin.payment_sim.api.payment.dto.response.ReversalResponse;
import com.chaeyeongmin.payment_sim.api.payment.service.PaymentReversalService;
import com.chaeyeongmin.payment_sim.api.payment.service.transaction.PaymentReversalTransactionService;
import com.chaeyeongmin.payment_sim.api.payment.service.transaction.model.PaymentReversalPrepareResult;
import com.chaeyeongmin.payment_sim.van.client.assembler.VanReversalAssembler;
import com.chaeyeongmin.payment_sim.van.client.dto.VanReversalRequest;
import com.chaeyeongmin.payment_sim.van.client.dto.VanReversalResponse;
import com.chaeyeongmin.payment_sim.van.gateway.VanGateway;
import com.chaeyeongmin.payment_sim.van.gateway.exception.VanGatewayRequestNotSentException;
import com.chaeyeongmin.payment_sim.van.gateway.exception.VanGatewayTimeoutException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * [Service]
 * 결제 reversal 유스케이스의 흐름을 제어한다.
 * <p>
 * 이 클래스에서 기억할 핵심:
 * - reversal은 VAN 응답을 받지 못해 UNKNOWN_TIMEOUT으로 확정된 원승인 attempt를 대상으로 한다.
 * - 신규 reversal은 PENDING row를 먼저 만든 뒤 VAN을 호출한다. 그래야 후속 요청이 중복 reversal 호출을 피할 수 있다.
 * - 같은 reversalPosTrx는 같은 원거래에 대해서만 재사용할 수 있고, 다른 payload면 conflict로 막는다.
 * - 이미 reversal row가 있으면 VAN을 다시 호출하지 않고 DB 상태를 기준으로 재응답한다.
 * <p>
 * 상태 정책:
 * - PENDING           : reversal 요청은 접수됐지만 최종 결과 미확정
 * - REVERSED          : reversal 성공 확정
 * - REVERSAL_DECLINED : VAN reversal 거절 확정
 */
@Service
@RequiredArgsConstructor
public class PaymentReversalServiceImpl implements PaymentReversalService {

    private final PaymentReversalTransactionService transactionService;
    private final VanGateway vanGateway;
    private final VanReversalAssembler vanReversalAssembler;

    /**
     * reversal 요청 1건을 DB 선점, VAN 호출, 최종 상태 저장 순서로 처리한다.
     *
     * <p>
     * VAN 호출 전에는 반드시 PENDING row를 먼저 만들어야 한다. 요청이 VAN에 전달된 뒤 응답을 받지 못하면
     * 최종 결과를 단정하지 않고 retryLater로 응답하며, 후속 요청은 DB 상태를 기준으로 처리한다.
     */
    @Override
    public ReversalResponse reversal(ReversalRequest request) {
        // R1~R4: DB 기준 reversal 준비 트랜잭션.
        // - reversalPosTrx payload 충돌 검증, 원승인 lock, 원승인 상태 확인, 기존 reversal 재응답을 담당한다.
        // - UNKNOWN_TIMEOUT 원승인만 reversal 대상이며, 신규 요청은 PENDING row를 먼저 만든다.
        // - completed=true면 이미 DB 기준으로 응답이 확정된 경로라 VAN을 호출하지 않는다.
        PaymentReversalPrepareResult prepared = transactionService.prepare(request);
        if (prepared.isCompleted()) return prepared.completedResponse();

        // R5: VAN reversal 요청 DTO 구성.
        // - prepared에는 TX1에서 확정한 reversal 거래번호와 원승인 attempt 정보가 들어 있다.
        // - assembler는 원승인 금액 등 VAN reversal 전문에 필요한 값만 꺼내 요청을 만든다.
        VanReversalRequest vanRequest = vanReversalAssembler.assemble(
                prepared.reversalPosTrx(),
                prepared.originalPosTrx(),
                prepared.originalAttemptSeq(),
                prepared.originalAttempt()
        );

        // R6: VAN reversal 호출. (트랜잭션 없음)
        // - 네트워크 I/O는 PENDING row 생성 트랜잭션과 최종 저장 트랜잭션 사이에서 수행한다.
        // - 신규 reversal 흐름에서 실제 외부 reversal 시도는 여기서 1번만 수행한다.
        final VanReversalResponse vanResponse;
        try {
            vanResponse = vanGateway.reversal(vanRequest);

        } catch (VanGatewayRequestNotSentException e) {
            // Socket.connect 단계에서 실패해 request bytes가 전송되지 않은 경우다.
            // - VAN에 reversal이 전달되지 않았으므로 방금 만든 PENDING row를 정리해 동일 요청 재시도를 허용한다.
            return transactionService.cleanupPendingAndRetryLater(prepared);

        } catch (VanGatewayTimeoutException e) {
            // 요청은 VAN에 전달됐을 수 있지만 응답을 받지 못했다.
            // - 성공/거절 여부를 추측하지 않고 PENDING 상태를 유지해 후속 요청의 중복 VAN 호출을 막는다.
            return ReversalResponse.retryLater(
                    prepared.reversalPosTrx(),
                    prepared.originalPosTrx(),
                    prepared.originalAttemptSeq()
            );
        }

        // R7: VAN 응답 확정 트랜잭션.
        // - PENDING row를 선점한 요청만 여기까지 내려온다.
        // - VAN 결과를 DB에 먼저 저장하고, 실제 저장된 값을 기준으로 최종 응답을 만든다.
        return transactionService.finalizeReversal(prepared, vanResponse);
    }
}
