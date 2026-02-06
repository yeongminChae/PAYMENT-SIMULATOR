package com.chaeyeongmin.payment_sim.infra.repository.impl;

import com.chaeyeongmin.payment_sim.infra.mybatis.mapper.PaymentAttemptMapper;
import com.chaeyeongmin.payment_sim.infra.repository.PaymentAttemptRepository;
import com.chaeyeongmin.payment_sim.infra.repository.dto.AttemptInsertParam;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class PaymentAttemptRepositoryMyBatis implements PaymentAttemptRepository {

    private final PaymentAttemptMapper mapper;

    @Override
    public int insertAttemptSeq(String posTrx) {
        return mapper.insertAttemptSeq(posTrx);
    }

    @Override
    public void insertAttempt(AttemptInsertParam attempt) {
        mapper.insertAttempt(attempt);
    }

}
