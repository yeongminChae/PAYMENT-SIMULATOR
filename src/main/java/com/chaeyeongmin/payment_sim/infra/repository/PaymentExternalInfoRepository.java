package com.chaeyeongmin.payment_sim.infra.repository;

import com.chaeyeongmin.payment_sim.infra.repository.dto.PaymentExternalInfoInsertParam;

/**
 * 승인 attempt와 1:1로 연결되는 외부 식별 정보 저장소.
 *
 * <p>
 * Service는 이 포트를 통해 카드/VAN 식별 상세를 저장하고,
 * 실제 SQL 구현은 MyBatis 구현체에 둔다.
 */
public interface PaymentExternalInfoRepository {
    void insert(PaymentExternalInfoInsertParam externalInfo);
}
