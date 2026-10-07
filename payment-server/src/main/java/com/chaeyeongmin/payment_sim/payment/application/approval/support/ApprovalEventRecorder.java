package com.chaeyeongmin.payment_sim.payment.application.approval.support;

import com.chaeyeongmin.payment_sim.common.api.ResultCode;
import com.chaeyeongmin.payment_sim.infra.repository.dto.PaymentAttemptUpdatedRow;
import com.chaeyeongmin.payment_sim.infra.repository.dto.PaymentEventLogInsertParam;
import com.chaeyeongmin.payment_sim.payment.application.common.PaymentResultCodeMapper;
import com.chaeyeongmin.payment_sim.payment.application.common.VanDeclineCodeMapper;
import com.chaeyeongmin.payment_sim.payment.application.approval.transaction.model.ApprovalPrepareResult;
import com.chaeyeongmin.payment_sim.payment.application.event.PaymentEventLogRecorder;
import com.chaeyeongmin.payment_sim.payment.domain.approval.PaymentAttempt;
import com.chaeyeongmin.payment_sim.payment.domain.approval.PaymentFinalStatus;
import com.chaeyeongmin.payment_sim.payment.domain.event.PaymentEventType;
import com.chaeyeongmin.payment_sim.van.client.dto.VanApproveResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 승인 유스케이스에서 발생하는 주요 업무 이벤트를 PAYMENT_EVENT_LOG에 기록한다.
 *
 * <p>
 * 승인 attempt 생성/재사용/충돌, VAN 요청·응답, 최종 확정과 timeout/update-miss 상태를
 * 구조화 이벤트로 남긴다. PAN/CVC/raw payload는 다루지 않으며,
 * 실제 저장 방식은 {@link PaymentEventLogRecorder}에 위임한다.
 */
@Component
@RequiredArgsConstructor
public class ApprovalEventRecorder {

    private final PaymentEventLogRecorder paymentEventLogRecorder;

    /** TX1 준비가 끝난 승인 건이 VAN 호출 단계로 넘어갔음을 기록한다. */
    public void recordVanApproveRequested(ApprovalPrepareResult prepared) {
        insertApproveEvent(
                PaymentEventType.APPROVE_VAN_REQUESTED,
                prepared.posTrx(),
                prepared.attemptSeq(),
                null,
                PaymentFinalStatus.PROCESSING.name(),
                null,
                null,
                null,
                "VAN approve requested"
        );
    }

    /** VAN 승인 응답을 정상 수신했음을 응답 상태와 외부 식별자 기준으로 기록한다. */
    public void recordVanApproveResultReceived(
            ApprovalPrepareResult prepared,
            VanApproveResponse vanResponse
    ) {
        insertApproveEvent(
                PaymentEventType.APPROVE_VAN_RESULT_RECEIVED,
                prepared.posTrx(),
                prepared.attemptSeq(),
                PaymentResultCodeMapper.codeName(vanResponse.finalStatus()),
                vanResponse.finalStatus().name(),
                vanResponse.vanTrxId(),
                vanResponse.approvalNo(),
                VanDeclineCodeMapper.toCode(vanResponse.declineCode()),
                "VAN approve result received"
        );
    }

    /** 같은 posTrx·payload 재요청에 대해 기존 DB 승인 결과를 재사용했음을 기록한다. */
    public void recordApprovalReused(
            String trx,
            PaymentAttempt latest,
            PaymentFinalStatus status
    ) {
        insertApproveEvent(
                PaymentEventType.APPROVE_REUSED,
                trx,
                latest.attemptSeq(),
                PaymentResultCodeMapper.codeName(status),
                status.name(),
                latest.vanTrxId(),
                latest.approvalNo(),
                latest.declineCode(),
                "approval result reused by same posTrx and same payload"
        );
    }

    /** 같은 posTrx가 다른 payload로 재사용되어 승인 요청을 차단했음을 기록한다. */
    public void recordApprovalConflict(
            String trx,
            PaymentAttempt latest,
            PaymentFinalStatus status
    ) {
        insertApproveEvent(
                PaymentEventType.APPROVE_CONFLICT,
                trx,
                latest.attemptSeq(),
                ResultCode.CONFLICT.name(),
                status.name(),
                latest.vanTrxId(),
                latest.approvalNo(),
                latest.declineCode(),
                "POS_TRX_ALREADY_USED"
        );
    }

    /** 신규 PROCESSING approval attempt가 생성됐음을 기록한다. */
    public void recordApprovalAttemptCreated(String trx, int attemptSeq) {
        insertApproveEvent(
                PaymentEventType.APPROVE_ATTEMPT_CREATED,
                trx,
                attemptSeq,
                null,
                PaymentFinalStatus.PROCESSING.name(),
                null,
                null,
                null,
                "approval attempt created"
        );
    }

    /** VAN 결과가 Payment DB의 최종 승인 상태로 확정됐음을 기록한다. */
    public void recordApprovalFinalized(
            String trx,
            int attemptSeq,
            PaymentAttemptUpdatedRow row
    ) {
        insertApproveEvent(
                PaymentEventType.APPROVE_FINALIZED,
                trx,
                attemptSeq,
                PaymentResultCodeMapper.codeName(row.finalStatus()),
                row.finalStatus().name(),
                row.vanTrxId(),
                row.approvalNo(),
                row.declineCode(),
                "approval finalized"
        );
    }

    /** VAN 응답 수신 후 finalize update miss에서 확정 상태를 확인하지 못했음을 기록한다. */
    public void recordApprovalUnknownAfterFinalizeUpdateMiss(
            String trx,
            int attemptSeq,
            VanApproveResponse vanResponse
    ) {
        insertApproveEvent(
                PaymentEventType.APPROVE_UNKNOWN_TIMEOUT,
                trx,
                attemptSeq,
                ResultCode.UNKNOWN_TIMEOUT.name(),
                PaymentFinalStatus.UNKNOWN_TIMEOUT.name(),
                vanResponse.vanTrxId(),
                null,
                "UNKNOWN_AFTER_UPDATE_MISS",
                "approval unknown after finalize update miss"
        );
    }

    /** VAN read timeout을 UNKNOWN_TIMEOUT으로 DB에 확정했음을 기록한다. */
    public void recordApprovalTimeoutFinalized(
            String trx,
            int attemptSeq,
            PaymentAttemptUpdatedRow row
    ) {
        insertApproveEvent(
                PaymentEventType.APPROVE_UNKNOWN_TIMEOUT,
                trx,
                attemptSeq,
                ResultCode.UNKNOWN_TIMEOUT.name(),
                PaymentFinalStatus.UNKNOWN_TIMEOUT.name(),
                null,
                null,
                row.declineCode(),
                "VAN response timeout"
        );
    }

    /** timeout 확정 update miss 후에도 저장 상태를 확정하지 못했음을 기록한다. */
    public void recordApprovalUnknownAfterTimeoutUpdateMiss(String trx, int attemptSeq) {
        insertApproveEvent(
                PaymentEventType.APPROVE_UNKNOWN_TIMEOUT,
                trx,
                attemptSeq,
                ResultCode.UNKNOWN_TIMEOUT.name(),
                PaymentFinalStatus.UNKNOWN_TIMEOUT.name(),
                null,
                null,
                "UNKNOWN_AFTER_UPDATE_MISS",
                "approval unknown after timeout update miss"
        );
    }

    /**
     * 승인 이벤트 로그를 구조화 컬럼만으로 저장한다.
     *
     * <p>
     * PAN/CVC/전문 원문은 파라미터에 포함하지 않는다.
     * 승인 이벤트는 approval factory를 사용해 attemptSeq 계열 컬럼만 채우고,
     * 취소 이벤트(originalPosTrx/originalAttemptSeq)와 컬럼 사용 규칙을 섞지 않는다.
     */
    private void insertApproveEvent(
            PaymentEventType eventType,
            String posTrx,
            int attemptSeq,
            String resultCode,
            String statusSnapshot,
            String vanTrxId,
            String approvalNo,
            String declineCode,
            String note
    ) {
        PaymentEventLogInsertParam event = PaymentEventLogInsertParam.approval(
                eventType,
                posTrx,
                attemptSeq,
                resultCode,
                statusSnapshot,
                vanTrxId,
                approvalNo,
                declineCode,
                note
        );

        if (eventType == PaymentEventType.APPROVE_CONFLICT) {
            // 충돌 이벤트는 이 메서드가 BusinessException으로 rollback된 뒤 listener가 기록한다.
            paymentEventLogRecorder.recordAfterRollback(event);
            return;
        }

        paymentEventLogRecorder.record(event);
    }

}
