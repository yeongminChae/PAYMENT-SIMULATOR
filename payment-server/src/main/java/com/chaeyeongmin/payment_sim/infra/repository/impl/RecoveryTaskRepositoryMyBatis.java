package com.chaeyeongmin.payment_sim.infra.repository.impl;

import com.chaeyeongmin.payment_sim.domain.model.RecoveryCandidate;
import com.chaeyeongmin.payment_sim.domain.model.RecoveryTask;
import com.chaeyeongmin.payment_sim.infra.mybatis.mapper.RecoveryTaskMapper;
import com.chaeyeongmin.payment_sim.infra.repository.RecoveryTaskRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * RecoveryTaskRepository의 MyBatis 구현체.
 *
 * <p>서비스 계층이 RecoveryTaskMapper를 직접 사용하지 않도록 mapper 호출을 감싼다.
 */
@Repository
@RequiredArgsConstructor
public class RecoveryTaskRepositoryMyBatis implements RecoveryTaskRepository {

    private final RecoveryTaskMapper mapper;

    @Override
    public int insertIfAbsent(RecoveryCandidate candidate) {
        return mapper.insertIfAbsent(candidate);
    }

    @Override
    public Optional<RecoveryTask> claimNext(String claimToken, LocalDateTime now, LocalDateTime leaseExpiresAt) {
        return Optional.ofNullable(mapper.claimNext(claimToken, now, leaseExpiresAt));
    }

    @Override
    public int markResolved(Long taskId, String claimToken, LocalDateTime now) {
        return mapper.markResolved(taskId, claimToken, now);
    }

}
