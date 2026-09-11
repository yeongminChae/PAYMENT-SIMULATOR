package com.chaeyeongmin.payment_sim.api.payment.service.recovery.handler;

import com.chaeyeongmin.payment_sim.api.payment.service.RecoveryFinalizationService;
import com.chaeyeongmin.payment_sim.api.payment.service.support.AttemptResultUpdateParamFactory;
import com.chaeyeongmin.payment_sim.api.payment.service.transaction.model.RecoveryFinalizeResult;
import com.chaeyeongmin.payment_sim.domain.model.PaymentAttempt;
import com.chaeyeongmin.payment_sim.domain.model.RecoveryTask;
import com.chaeyeongmin.payment_sim.domain.policy.RecoveryTargetType;
import com.chaeyeongmin.payment_sim.domain.status.PaymentFinalStatus;
import com.chaeyeongmin.payment_sim.infra.repository.PaymentAttemptRepository;
import com.chaeyeongmin.payment_sim.infra.repository.dto.AttemptResultUpdateParam;
import com.chaeyeongmin.payment_sim.van.client.assembler.VanInquiryAssembler;
import com.chaeyeongmin.payment_sim.van.client.dto.VanInquiryRequest;
import com.chaeyeongmin.payment_sim.van.client.dto.VanInquiryResponse;
import com.chaeyeongmin.payment_sim.van.client.dto.VanInquiryResultCode;
import com.chaeyeongmin.payment_sim.van.client.tcp.protocol.inquiry.VanInquiryStatus;
import com.chaeyeongmin.payment_sim.van.gateway.VanGateway;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 승인 Recovery Task 한 건을 현재 PAYMENT_ATTEMPT 및 VAN 원장 사실과 대조해 복구한다.
 *
 * <p>task가 생성된 뒤 수동 Inquiry나 다른 요청이 먼저 승인을 확정했을 수 있으므로
 * PAYMENT_ATTEMPT를 반드시 다시 읽는다. DB가 이미 APPROVED/DECLINED라면 그 상태를 정본으로 보고
 * VAN을 다시 호출하지 않는다. 아직 PROCESSING/UNKNOWN_TIMEOUT일 때만 VAN Inquiry를 수행하며,
 * VAN에서 얻은 terminal 사실의 조건부 반영은 RecoveryFinalizationService에 맡긴다.
 *
 * <p>이 클래스는 외부 I/O를 포함하므로 class 또는 handle 메서드에 transaction을 열지 않는다.
 * DB 확정 transaction은 RecoveryFinalizationService의 짧은 transaction으로 제한한다.
 */
@Component
@RequiredArgsConstructor
public class ApprovalRecoveryHandler implements RecoveryHandler {

    private final PaymentAttemptRepository attemptRepository;
    private final VanGateway vanGateway;
    private final VanInquiryAssembler vanInquiryAssembler;
    private final RecoveryFinalizationService recoveryFinalizationService;

    @Override
    public RecoveryTargetType targetType() {
        return RecoveryTargetType.APPROVAL;
    }

    @Override
    public RecoveryHandlerResult handle(RecoveryTask task) {
        /*
         * Handler 선택 오류를 초기에 차단한다. 승인 attempt는 posTrx와 attemptSeq가 함께 있어야
         * 정확히 한 건을 특정할 수 있으므로 targetAttemptSeq가 없는 task도 복구 대상으로 받지 않는다.
         */
        if (task.targetType() != RecoveryTargetType.APPROVAL) {
            throw new IllegalArgumentException("ApprovalRecoveryHandler requires APPROVAL task");
        }

        if (task.targetAttemptSeq() == null) {
            throw new IllegalArgumentException("Approval recovery task requires targetAttemptSeq");
        }

        /*
         * task 생성 당시 상태는 이미 오래됐을 수 있다. VAN을 호출하기 직전에 target DB를 재조회해
         * 현재 상태를 기준으로 shortcut 또는 Inquiry 여부를 결정한다.
         */
        Optional<PaymentAttempt> attemptOpt =
                attemptRepository.findByPosTrxAndAttemptSeq(task.targetTrxNo(), task.targetAttemptSeq());

        if (attemptOpt.isEmpty()) {
            // VAN에 물어볼 local target 자체가 없으므로 외부 호출 없이 Worker에 대상 부재를 알린다.
            return new RecoveryHandlerResult(
                    RecoveryHandlerResultType.TARGET_NOT_FOUND,
                    null,
                    null
            );
        }

        PaymentAttempt attempt = attemptOpt.get();
        PaymentFinalStatus dbStatus = attempt.getFinalStatusEnum();

        /*
         * 다른 흐름이 먼저 같은 attempt를 확정했다면 이 task의 자동 복구 목적은 이미 달성됐다.
         * 불필요한 VAN Inquiry를 금지하고 Worker가 task를 RESOLVED 처리할 수 있게 반환한다.
         */
        if (dbStatus == PaymentFinalStatus.APPROVED || dbStatus == PaymentFinalStatus.DECLINED) {
            return new RecoveryHandlerResult(
                    RecoveryHandlerResultType.RESOLVED,
                    null,
                    dbStatus.name()
            );
        }

        /*
         * PROCESSING 또는 UNKNOWN_TIMEOUT만 여기까지 도달한다. 저장된 카드 식별 정보와 VAN 추적키를
         * 사용해 기존 R5 Inquiry 계약을 구성하고, DB transaction 없이 외부 VAN I/O를 수행한다.
         */
        VanInquiryRequest request =
                vanInquiryAssembler.getVanInquiryRequest(
                        task.targetTrxNo(),
                        task.targetAttemptSeq(),
                        attempt.cardLast4(),
                        attempt.vanTrxId()
                );

        VanInquiryResponse response = vanGateway.inquiry(request);

        /*
         * NOT_FOUND는 VAN 원장에 대상 row가 없다는 뜻이다. 승인 실패로 단정할 근거가 아니므로
         * DB를 변경하지 않고 STILL_UNRESOLVED로 반환해 후속 retry 정책이 판단하도록 한다.
         */
        if (response.resultCode() == VanInquiryResultCode.NOT_FOUND) {
            return new RecoveryHandlerResult(
                    RecoveryHandlerResultType.STILL_UNRESOLVED,
                    VanInquiryResultCode.NOT_FOUND.name(),
                    dbStatus.name()
            );
        }

        /*
         * SUCCESS + UNKNOWN은 VAN row는 존재하지만 VAN도 결론을 내리지 못한 상태다.
         * NOT_FOUND와 결과 타입은 같지만 운영에서 원인을 구분할 수 있도록 observedStatus를 보존한다.
         */
        if (response.status() == VanInquiryStatus.UNKNOWN) {
            return new RecoveryHandlerResult(
                    RecoveryHandlerResultType.STILL_UNRESOLVED,
                    VanInquiryStatus.UNKNOWN.name(),
                    dbStatus.name()
            );
        }

        /*
         * Approval recovery가 받아들일 수 있는 terminal 사실은 APPROVED/DECLINED뿐이다.
         * Cancel 계열 상태는 정상 미확정으로 삼키지 않고 protocol/target mismatch로 즉시 드러낸다.
         */
        if (response.status() != VanInquiryStatus.APPROVED && response.status() != VanInquiryStatus.DECLINED) {
            throw new IllegalStateException("Unexpected VAN inquiry status for APPROVAL: " + response.status());
        }

        /*
         * VAN terminal 응답을 PAYMENT_ATTEMPT에 반영할 의도 값으로 변환한다. 실제 update와 경합 후
         * DB 재확인은 Phase 6 finalizer가 짧은 transaction 안에서 수행한다.
         */
        AttemptResultUpdateParam intended =
                AttemptResultUpdateParamFactory.fromVanInquiry(
                        response,
                        task.targetTrxNo(),
                        task.targetAttemptSeq()
                );

        RecoveryFinalizeResult finalizeResult = recoveryFinalizationService.finalizeApproval(intended);

        /*
         * finalizer의 APPLIED와 ALREADY_CONSISTENT는 처리 주체만 다를 뿐 Worker 관점에서는 모두
         * 추가 자동 복구가 필요 없는 RESOLVED다. 나머지 결과는 의미를 유지해 Worker에 전달한다.
         */
        return getRecoveryHandlerResult(finalizeResult);

    }

    private RecoveryHandlerResult getRecoveryHandlerResult(RecoveryFinalizeResult finalizeResult) {
        return switch (finalizeResult.resultType()) {
            case APPLIED, ALREADY_CONSISTENT -> new RecoveryHandlerResult(
                    RecoveryHandlerResultType.RESOLVED,
                    finalizeResult.intendedStatus(),
                    finalizeResult.dbStatus()
            );

            case STILL_UNRESOLVED -> new RecoveryHandlerResult(
                    RecoveryHandlerResultType.STILL_UNRESOLVED,
                    finalizeResult.intendedStatus(),
                    finalizeResult.dbStatus()
            );

            case TERMINAL_CONFLICT -> new RecoveryHandlerResult(
                    RecoveryHandlerResultType.TERMINAL_CONFLICT,
                    finalizeResult.intendedStatus(),
                    finalizeResult.dbStatus()
            );

            case TARGET_NOT_FOUND -> new RecoveryHandlerResult(
                    RecoveryHandlerResultType.TARGET_NOT_FOUND,
                    finalizeResult.intendedStatus(),
                    finalizeResult.dbStatus()
            );
        };

    }

}
