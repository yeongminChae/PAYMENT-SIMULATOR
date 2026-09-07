package com.chaeyeongmin.payment_sim.api.payment.service.impl;

import com.chaeyeongmin.payment_sim.api.payment.dto.request.CancelRequest;
import com.chaeyeongmin.payment_sim.api.payment.dto.response.CancelResponse;
import com.chaeyeongmin.payment_sim.api.payment.service.PaymentCancelService;
import com.chaeyeongmin.payment_sim.api.payment.service.support.CancelEventRecorder;
import com.chaeyeongmin.payment_sim.api.payment.service.support.PaymentResultCodeMapper;
import com.chaeyeongmin.payment_sim.api.payment.service.support.VanDeclineCodeMapper;
import com.chaeyeongmin.payment_sim.api.payment.service.transaction.PaymentCancelTransactionService;
import com.chaeyeongmin.payment_sim.api.payment.service.transaction.model.PaymentCancelPrepareResult;
import com.chaeyeongmin.payment_sim.api.payment.validate.CancelRequestValidator;
import com.chaeyeongmin.payment_sim.domain.policy.CancelStatus;
import com.chaeyeongmin.payment_sim.domain.policy.PaymentEventType;
import com.chaeyeongmin.payment_sim.van.client.assembler.VanCancelAssembler;
import com.chaeyeongmin.payment_sim.van.client.dto.VanCancelRequest;
import com.chaeyeongmin.payment_sim.van.client.dto.VanCancelResponse;
import com.chaeyeongmin.payment_sim.van.gateway.VanGateway;
import com.chaeyeongmin.payment_sim.van.gateway.exception.VanGatewayTimeoutException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * [Service]
 * 결제 취소(Cancel) 유스케이스의 흐름을 제어한다.
 * <p>
 * 이 클래스에서 기억할 핵심:
 * - 취소는 "원승인 attempt"를 대상으로 하는 후속 거래다.
 * - MVP 정책은 전액취소 1회만 허용하므로, 원거래(originalPosTrx + originalAttemptSeq) 기준으로 cancel row를 1건만 만든다.
 * - 이미 cancel row가 있으면 VAN 취소를 다시 호출하지 않고 DB 상태를 기준으로 재응답한다.
 * - 신규 취소는 PENDING row를 먼저 만든 뒤 VAN을 호출한다. 그래야 VAN 호출 중 장애/타임아웃이 나도 후속 요청이 중복 취소를 막을 수 있다.
 * <p>
 * 상태 정책:
 * - PENDING         : 취소 요청은 접수됐지만 최종 결과 미확정
 * - CANCELLED       : 취소 성공 확정
 * - CANCEL_DECLINED : VAN 또는 정책상 취소 거절 확정
 * - UNKNOWN_TIMEOUT : VAN으로 요청이 전달됐을 수 있으나 응답을 받지 못해 결과 미확정
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentCancelServiceImpl implements PaymentCancelService {

    private final PaymentCancelTransactionService transactionService;
    private final VanGateway vanGateway;
    private final CancelRequestValidator validator;
    private final VanCancelAssembler vanCancelAssembler;
    private final CancelEventRecorder recorder;

    /**
     * 취소 요청 1건을 검증, DB 선점, VAN 호출, 최종 상태 저장 순서로 처리한다.
     *
     * <p>
     * VAN 호출 전에는 반드시 PENDING row를 먼저 만들어야 한다. 그래야 호출 중 타임아웃이 나도
     * 같은 원거래에 대한 후속 취소 요청이 VAN을 다시 호출하지 않고 DB 상태 기준으로 응답할 수 있다.
     */
    @Override
    public CancelResponse cancel(CancelRequest request) {
        // C1: 취소 요청은 Controller에서 수신/로깅한다.
        // - Service는 원거래 확인, 중복 취소 방지, VAN 취소 호출, DB 확정 저장을 담당한다.

        // C2: 취소 유효성 검증.
        // - posTrx는 이번 취소 거래번호(현거래번호)다.
        // - originalPosTrx + originalAttemptSeq는 취소할 원승인 attempt를 찾는 식별자다.
        // - 입력이 잘못되면 원거래 조회나 VAN 취소로 진행하면 안 된다.
        validator.validate(request);

        // C3~C5: DB 기준 취소 준비 트랜잭션.
        // - 원승인 lock, 원거래 상태/카드 검증, 기존 취소 재응답, 신규 PENDING row 생성을 담당한다.
        // - 이 단계에서 completed=true가 돌아오면 이미 DB 기준으로 응답이 확정된 경로다.
        //   예: 취소 불가, 기존 취소 재응답, PENDING insert 경합 복구.
        // - completed=false인 요청만 트랜잭션 밖에서 VAN 취소를 호출한다.
        PaymentCancelPrepareResult prepared = transactionService.prepare(request);
        if (prepared.isCompleted()) return prepared.completedResponse();

        String posTrx = prepared.posTrx();
        String originalPosTrx = prepared.originalPosTrx();
        int originalAttemptSeq = prepared.originalAttemptSeq();

        // C6: VAN 취소 요청 DTO 구성.
        // - CancelRequest에는 현취소 거래번호와 원거래 식별자가 있다.
        // - originalAttempt에는 원승인 금액/승인번호/카드요약 등 VAN 취소에 필요한 원거래 정보가 있다.
        // - assembler는 두 객체를 합쳐 VAN 계약에 맞는 취소 전문을 만든다.
        VanCancelRequest vanCancelRequest =
                vanCancelAssembler.assemble(
                        prepared.posTrx(),
                        prepared.originalPosTrx(),
                        prepared.originalAttemptSeq(),
                        prepared.originalAttempt()
                );

        recorder.recordCancelEvent(
                PaymentEventType.CANCEL_VAN_REQUESTED,
                posTrx,
                originalPosTrx,
                originalAttemptSeq,
                null,
                CancelStatus.PENDING.name(),
                null,
                null,
                null,
                "VAN cancel requested"
        );

        // C6-1: VAN 취소 호출.
        // - 신규 취소 흐름에서 실제 외부 취소 시도는 여기서 1번만 수행한다.
        final VanCancelResponse vanCancelResponse;

        try {
            vanCancelResponse = vanGateway.cancel(vanCancelRequest);

        } catch (VanGatewayTimeoutException e) {
            // VAN timeout은 성공/거절을 알 수 없는 상태다.
            // - 외부 취소가 실제 처리됐을 수 있으므로 결과를 추측하지 않는다.
            // - PENDING row를 UNKNOWN_TIMEOUT으로 바꿔 후속 요청의 중복 VAN 호출을 막고 retryLater로 응답한다.
            return transactionService.finalizeUnknownTimeout(prepared);
        }

        CancelStatus vanFinalStatus = vanCancelResponse.cancelStatus();
        String responseDeclineCode = VanDeclineCodeMapper.toCode(vanCancelResponse.declineCode());
        recorder.recordCancelEvent(
                PaymentEventType.CANCEL_VAN_RESULT_RECEIVED,
                posTrx,
                originalPosTrx,
                originalAttemptSeq,
                PaymentResultCodeMapper.codeName(vanFinalStatus),
                vanFinalStatus.name(),
                vanCancelResponse.vanTrxId(),
                vanCancelResponse.cancelApprovalNo(),
                responseDeclineCode,
                "VAN cancel result received"
        );

        // C7: VAN 응답 확정 트랜잭션.
        // - C5에서 PENDING row를 선점한 요청만 여기까지 내려온다.
        // - PENDING row가 없으면 후속 요청에서 중복 취소를 막을 근거가 약하므로,
        //   prepare()가 created 상태를 반환한 경우에만 VAN 결과를 최종 상태로 반영한다.
        return transactionService.finalizeCancel(prepared, vanCancelResponse);

    }

}
