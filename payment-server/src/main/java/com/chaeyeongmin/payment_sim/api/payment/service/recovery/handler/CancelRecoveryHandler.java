package com.chaeyeongmin.payment_sim.api.payment.service.recovery.handler;

import com.chaeyeongmin.payment_sim.api.payment.service.RecoveryFinalizationService;
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
 * 취소 Recovery Task 한 건을 현재 PAYMENT_CANCEL 및 VAN 원장 사실과 대조해 복구한다.
 *
 * <p>task가 생성된 뒤 수동 Cancel Inquiry나 다른 흐름이 먼저 취소를 확정했을 수 있으므로
 * PAYMENT_CANCEL을 반드시 다시 읽는다. DB가 이미 CANCELLED/CANCEL_DECLINED라면 그 상태를
 * 정본으로 보고 VAN을 다시 호출하지 않는다. 아직 PENDING/UNKNOWN_TIMEOUT일 때만 VAN Inquiry를
 * 수행하며, 확인한 terminal 사실의 조건부 반영은 RecoveryFinalizationService에 맡긴다.
 *
 * <p>취소 task는 현재 취소 거래번호뿐 아니라 원승인 identity도 일치해야 한다. 잘못 연결된 task가
 * 다른 원승인의 취소 row를 확정하지 않도록 VAN 호출 전에 originalPosTrx/originalAttemptSeq를 검증한다.
 *
 * <p>이 클래스는 외부 I/O를 포함하므로 class 또는 handle 메서드에 transaction을 열지 않는다.
 * DB 확정 transaction은 RecoveryFinalizationService의 짧은 transaction으로 제한한다.
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

    @Override
    public RecoveryHandlerResult handle(RecoveryTask task) {
        // Worker가 잘못된 구현체를 선택한 경우 CANCEL 데이터에 접근하기 전에 즉시 차단한다.
        if (task.targetType() != RecoveryTargetType.CANCEL) {
            throw new IllegalArgumentException("CancelRecoveryHandler requires CANCEL task");
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

        /*
         * Cancel recovery가 받아들일 수 있는 terminal 사실은 CANCELLED/CANCEL_DECLINED뿐이다.
         * 기존 R5 DTO factory와 decline-code 변환을 재사용해 Phase 6 finalizer 입력을 구성한다.
         * APPROVED/DECLINED 같은 승인 상태는 정상 미확정으로 삼키지 않고 mismatch로 드러낸다.
         */
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
            default -> throw new IllegalStateException(
                    "Unexpected VAN inquiry status for CANCEL: " + response.status()
            );
        };

        /*
         * 실제 conditional update와 경합 후 DB 재확인은 finalizer의 짧은 transaction에서 수행한다.
         * Handler는 외부 I/O 전후를 하나의 transaction으로 묶지 않는다.
         */
        RecoveryFinalizeResult finalizeResult = recoveryFinalizationService.finalizeCancel(intended);

        /*
         * finalizer의 APPLIED와 ALREADY_CONSISTENT는 처리 주체만 다를 뿐 Worker 관점에서는 모두
         * 추가 자동 복구가 필요 없는 RESOLVED다. 나머지 결과는 의미를 유지해 Worker에 전달한다.
         */
        return getRecoveryHandlerResult(finalizeResult);
    }

    /**
     * task와 PAYMENT_CANCEL row가 같은 원승인을 가리키는지 확인하는 application-level fencing이다.
     * Phase 6 finalizer의 재검증은 경합 이후 DB 상태를 방어하고, 이 검증은 외부 I/O 자체를 사전에 막는다.
     */
    private void validateIdentity(RecoveryTask task, PaymentCancel cancel) {
        if (Objects.equals(cancel.originalPosTrx(), task.originalPosTrx()) == false
                || cancel.originalAttemptSeq() != task.originalAttemptSeq()) {
            throw new IllegalStateException("RECOVERY_CANCEL_TARGET_IDENTITY_MISMATCH");
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
            throw new IllegalStateException("Invalid VAN inquiry response for CANCEL recovery");
        }
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
