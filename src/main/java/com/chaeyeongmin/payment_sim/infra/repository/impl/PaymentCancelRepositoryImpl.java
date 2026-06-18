package com.chaeyeongmin.payment_sim.infra.repository.impl;

import com.chaeyeongmin.payment_sim.domain.model.PaymentAttempt;
import com.chaeyeongmin.payment_sim.domain.model.PaymentCancel;
import com.chaeyeongmin.payment_sim.infra.mybatis.mapper.PaymentCancelMapper;
import com.chaeyeongmin.payment_sim.infra.repository.PaymentCancelRepository;
import com.chaeyeongmin.payment_sim.infra.repository.dto.CancelInsertParam;
import com.chaeyeongmin.payment_sim.infra.repository.dto.CancelResultUpdateParam;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class PaymentCancelRepositoryImpl implements PaymentCancelRepository {

    private final PaymentCancelMapper mapper;

    @Override
    public Optional<PaymentAttempt> findOriginalAttempt(String posTrx, int attemptSeq) {
        return mapper.findOriginalAttempt(posTrx, attemptSeq);
    }

    /**
     * cancel posTrx 중복 방어용 단건 조회를 MyBatis mapper에 위임한다.
     */
    @Override
    public Optional<PaymentCancel> findByPosTrx(String posTrx) {
        return mapper.findByPosTrx(posTrx);
    }

    @Override
    public Optional<PaymentCancel> findByOriginalPosTrxAndOriginalAttemptSeq(
            String originalPosTrx,
            int originalAttemptSeq
    ) {
        return mapper.findByOriginalPosTrxAndOriginalAttemptSeq(
                originalPosTrx,
                originalAttemptSeq
        );

    }

    @Override
    public Optional<PaymentCancel> insertPendingCancel(CancelInsertParam param) {
        return mapper.insertPendingCancel(param);
    }

    @Override
    public Optional<PaymentCancel> updateCancelResult(CancelResultUpdateParam param) {
        return mapper.updateCancelResult(param);
    }

}
