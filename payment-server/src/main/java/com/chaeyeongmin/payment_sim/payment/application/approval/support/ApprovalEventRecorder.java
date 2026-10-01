package com.chaeyeongmin.payment_sim.payment.application.approval.support;

import com.chaeyeongmin.payment_sim.common.api.ResultCode;
import com.chaeyeongmin.payment_sim.infra.repository.dto.PaymentAttemptUpdatedRow;
import com.chaeyeongmin.payment_sim.infra.repository.dto.PaymentEventLogInsertParam;
import com.chaeyeongmin.payment_sim.payment.application.common.PaymentResultCodeMapper;
import com.chaeyeongmin.payment_sim.payment.application.common.VanDeclineCodeMapper;
import com.chaeyeongmin.payment_sim.payment.application.approval.transaction.model.PaymentApprovalPrepareResult;
import com.chaeyeongmin.payment_sim.payment.application.event.PaymentEventLogRecorder;
import com.chaeyeongmin.payment_sim.payment.domain.approval.PaymentAttempt;
import com.chaeyeongmin.payment_sim.payment.domain.approval.PaymentFinalStatus;
import com.chaeyeongmin.payment_sim.payment.domain.event.PaymentEventType;
import com.chaeyeongmin.payment_sim.van.client.dto.VanApproveResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ApprovalEventRecorder {

    private final PaymentEventLogRecorder paymentEventLogRecorder;

    public void recordVanApproveRequested(PaymentApprovalPrepareResult prepared) {
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

    public void recordVanApproveResultReceived(
            PaymentApprovalPrepareResult prepared,
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
