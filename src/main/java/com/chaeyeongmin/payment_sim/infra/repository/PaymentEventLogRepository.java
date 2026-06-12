package com.chaeyeongmin.payment_sim.infra.repository;

import com.chaeyeongmin.payment_sim.infra.repository.dto.PaymentEventLogInsertParam;

public interface PaymentEventLogRepository {

    /**
     * 결제 업무 이벤트를 PAYMENT_EVENT_LOG에 저장한다.
     *
     * <p>
     * 이벤트 로그 insert도 현재 결제 트랜잭션에 포함되므로 실패를 숨기지 않는다.
     */
    void insert(PaymentEventLogInsertParam event);

}
