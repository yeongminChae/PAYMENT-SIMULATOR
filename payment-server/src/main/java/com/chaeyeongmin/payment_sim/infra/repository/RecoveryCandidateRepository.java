package com.chaeyeongmin.payment_sim.infra.repository;

import com.chaeyeongmin.payment_sim.domain.model.RecoveryCandidate;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 복구 작업 생성 전 단계에서 미확정 거래 후보를 조회하는 저장소 포트.
 *
 * <p>호출자는 정책에 따라 cutoff 시간을 계산해 전달하고, 이 저장소는 조회만 수행한다.
 * 원거래 상태 변경이나 recovery task 생성은 이 인터페이스의 책임이 아니다.
 */
public interface RecoveryCandidateRepository {

    /**
     * 승인 거래 중 UNKNOWN_TIMEOUT 또는 오래된 PROCESSING 상태 후보를 찾는다.
     */
    List<RecoveryCandidate> findApprovalCandidates(
            LocalDateTime unknownTimeoutBefore,
            LocalDateTime staleProcessingBefore
    );

    /**
     * 취소 거래 중 UNKNOWN_TIMEOUT 또는 오래된 PENDING 상태 후보를 찾는다.
     */
    List<RecoveryCandidate> findCancelCandidates(
            LocalDateTime unknownTimeoutBefore,
            LocalDateTime stalePendingBefore
    );

    /**
     * 망취소 거래 중 오래된 PENDING 상태 후보를 찾는다.
     */
    List<RecoveryCandidate> findReversalCandidates(
            LocalDateTime stalePendingBefore
    );
}
