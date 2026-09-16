package com.chaeyeongmin.payment_sim.infra.repository.impl;

import com.chaeyeongmin.payment_sim.domain.model.RecoveryHistory;
import com.chaeyeongmin.payment_sim.infra.mybatis.mapper.RecoveryHistoryMapper;
import com.chaeyeongmin.payment_sim.infra.repository.RecoveryHistoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;

@Repository
@RequiredArgsConstructor
public class RecoveryHistoryRepositoryMyBatis implements RecoveryHistoryRepository {

    private final RecoveryHistoryMapper mapper;

    @Override
    public int nextTryNo(Long recoveryTaskId) {
        return mapper.nextTryNo(recoveryTaskId);
    }

    @Override
    public RecoveryHistory insertStarted(Long recoveryTaskId, int tryNo, LocalDateTime startedAt) {
        return mapper.insertStarted(recoveryTaskId, tryNo, startedAt);
    }
}
