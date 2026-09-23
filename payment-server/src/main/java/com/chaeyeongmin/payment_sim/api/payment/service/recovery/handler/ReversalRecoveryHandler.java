package com.chaeyeongmin.payment_sim.api.payment.service.recovery.handler;

import com.chaeyeongmin.payment_sim.api.payment.service.RecoveryFinalizationService;
import com.chaeyeongmin.payment_sim.api.payment.service.recovery.exception.RecoveryInvariantViolationException;
import com.chaeyeongmin.payment_sim.api.payment.service.support.VanDeclineCodeMapper;
import com.chaeyeongmin.payment_sim.api.payment.service.transaction.model.RecoveryFinalizeResult;
import com.chaeyeongmin.payment_sim.domain.model.PaymentReversal;
import com.chaeyeongmin.payment_sim.domain.model.RecoveryTask;
import com.chaeyeongmin.payment_sim.domain.policy.RecoveryTargetType;
import com.chaeyeongmin.payment_sim.domain.policy.ReversalStatus;
import com.chaeyeongmin.payment_sim.infra.repository.PaymentReversalRepository;
import com.chaeyeongmin.payment_sim.infra.repository.dto.ReversalResultUpdateParam;
import com.chaeyeongmin.payment_sim.van.client.assembler.VanInquiryAssembler;
import com.chaeyeongmin.payment_sim.van.client.dto.VanInquiryRequest;
import com.chaeyeongmin.payment_sim.van.client.dto.VanInquiryResponse;
import com.chaeyeongmin.payment_sim.van.client.dto.VanInquiryResultCode;
import com.chaeyeongmin.payment_sim.van.client.dto.VanInquiryTargetType;
import com.chaeyeongmin.payment_sim.van.gateway.VanGateway;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.Optional;

/**
 * 결과가 확정되지 않은 망취소 거래를 복구한다.
 *
 * <p>먼저 PAYMENT_REVERSAL의 현재 상태를 확인한다. 이미 망취소 또는 망취소 거절로 끝났다면 그대로
 * 복구 완료로 처리하고, 아직 대기 상태일 때만 VAN에 실제 결과를 조회한다.
 * 잘못된 원승인에 망취소 결과를 반영하지 않도록 망취소 거래의 원승인 정보도 함께 확인한다.
 */
@Component
@RequiredArgsConstructor
public class ReversalRecoveryHandler implements RecoveryHandler {

    private final PaymentReversalRepository reversalRepository;
    private final VanGateway vanGateway;
    private final VanInquiryAssembler vanInquiryAssembler;
    private final RecoveryFinalizationService recoveryFinalizationService;

    @Override
    public RecoveryTargetType targetType() {
        return RecoveryTargetType.REVERSAL;
    }

    /**
     * 망취소 거래의 현재 상태를 확인하고, 필요하면 VAN 조회 결과로 미확정 거래를 마무리한다.
     */
    @Override
    public RecoveryHandlerResult handle(RecoveryTask task) {

        /*
         * Handler 선택 오류를 초기에 차단한다. reversalPosTrx가 망취소 row의 단일 식별자이므로
         * REVERSAL task에는 승인 조회용 targetAttemptSeq가 포함되면 안 된다.
         */
        if (task.targetType() != RecoveryTargetType.REVERSAL) {
            throw new IllegalArgumentException("ReversalRecoveryHandler requires REVERSAL task");
        }

        if (task.targetAttemptSeq() != null) {
            throw new IllegalArgumentException("Reversal recovery task must not have targetAttemptSeq");
        }

        /*
         * task 생성 당시 상태는 이미 오래됐을 수 있다. VAN을 호출하기 직전에 reversalPosTrx로
         * target row를 재조회해 지금 시점의 DB 상태를 기준으로 shortcut 또는 Inquiry 여부를 결정한다.
         */
        Optional<PaymentReversal> reversalOpt = reversalRepository.findByReversalPosTrx(task.targetTrxNo());

        // VAN에 물어볼 local target 자체가 없으므로 외부 호출 없이 Worker에 대상 부재를 알린다.
        if (reversalOpt.isEmpty())
            return new RecoveryHandlerResult(RecoveryHandlerResultType.TARGET_NOT_FOUND, null, null );

        PaymentReversal reversal = reversalOpt.get();

        /*
         * targetTrxNo로 row를 찾았더라도 task가 기록한 원승인과 실제 reversal row의 원승인이 다르면
         * 동일 복구 대상이라고 볼 수 없다. 잘못된 VAN 조회와 DB 확정을 모두 막기 위해 먼저 검증한다.
         */
        validateIdentity(task, reversal);

        ReversalStatus dbStatus = reversal.reversalStatus();
        /*
         * 다른 흐름이 먼저 reversal row를 확정했다면 이 task의 자동 복구 목적은 이미 달성됐다.
         * 불필요한 VAN Inquiry를 금지하고 Worker가 task를 RESOLVED 처리할 수 있게 반환한다.
         */
        if (dbStatus == ReversalStatus.REVERSED || dbStatus == ReversalStatus.REVERSAL_DECLINED)
            return new RecoveryHandlerResult(RecoveryHandlerResultType.RESOLVED, null, dbStatus.name());

        /*
         * 현재 자동복구 가능한 미확정 상태는 PENDING뿐이다.
         * 향후 새로운 상태가 추가돼도 이를 암묵적으로 VAN Inquiry 대상으로 취급하지 않는다.
         */
        if (dbStatus != ReversalStatus.PENDING) {
            throw new IllegalStateException("Unexpected PAYMENT_REVERSAL status for recovery: " + dbStatus);
        }

        /*
         * PENDING 상태만 여기까지 도달한다. reversalPosTrx만 사용하는 REVERSAL Inquiry 요청을 만들고,
         * DB transaction 없이 외부 VAN I/O를 수행한다.
         */
        VanInquiryRequest request = vanInquiryAssembler.getReversalInquiryRequest(reversal.reversalPosTrx());
        VanInquiryResponse response = vanGateway.inquiry(request);

        /*
         * NOT_FOUND는 VAN 원장에 대상 row가 없다는 뜻이다. 망취소 실패로 단정할 근거가 아니므로
         * DB를 변경하지 않고 STILL_UNRESOLVED로 반환해 후속 retry 정책이 판단하도록 한다.
         */
        if (response.resultCode() == VanInquiryResultCode.NOT_FOUND)
            return new RecoveryHandlerResult(
                    RecoveryHandlerResultType.STILL_UNRESOLVED,
                    VanInquiryResultCode.NOT_FOUND.name(),
                    dbStatus.name());

        /*
         * SUCCESS 응답은 반드시 방금 요청한 REVERSAL 거래에 대한 결과여야 한다. target 종류,
         * 거래번호, attemptSeq 사용 규칙, status 존재 여부를 확인해 잘못된 응답을 finalizer로 넘기지 않는다.
         */
        validateSuccessfulResponse(reversal, response);

        // VAN의 망취소 결과를 원장에 저장할 값으로 바꾼다. 다른 종류의 상태는 잘못된 응답으로 본다.
        ReversalResultUpdateParam intended =
                switch (response.status()) {
                    case REVERSED ->
                            ReversalResultUpdateParam.reversed(
                                    reversal.reversalPosTrx(),
                                    reversal.originalPosTrx(),
                                    reversal.originalAttemptSeq(),
                                    response.vanTrxId(),
                                    response.reversalApprovalNo()
                            );

                    case REVERSAL_DECLINED ->
                            ReversalResultUpdateParam.declined(
                                    reversal.reversalPosTrx(),
                                    reversal.originalPosTrx(),
                                    reversal.originalAttemptSeq(),
                                    response.vanTrxId(),
                                    VanDeclineCodeMapper.toCode(response.declineCode())
                            );

                    default -> throw new RecoveryInvariantViolationException(
                            "Unexpected VAN inquiry status for REVERSAL: " + response.status());
                };

        // 실제 원장 갱신과 작업 소유권 검사는 짧은 transaction을 사용하는 Finalizer가 처리한다.
        RecoveryFinalizeResult finalizeResult = recoveryFinalizationService.finalizeReversal(task, intended);

        // Finalizer 결과를 Worker가 이해하는 공통 복구 결과로 바꾼다.
        return getRecoveryHandlerResult(finalizeResult);
    }

    /**
     * VAN SUCCESS 응답이 REVERSAL Inquiry 계약과 방금 조회한 target identity를 만족하는지 확인한다.
     */
    private void validateSuccessfulResponse(PaymentReversal reversal, VanInquiryResponse response) {
        if (response.resultCode() != VanInquiryResultCode.SUCCESS
                || response.targetType() != VanInquiryTargetType.REVERSAL
                || Objects.equals(reversal.reversalPosTrx(), response.targetTrxNo()) == false
                || response.targetAttemptSeq() != null
                || response.status() == null
        ) {
            throw new RecoveryInvariantViolationException("Invalid VAN inquiry response for REVERSAL recovery");
        }

    }

    /** 원장 반영 결과를 Worker가 처리할 수 있는 복구 결과로 바꾼다. */
    private RecoveryHandlerResult getRecoveryHandlerResult(RecoveryFinalizeResult finalizeResult) {
        return switch (finalizeResult.resultType()) {
            case APPLIED, ALREADY_CONSISTENT ->
                    new RecoveryHandlerResult(
                            RecoveryHandlerResultType.RESOLVED,
                            finalizeResult.intendedStatus(),
                            finalizeResult.dbStatus()
                    );

            case STILL_UNRESOLVED ->
                    new RecoveryHandlerResult(
                            RecoveryHandlerResultType.STILL_UNRESOLVED,
                            finalizeResult.intendedStatus(),
                            finalizeResult.dbStatus()
                    );

            case TERMINAL_CONFLICT ->
                    new RecoveryHandlerResult(
                            RecoveryHandlerResultType.TERMINAL_CONFLICT,
                            finalizeResult.intendedStatus(),
                            finalizeResult.dbStatus()
                    );

            case TARGET_NOT_FOUND ->
                    new RecoveryHandlerResult(
                            RecoveryHandlerResultType.TARGET_NOT_FOUND,
                            finalizeResult.intendedStatus(),
                            finalizeResult.dbStatus()
                    );

            case OWNERSHIP_LOST ->
                    new RecoveryHandlerResult(
                            RecoveryHandlerResultType.OWNERSHIP_LOST,
                            finalizeResult.intendedStatus(),
                            finalizeResult.dbStatus()
                    );
        };

    }

    /** Task와 망취소 원장이 같은 원승인을 가리키는지 확인한다. */
    private void validateIdentity(RecoveryTask task, PaymentReversal reversal) {
        if (Objects.equals(reversal.originalPosTrx(), task.originalPosTrx()) == false
                || reversal.originalAttemptSeq() != task.originalAttemptSeq()
        ) {
            throw new RecoveryInvariantViolationException("RECOVERY_REVERSAL_TARGET_IDENTITY_MISMATCH");
        }

    }

}
