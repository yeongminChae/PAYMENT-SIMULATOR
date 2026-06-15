package com.chaeyeongmin.payment_sim.api.payment.event;

import com.chaeyeongmin.payment_sim.api.payment.service.PaymentEventLogService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
@RequiredArgsConstructor
public class PaymentEventLogRollbackListener {

    private final PaymentEventLogService paymentEventLogService;

    /**
     * rollback이 끝난 뒤 충돌 이벤트 로그를 기록한다.
     *
     * <p>
     * 원 결제 트랜잭션과 같은 타이밍에 쓰지 않아 SQLite 파일 락 경합을 줄이고,
     * rollback되는 충돌 요청의 흔적은 별도로 남긴다.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_ROLLBACK)
    public void handle(PaymentEventLogRequestedEvent event) {
        paymentEventLogService.record(event.log());
    }
}
