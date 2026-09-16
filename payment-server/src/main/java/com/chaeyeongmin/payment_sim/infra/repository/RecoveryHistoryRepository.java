package com.chaeyeongmin.payment_sim.infra.repository;

import com.chaeyeongmin.payment_sim.domain.model.RecoveryHistory;

import java.time.LocalDateTime;

public interface RecoveryHistoryRepository {

    int nextTryNo(Long recoveryTaskId);

    RecoveryHistory insertStarted(Long recoveryTaskId, int tryNo, LocalDateTime startedAt);
}
