package com.chaeyeongmin.payment_sim.payment.application.approval.support;

import com.chaeyeongmin.payment_sim.infra.repository.dto.PaymentEventLogInsertParam;
import com.chaeyeongmin.payment_sim.payment.application.event.PaymentEventLogRecorder;
import com.chaeyeongmin.payment_sim.payment.domain.approval.PaymentAttempt;
import com.chaeyeongmin.payment_sim.payment.domain.approval.PaymentFinalStatus;
import com.chaeyeongmin.payment_sim.payment.domain.event.PaymentEventType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class ApprovalEventRecorderTest {

    private PaymentEventLogRecorder paymentEventLogRecorder;
    private ApprovalEventRecorder approvalEventRecorder;

    @BeforeEach
    void setUp() {
        paymentEventLogRecorder = mock(PaymentEventLogRecorder.class);
        approvalEventRecorder = new ApprovalEventRecorder(paymentEventLogRecorder);
    }

    @Test
    void 일반_approval_event는_record로_기록한다() {
        approvalEventRecorder.recordApprovalAttemptCreated("2376-20260930-9991-0001", 1);

        ArgumentCaptor<PaymentEventLogInsertParam> captor =
                ArgumentCaptor.forClass(PaymentEventLogInsertParam.class);
        verify(paymentEventLogRecorder).record(captor.capture());
        verify(paymentEventLogRecorder, never()).recordAfterRollback(any());

        PaymentEventLogInsertParam event = captor.getValue();
        assertThat(event.eventType()).isEqualTo(PaymentEventType.APPROVE_ATTEMPT_CREATED);
        assertThat(event.posTrx()).isEqualTo("2376-20260930-9991-0001");
        assertThat(event.attemptSeq()).isEqualTo(1);
        assertThat(event.statusSnapshot()).isEqualTo(PaymentFinalStatus.PROCESSING.name());
        assertThat(event.note()).isEqualTo("approval attempt created");
    }

    @Test
    void approve_conflict는_recordAfterRollback으로_기록한다() {
        String posTrx = "2376-20260930-9991-0002";
        PaymentAttempt latest = latestAttempt();

        approvalEventRecorder.recordApprovalConflict(
                posTrx,
                latest,
                PaymentFinalStatus.APPROVED
        );

        ArgumentCaptor<PaymentEventLogInsertParam> captor =
                ArgumentCaptor.forClass(PaymentEventLogInsertParam.class);
        verify(paymentEventLogRecorder).recordAfterRollback(captor.capture());
        verify(paymentEventLogRecorder, never()).record(any());

        PaymentEventLogInsertParam event = captor.getValue();
        assertThat(event.eventType()).isEqualTo(PaymentEventType.APPROVE_CONFLICT);
        assertThat(event.posTrx()).isEqualTo(posTrx);
        assertThat(event.attemptSeq()).isEqualTo(latest.attemptSeq());
        assertThat(event.resultCode()).isEqualTo("CONFLICT");
        assertThat(event.statusSnapshot()).isEqualTo(PaymentFinalStatus.APPROVED.name());
        assertThat(event.approvalNo()).isEqualTo(latest.approvalNo());
        assertThat(event.note()).isEqualTo("POS_TRX_ALREADY_USED");
    }

    private PaymentAttempt latestAttempt() {
        return new PaymentAttempt(
                PaymentFinalStatus.APPROVED.name(),
                "APPROVAL-0001",
                null,
                "41111111",
                "1111",
                "UNKNOWN",
                "fp:4111111111111111",
                1,
                10000,
                "VAN-TRX-0001"
        );
    }

}
