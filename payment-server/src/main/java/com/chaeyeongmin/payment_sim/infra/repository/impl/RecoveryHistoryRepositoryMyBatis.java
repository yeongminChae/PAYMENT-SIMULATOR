package com.chaeyeongmin.payment_sim.infra.repository.impl;

import com.chaeyeongmin.payment_sim.api.payment.service.recovery.transaction.RecoveryHistoryResult;
import com.chaeyeongmin.payment_sim.domain.model.RecoveryHistory;
import com.chaeyeongmin.payment_sim.infra.mybatis.mapper.RecoveryHistoryMapper;
import com.chaeyeongmin.payment_sim.infra.repository.RecoveryHistoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

/** 서비스 계층이 MyBatis mapper를 직접 사용하지 않도록 감싼 History 저장소 구현체다. */
@Repository
@RequiredArgsConstructor
public class RecoveryHistoryRepositoryMyBatis implements RecoveryHistoryRepository {

    private final RecoveryHistoryMapper mapper;

    @Override
    public List<RecoveryHistory> findByRecoveryTaskId(Long recoveryTaskId) {
        return mapper.findByRecoveryTaskId(recoveryTaskId);
    }

    @Override
    public int nextTryNo(Long recoveryTaskId) {
        return mapper.nextTryNo(recoveryTaskId);
    }

    @Override
    public RecoveryHistory insertStarted(Long recoveryTaskId, int tryNo, LocalDateTime startedAt) {
        return mapper.insertStarted(recoveryTaskId, tryNo, startedAt);
    }

    @Override
    public int finish(Long historyId, RecoveryHistoryResult result, String errorCode, LocalDateTime finishedAt) {
        return mapper.finish(historyId, result, errorCode, finishedAt);
    }

}
