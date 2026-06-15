package com.chaeyeongmin.payment_sim.api.payment.event;

import com.chaeyeongmin.payment_sim.api.payment.service.PaymentEventLogService;
import com.chaeyeongmin.payment_sim.domain.policy.PaymentEventType;
import com.chaeyeongmin.payment_sim.infra.repository.dto.PaymentEventLogInsertParam;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class PaymentEventLogRollbackListenerTest {

    private final PaymentEventLogService paymentEventLogService = mock(PaymentEventLogService.class);
    private final PaymentEventLogRollbackListener listener =
            new PaymentEventLogRollbackListener(paymentEventLogService);

    /**
     * [시나리오] UT-2-LOG-STRUCT-002
     */
    @Test
    void handle_recordsPublishedLog() {
        PaymentEventLogInsertParam event = new PaymentEventLogInsertParam(
                PaymentEventType.CANCEL_CONFLICT,
                null,
                null,
                "CANCEL-TRX-1",
                "APPROVE-TRX-1",
                1,
                "CONFLICT",
                "CANCELLED",
                null,
                "CANCEL-APPROVAL-1",
                null,
                null,
                "POS_TRX_ALREADY_USED"
        );

        listener.handle(new PaymentEventLogRequestedEvent(event));

        verify(paymentEventLogService).record(event);
    }
}
