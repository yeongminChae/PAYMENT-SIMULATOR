package com.chaeyeongmin.payment_sim.api.payment.event;

import com.chaeyeongmin.payment_sim.api.payment.service.PaymentEventLogService;
import com.chaeyeongmin.payment_sim.infra.repository.dto.PaymentEventLogInsertParam;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class PaymentEventLogRecorder {

    private final PaymentEventLogService paymentEventLogService;
    private final ApplicationEventPublisher eventPublisher;

    public void record(PaymentEventLogInsertParam event) {
        paymentEventLogService.record(event);
    }

    /**
     * 충돌 이벤트는 비즈니스 예외로 원 트랜잭션이 rollback된 뒤 남긴다.
     *
     * <p>
     * SQLite에서는 트랜잭션 안에서 REQUIRES_NEW로 다시 쓰기를 열면 파일 락(SQLITE_BUSY)에 걸릴 수 있어
     * 즉시 저장하지 않고 rollback 이벤트로 넘긴다.
     */
    public void recordAfterRollback(PaymentEventLogInsertParam event) {
        eventPublisher.publishEvent(new PaymentEventLogRequestedEvent(event));
    }
}
