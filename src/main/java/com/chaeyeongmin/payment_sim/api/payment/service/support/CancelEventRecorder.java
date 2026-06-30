package com.chaeyeongmin.payment_sim.api.payment.service.support;

import com.chaeyeongmin.payment_sim.api.payment.event.PaymentEventLogRecorder;
import com.chaeyeongmin.payment_sim.domain.policy.PaymentEventType;
import com.chaeyeongmin.payment_sim.infra.repository.dto.PaymentEventLogInsertParam;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class CancelEventRecorder {

    private final PaymentEventLogRecorder paymentEventLogRecorder;

    /**
     * 취소 이벤트 로그를 구조화 컬럼만으로 저장한다.
     *
     * <p>
     * POS_TRX에는 항상 이번 요청의 cancel posTrx를 저장하고,
     * 원승인 식별자는 ORIGINAL_* 컬럼에 분리해 저장한다.
     * CURRENT_TRX_NO는 과거 취소 거래번호 용도로 쓰던 컬럼이지만,
     * 신규 이벤트 정책에서는 승인/취소 모두 POS_TRX를 현재 요청 거래번호로 통일한다.
     */
    public void recordCancelEvent(
            PaymentEventType eventType,
            String posTrx,
            String originalPosTrx,
            int originalAttemptSeq,
            String resultCode,
            String statusSnapshot,
            String vanTrxId,
            String approvalNo,
            String declineCode,
            String note
    ) {
        PaymentEventLogInsertParam event = new PaymentEventLogInsertParam(
                eventType,
                posTrx,
                null,
                null,
                originalPosTrx,
                originalAttemptSeq,
                resultCode,
                statusSnapshot,
                vanTrxId,
                approvalNo,
                declineCode,
                null,
                note
        );

        if (eventType == PaymentEventType.CANCEL_CONFLICT) {
            // 충돌 이벤트는 이 메서드가 BusinessException으로 rollback된 뒤 listener가 기록한다.
            paymentEventLogRecorder.recordAfterRollback(event);
            return;
        }

        paymentEventLogRecorder.record(event);
    }

    public void recordAfterRollback(
            PaymentEventType eventType,
            String posTrx,
            String originalPosTrx,
            int originalAttemptSeq,
            String resultCode,
            String statusSnapshot,
            String vanTrxId,
            String approvalNo,
            String declineCode,
            String note
    ) {
        PaymentEventLogInsertParam event = new PaymentEventLogInsertParam(
                eventType,
                posTrx,
                null,
                null,
                originalPosTrx,
                originalAttemptSeq,
                resultCode,
                statusSnapshot,
                vanTrxId,
                approvalNo,
                declineCode,
                null,
                note
        );

        paymentEventLogRecorder.recordAfterRollback(event);

    }

}
