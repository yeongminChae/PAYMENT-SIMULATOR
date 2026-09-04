package com.chaeyeongmin.payment_sim.infra.repository.impl;

import com.chaeyeongmin.payment_sim.domain.model.PaymentReversal;
import com.chaeyeongmin.payment_sim.infra.mybatis.mapper.PaymentReversalMapper;
import com.chaeyeongmin.payment_sim.infra.repository.PaymentReversalRepository;
import com.chaeyeongmin.payment_sim.infra.repository.dto.ReversalInsertParam;
import com.chaeyeongmin.payment_sim.infra.repository.dto.ReversalResultUpdateParam;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class PaymentReversalRepositoryImpl implements PaymentReversalRepository {

    private final PaymentReversalMapper mapper;

    @Override
    public Optional<PaymentReversal> findByReversalPosTrx(String reversalPosTrx) {
        return mapper.findByReversalPosTrx(reversalPosTrx);
    }

    @Override
    public Optional<PaymentReversal> findByOriginalPosTrxAndOriginalAttemptSeq(
            String originalPosTrx,
            int originalAttemptSeq
    ) {
        return mapper.findByOriginalPosTrxAndOriginalAttemptSeq(originalPosTrx, originalAttemptSeq);
    }

    @Override
    public Optional<PaymentReversal> insertPendingReversal(ReversalInsertParam param) {
        return mapper.insertPendingReversal(param);
    }

    @Override
    public Optional<PaymentReversal> updateReversalResult(ReversalResultUpdateParam param) {
        return mapper.updateReversalResult(param);
    }

    @Override
    public int deletePendingReversal(String reversalPosTrx, String originalPosTrx, int originalAttemptSeq) {
        return mapper.deletePendingReversal(reversalPosTrx, originalPosTrx, originalAttemptSeq);
    }
}
