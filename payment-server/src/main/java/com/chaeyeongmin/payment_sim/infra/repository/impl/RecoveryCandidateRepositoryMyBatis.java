package com.chaeyeongmin.payment_sim.infra.repository.impl;

import com.chaeyeongmin.payment_sim.domain.model.RecoveryCandidate;
import com.chaeyeongmin.payment_sim.infra.mybatis.mapper.RecoveryCandidateMapper;
import com.chaeyeongmin.payment_sim.infra.repository.RecoveryCandidateRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * RecoveryCandidateRepository의 MyBatis 구현체.
 *
 * <p>서비스 계층이 MyBatis mapper를 직접 알지 않도록 mapper 호출을 repository 뒤로 숨긴다.
 */
@Repository
@RequiredArgsConstructor
public class RecoveryCandidateRepositoryMyBatis implements RecoveryCandidateRepository {

    private final RecoveryCandidateMapper mapper;

    @Override
    public List<RecoveryCandidate> findApprovalCandidates(
            LocalDateTime unknownTimeoutBefore,
            LocalDateTime staleProcessingBefore
    ) {
        // 승인 후보 판정 SQL은 mapper XML에 두고, repository는 영속성 포트 역할만 유지한다.
        return mapper.findApprovalCandidates(unknownTimeoutBefore, staleProcessingBefore);
    }

    @Override
    public List<RecoveryCandidate> findCancelCandidates(
            LocalDateTime unknownTimeoutBefore,
            LocalDateTime stalePendingBefore
    ) {
        // 취소 후보 조회는 cancel current trx와 original approval identity를 함께 보존한다.
        return mapper.findCancelCandidates(unknownTimeoutBefore, stalePendingBefore);
    }

    @Override
    public List<RecoveryCandidate> findReversalCandidates(LocalDateTime stalePendingBefore) {
        // 망취소 후보 조회는 PENDING 상태의 current trx를 복구 target으로 반환한다.
        return mapper.findReversalCandidates(stalePendingBefore);
    }

}
