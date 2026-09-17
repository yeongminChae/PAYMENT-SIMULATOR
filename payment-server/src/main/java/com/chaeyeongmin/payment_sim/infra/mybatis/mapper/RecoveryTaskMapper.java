package com.chaeyeongmin.payment_sim.infra.mybatis.mapper;

import com.chaeyeongmin.payment_sim.domain.model.RecoveryCandidate;
import com.chaeyeongmin.payment_sim.domain.model.RecoveryTask;
import com.chaeyeongmin.payment_sim.domain.policy.RecoveryStatus;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * PAYMENT_RECOVERY_TASK MyBatis mapper.
 *
 * <p>실행 가능한 행 선택과 소유권 조건은 SQL로 보장하지만, Handler 실행이나 retry 정책 판단은 하지 않는다.
 */
@Mapper
public interface RecoveryTaskMapper {

    /**
     * 상태가 일치하는 task를 최근 갱신 순으로 조회한다.
     */
    List<RecoveryTask> findByStatus(@Param("status") RecoveryStatus status);

    /**
     * task ID로 한 건을 조회한다.
     */
    RecoveryTask findById(@Param("taskId") Long taskId);

    /**
     * 동일 복구 target의 task가 없을 때만 PENDING task를 생성한다.
     *
     * <p>중복 판정은 application SELECT가 아니라 DB unique constraint와
     * ON CONFLICT DO NOTHING에 맡긴다.
     *
     * @return 1이면 신규 생성, 0이면 이미 같은 target의 task가 존재함
     */
    int insertIfAbsent(@Param("candidate") RecoveryCandidate candidate);

    /**
     * 실행 가능한 task 한 건을 잠그고 RUNNING 상태와 새 lease를 부여한다.
     */
    RecoveryTask claimNext(
            @Param("claimToken") String claimToken,
            @Param("now") LocalDateTime now,
            @Param("leaseExpiresAt") LocalDateTime leaseExpiresAt
    );

    /**
     * claim token과 lease가 아직 유효한 task만 RESOLVED로 변경한다.
     */
    int markResolved(
            @Param("taskId") Long taskId,
            @Param("claimToken") String claimToken,
            @Param("now") LocalDateTime now
    );

    /**
     * 현재 소유한 task만 RETRY_WAIT로 변경하고 retryCount를 1 증가시킨다.
     */
    int markRetryWait(
            @Param("taskId") Long taskId,
            @Param("claimToken") String claimToken,
            @Param("now") LocalDateTime now,
            @Param("nextRetryAt") LocalDateTime nextRetryAt
    );

    /**
     * 현재 소유한 task만 MANUAL_REVIEW로 변경한다.
     */
    int markManualReview(
            @Param("taskId") Long taskId,
            @Param("claimToken") String claimToken,
            @Param("now") LocalDateTime now
    );

    int requeueManualReview(
            @Param("taskId") Long taskId,
            @Param("now") LocalDateTime now
    );

}
