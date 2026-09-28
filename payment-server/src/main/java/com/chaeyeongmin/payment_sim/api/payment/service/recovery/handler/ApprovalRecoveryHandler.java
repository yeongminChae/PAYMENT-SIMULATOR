package com.chaeyeongmin.payment_sim.api.payment.service.recovery.handler;

import com.chaeyeongmin.payment_sim.api.payment.service.RecoveryFinalizationService;
import com.chaeyeongmin.payment_sim.api.payment.service.recovery.exception.RecoveryInvariantViolationException;
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
import com.chaeyeongmin.payment_sim.van.client.dto.VanInquiryTargetType;
import com.chaeyeongmin.payment_sim.van.client.tcp.protocol.inquiry.VanInquiryStatus;
import com.chaeyeongmin.payment_sim.van.gateway.VanGateway;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.Optional;

/**
 * 결과가 확정되지 않은 승인 거래를 복구한다.
 *
 * <p>먼저 PAYMENT_ATTEMPT의 현재 상태를 확인한다. 이미 승인 또는 거절로 끝났다면 그대로 복구 완료로
 * 처리하고, 아직 처리 중이거나 타임아웃 상태일 때만 VAN에 실제 결과를 조회한다.
 * VAN에서 최종 결과를 확인하면 RecoveryFinalizationService가 작업 소유권을 검사한 뒤 원장에 반영한다.
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

    /**
     * 승인 거래의 현재 상태를 확인하고, 필요하면 VAN 조회 결과로 미확정 거래를 마무리한다.
     */
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

        validateSuccessfulResponse(task, response);

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

        // 승인 복구에서는 승인 또는 거절 결과만 처리한다. 다른 종류의 상태는 잘못된 응답으로 본다.
        if (response.status() != VanInquiryStatus.APPROVED && response.status() != VanInquiryStatus.DECLINED) {
            throw new RecoveryInvariantViolationException("RECOVERY_APPROVAL_INQUIRY_STATUS_INVALID: " + response.status());
        }

        // VAN 응답을 승인 원장에 저장할 값으로 바꾼다. 실제 갱신과 소유권 검사는 Finalizer가 처리한다.
        AttemptResultUpdateParam intended =
                AttemptResultUpdateParamFactory.fromVanInquiry(
                        response,
                        task.targetTrxNo(),
                        task.targetAttemptSeq()
                );

        RecoveryFinalizeResult finalizeResult = recoveryFinalizationService.finalizeApproval(task, intended);

        // Finalizer 결과를 Worker가 이해하는 공통 복구 결과로 바꾼다.
        return getRecoveryHandlerResult(finalizeResult);

    }

    /**
     * SUCCESS 응답이 현재 승인 task와 같은 거래를 가리키고 필수 상태를 포함하는지 확인한다.
     * 잘못된 응답을 finalizer에 넘기면 다른 attempt를 확정할 수 있으므로 먼저 차단한다.
     */
    private void validateSuccessfulResponse(RecoveryTask task, VanInquiryResponse response) {
        if (response.resultCode() != VanInquiryResultCode.SUCCESS
                || response.targetType() != VanInquiryTargetType.APPROVAL
                || Objects.equals(task.targetTrxNo(), response.targetTrxNo()) == false
                || Objects.equals(task.targetAttemptSeq(), response.targetAttemptSeq()) == false
                || response.status() == null) {
            throw new RecoveryInvariantViolationException("RECOVERY_APPROVAL_INQUIRY_RESPONSE_INVALID");
        }
    }

    /** 원장 반영 결과를 Worker가 처리할 수 있는 복구 결과로 바꾼다. */
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

            case OWNERSHIP_LOST -> new RecoveryHandlerResult(
                    RecoveryHandlerResultType.OWNERSHIP_LOST,
                    finalizeResult.intendedStatus(),
                    finalizeResult.dbStatus()
            );

        };

    }

}
