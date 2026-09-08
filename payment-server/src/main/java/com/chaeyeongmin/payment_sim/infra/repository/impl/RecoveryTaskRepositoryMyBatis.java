package com.chaeyeongmin.payment_sim.infra.repository.impl;

import com.chaeyeongmin.payment_sim.domain.model.RecoveryCandidate;
import com.chaeyeongmin.payment_sim.infra.mybatis.mapper.RecoveryTaskMapper;
import com.chaeyeongmin.payment_sim.infra.repository.RecoveryTaskRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

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
}
