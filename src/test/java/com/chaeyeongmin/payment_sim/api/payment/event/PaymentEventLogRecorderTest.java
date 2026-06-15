package com.chaeyeongmin.payment_sim.api.payment.event;

import com.chaeyeongmin.payment_sim.api.payment.service.PaymentEventLogService;
import com.chaeyeongmin.payment_sim.domain.policy.PaymentEventType;
import com.chaeyeongmin.payment_sim.infra.repository.dto.PaymentEventLogInsertParam;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class PaymentEventLogRecorderTest {

    private final PaymentEventLogService paymentEventLogService = mock(PaymentEventLogService.class);
    private final ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
    private final PaymentEventLogRecorder recorder =
            new PaymentEventLogRecorder(paymentEventLogService, eventPublisher);

    @Test
    void record_writesLogImmediately() {
        PaymentEventLogInsertParam event = approveConflictEvent();

        recorder.record(event);

        verify(paymentEventLogService).record(event);
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void recordAfterRollback_publishesRollbackEvent() {
        PaymentEventLogInsertParam event = approveConflictEvent();

        recorder.recordAfterRollback(event);

        var captor = forClass(Object.class);
        verify(eventPublisher).publishEvent(captor.capture());

        PaymentEventLogRequestedEvent published =
                assertInstanceOf(PaymentEventLogRequestedEvent.class, captor.getValue());
        assertSame(event, published.log());
        verifyNoInteractions(paymentEventLogService);
    }

    private PaymentEventLogInsertParam approveConflictEvent() {
        return new PaymentEventLogInsertParam(
                PaymentEventType.APPROVE_CONFLICT,
                "POS-TRX-1",
                1,
                null,
                null,
                null,
                "CONFLICT",
                "APPROVED",
                "VAN-TRX-1",
                "APPROVAL-1",
                null,
                null,
                "POS_TRX_ALREADY_USED"
        );
    }
}
