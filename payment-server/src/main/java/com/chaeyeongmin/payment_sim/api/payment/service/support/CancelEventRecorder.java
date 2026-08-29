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
        // 취소 이벤트는 현재 취소 거래번호(posTrx)와 원승인 거래(originalPosTrx/originalAttemptSeq)를 함께 남긴다.
        // PaymentEventLogInsertParam.cancel()을 사용해 승인 이벤트용 attemptSeq 컬럼과 섞이지 않게 고정한다.
        PaymentEventLogInsertParam event = PaymentEventLogInsertParam.cancel(
                eventType,
                posTrx,
                originalPosTrx,
                originalAttemptSeq,
                resultCode,
                statusSnapshot,
                vanTrxId,
                approvalNo,
                declineCode,
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
        // rollback 이후 기록해야 하는 취소 이벤트도 동일한 cancel factory를 사용한다.
        // 실패/충돌 이벤트가 롤백 밖에서 저장되더라도 컬럼 배치는 일반 취소 이벤트와 같아야 한다.
        PaymentEventLogInsertParam event = PaymentEventLogInsertParam.cancel(
                eventType,
                posTrx,
                originalPosTrx,
                originalAttemptSeq,
                resultCode,
                statusSnapshot,
                vanTrxId,
                approvalNo,
                declineCode,
                note
        );

        paymentEventLogRecorder.recordAfterRollback(event);

    }

}
