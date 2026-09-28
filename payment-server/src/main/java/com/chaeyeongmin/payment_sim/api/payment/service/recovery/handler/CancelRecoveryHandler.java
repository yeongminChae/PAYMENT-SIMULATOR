package com.chaeyeongmin.payment_sim.api.payment.service.recovery.handler;

import com.chaeyeongmin.payment_sim.api.payment.service.RecoveryFinalizationService;
import com.chaeyeongmin.payment_sim.api.payment.service.recovery.exception.RecoveryInvariantViolationException;
import com.chaeyeongmin.payment_sim.api.payment.service.support.VanDeclineCodeMapper;
import com.chaeyeongmin.payment_sim.api.payment.service.transaction.model.RecoveryFinalizeResult;
import com.chaeyeongmin.payment_sim.domain.model.PaymentCancel;
import com.chaeyeongmin.payment_sim.domain.model.RecoveryTask;
import com.chaeyeongmin.payment_sim.domain.policy.CancelStatus;
import com.chaeyeongmin.payment_sim.domain.policy.RecoveryTargetType;
import com.chaeyeongmin.payment_sim.infra.repository.PaymentCancelRepository;
import com.chaeyeongmin.payment_sim.infra.repository.dto.CancelResultUpdateParam;
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
 * 결과가 확정되지 않은 취소 거래를 복구한다.
 *
 * <p>먼저 PAYMENT_CANCEL의 현재 상태를 확인한다. 이미 취소 또는 취소 거절로 끝났다면 그대로 복구
 * 완료로 처리하고, 아직 대기 또는 타임아웃 상태일 때만 VAN에 실제 결과를 조회한다.
 * 잘못된 원승인에 취소 결과를 반영하지 않도록 취소 거래의 원승인 정보도 함께 확인한다.
 */
@Component
@RequiredArgsConstructor
public class CancelRecoveryHandler implements RecoveryHandler {

    private final PaymentCancelRepository cancelRepository;
    private final VanGateway vanGateway;
    private final VanInquiryAssembler vanInquiryAssembler;
    private final RecoveryFinalizationService recoveryFinalizationService;

    @Override
    public RecoveryTargetType targetType() {
        return RecoveryTargetType.CANCEL;
    }

    /**
     * 취소 거래의 현재 상태를 확인하고, 필요하면 VAN 조회 결과로 미확정 거래를 마무리한다.
     */
    @Override
    public RecoveryHandlerResult handle(RecoveryTask task) {
        // Worker가 잘못된 구현체를 선택한 경우 CANCEL 데이터에 접근하기 전에 즉시 차단한다.
        if (task.targetType() != RecoveryTargetType.CANCEL) {
            throw new IllegalArgumentException("CancelRecoveryHandler requires CANCEL task");
        }

        if (task.targetAttemptSeq() != null) {
            throw new IllegalArgumentException("Cancel recovery task must not have targetAttemptSeq");
        }

        /*
         * task 생성 당시 상태는 이미 오래됐을 수 있다. 현재 취소 거래번호로 target row를 다시 읽어
         * 지금 시점의 DB 상태를 기준으로 shortcut 또는 Inquiry 여부를 결정한다.
         */
        Optional<PaymentCancel> cancelOpt = cancelRepository.findByPosTrx(task.targetTrxNo());

        if (cancelOpt.isEmpty()) {
            // VAN에 물어볼 local target 자체가 없으므로 외부 호출 없이 Worker에 대상 부재를 알린다.
            return new RecoveryHandlerResult(
                    RecoveryHandlerResultType.TARGET_NOT_FOUND,
                    null,
                    null
            );
        }

        PaymentCancel cancel = cancelOpt.get();

        /*
         * targetTrxNo로 row를 찾았더라도 task가 기록한 원승인과 실제 cancel row의 원승인이 다르면
         * 동일 복구 대상이라고 볼 수 없다. 잘못된 VAN 조회와 DB 확정을 모두 막기 위해 먼저 검증한다.
         */
        validateIdentity(task, cancel);

        CancelStatus dbStatus = cancel.cancelStatus();

        /*
         * 다른 흐름이 먼저 cancel row를 확정했다면 이 task의 자동 복구 목적은 이미 달성됐다.
         * 불필요한 VAN Inquiry를 금지하고 Worker가 task를 RESOLVED 처리할 수 있게 반환한다.
         */
        if (dbStatus == CancelStatus.CANCELLED || dbStatus == CancelStatus.CANCEL_DECLINED) {
            return new RecoveryHandlerResult(
                    RecoveryHandlerResultType.RESOLVED,
                    null,
                    dbStatus.name()
            );
        }

        // 자동 복구 대상으로 정한 미확정 상태만 VAN에 조회한다.
        if (dbStatus != CancelStatus.PENDING && dbStatus != CancelStatus.UNKNOWN_TIMEOUT) {
            throw new IllegalStateException("Unexpected PAYMENT_CANCEL status for recovery: " + dbStatus);
        }

        /*
         * PENDING 또는 UNKNOWN_TIMEOUT만 여기까지 도달한다. R5의 CANCEL Inquiry assembler를 재사용해
         * 현재 취소 거래번호 기준 요청을 만들고, DB transaction 없이 외부 VAN I/O를 수행한다.
         * 기존 API용 Inquiry Service는 자체 update 흐름을 가지므로 호출하지 않는다.
         */
        VanInquiryRequest request = vanInquiryAssembler.getCancelInquiryRequest(cancel.posTrx());
        VanInquiryResponse response = vanGateway.inquiry(request);

        /*
         * NOT_FOUND는 VAN 원장에 대상 row가 없다는 뜻이다. 취소 실패로 단정할 근거가 아니므로
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
         * SUCCESS 응답은 반드시 방금 요청한 CANCEL 거래에 대한 결과여야 한다. target 종류, 거래번호,
         * attemptSeq 사용 규칙, status 존재 여부를 확인해 잘못된 응답이 finalizer로 내려가지 않게 한다.
         */
        validateSuccessfulResponse(cancel, response);

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

        // VAN의 취소 결과를 취소 원장에 저장할 값으로 바꾼다. 다른 종류의 상태는 잘못된 응답으로 본다.
        CancelResultUpdateParam intended = switch (response.status()) {
            case CANCELLED -> CancelResultUpdateParam.cancelled(
                    cancel.posTrx(),
                    cancel.originalPosTrx(),
                    cancel.originalAttemptSeq(),
                    response.vanTrxId(),
                    response.cancelApprovalNo()
            );

            case CANCEL_DECLINED -> CancelResultUpdateParam.declined(
                    cancel.posTrx(),
                    cancel.originalPosTrx(),
                    cancel.originalAttemptSeq(),
                    response.vanTrxId(),
                    VanDeclineCodeMapper.toCode(response.declineCode())
            );

            default -> throw new RecoveryInvariantViolationException(
                    "Unexpected VAN inquiry status for CANCEL: " + response.status()
            );
        };

        // 실제 원장 갱신과 작업 소유권 검사는 짧은 transaction을 사용하는 Finalizer가 처리한다.
        RecoveryFinalizeResult finalizeResult = recoveryFinalizationService.finalizeCancel(task, intended);

        // Finalizer 결과를 Worker가 이해하는 공통 복구 결과로 바꾼다.
        return getRecoveryHandlerResult(finalizeResult);
    }

    /** Task와 취소 원장이 같은 원승인을 가리키는지 확인한다. */
    private void validateIdentity(RecoveryTask task, PaymentCancel cancel) {
        if (Objects.equals(cancel.originalPosTrx(), task.originalPosTrx()) == false
                || cancel.originalAttemptSeq() != task.originalAttemptSeq()) {
            throw new RecoveryInvariantViolationException("RECOVERY_CANCEL_TARGET_IDENTITY_MISMATCH");
        }
    }

    /**
     * VAN SUCCESS 응답이 CANCEL Inquiry 계약과 방금 조회한 target identity를 만족하는지 확인한다.
     */
    private void validateSuccessfulResponse(PaymentCancel cancel, VanInquiryResponse response) {
        if (response.resultCode() != VanInquiryResultCode.SUCCESS
                || response.targetType() != VanInquiryTargetType.CANCEL
                || Objects.equals(cancel.posTrx(), response.targetTrxNo()) == false
                || response.targetAttemptSeq() != null
                || response.status() == null) {
            throw new RecoveryInvariantViolationException("Invalid VAN inquiry response for CANCEL recovery");
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
