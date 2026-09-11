package com.chaeyeongmin.payment_sim.infra.repository;

import com.chaeyeongmin.payment_sim.domain.model.RecoveryCandidate;
import com.chaeyeongmin.payment_sim.domain.model.RecoveryTask;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 복구 task 생성을 위한 저장소 포트.
 *
 * <p>동일 target 중복 방지는 DB unique constraint에 위임하고,
 * 호출자는 반환 row count로 신규 생성 여부만 판단한다.
 */
public interface RecoveryTaskRepository {

    /**
     * 동일 복구 target의 task가 없을 때만 PENDING task를 생성한다.
     *
     * @return 1이면 신규 생성, 0이면 이미 같은 target의 task가 존재함
     */
    int insertIfAbsent(RecoveryCandidate candidate);

    Optional<RecoveryTask> claimNext(
            String claimToken,
            LocalDateTime now,
            LocalDateTime leaseExpiresAt
    );

    int markResolved(
            Long taskId,
            String claimToken,
            LocalDateTime now
    );

}
