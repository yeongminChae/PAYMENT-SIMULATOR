package com.chaeyeongmin.payment_sim.infra.repository;

import com.chaeyeongmin.payment_sim.domain.model.RecoveryCandidate;
import com.chaeyeongmin.payment_sim.domain.model.RecoveryTask;
import com.chaeyeongmin.payment_sim.domain.policy.RecoveryStatus;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Recovery Task 생성, claim, 상태 전이를 위한 저장소 포트다.
 *
 * <p>claim과 상태 전이는 claim token과 lease를 이용해 현재 Worker의 소유권을 지킨다.
 * 동일 target 생성 방지는 DB unique constraint에 맡긴다.
 */
public interface RecoveryTaskRepository {

    /**
     * 상태가 일치하는 task를 최근 갱신 순으로 조회한다.
     */
    List<RecoveryTask> findByStatus(RecoveryStatus status);

    /**
     * 관리자 상세 조회를 위해 task ID로 한 건을 조회한다.
     */
    Optional<RecoveryTask> findById(Long taskId);

    /**
     * 동일 복구 target의 task가 없을 때만 PENDING task를 생성한다.
     *
     * @return 1이면 신규 생성, 0이면 이미 같은 target의 task가 존재함
     */
    int insertIfAbsent(RecoveryCandidate candidate);

    /**
     * 지금 실행할 수 있는 task 한 건을 골라 RUNNING으로 claim한다.
     *
     * <p>PENDING, 실행 시각이 지난 RETRY_WAIT, lease가 만료된 RUNNING이 대상이다.
     * 다른 transaction이 잡고 있는 행은 건너뛴다.
     */
    Optional<RecoveryTask> claimNext(String claimToken, LocalDateTime now, LocalDateTime leaseExpiresAt);

    /**
     * 현재 Worker가 소유한 RUNNING task를 RESOLVED로 끝낸다.
     */
    int markResolved(Long taskId, String claimToken, LocalDateTime now);

    /**
     * 미해결 task의 retryCount를 올리고 다음 실행 시각까지 RETRY_WAIT로 보낸다.
     */
    int markRetryWait(Long taskId, String claimToken, LocalDateTime now, LocalDateTime nextRetryAt);

    /**
     * 자동 복구를 중단하고 task를 MANUAL_REVIEW로 보낸다.
     */
    int markManualReview(Long taskId, String claimToken, LocalDateTime now);

    /**
     * 운영자가 다시 실행하기로 한 MANUAL_REVIEW Task를 PENDING으로 되돌린다.
     * retry 횟수, 다음 실행 시각, 이전 Worker의 소유권 정보도 함께 초기화한다.
     *
     * @return 상태를 변경하면 1, Task가 없거나 MANUAL_REVIEW가 아니면 0
     */
    int requeueManualReview(Long taskId, LocalDateTime now);

}
