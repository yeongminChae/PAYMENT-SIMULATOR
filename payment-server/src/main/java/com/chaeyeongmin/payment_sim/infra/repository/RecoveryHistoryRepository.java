package com.chaeyeongmin.payment_sim.infra.repository;

import com.chaeyeongmin.payment_sim.api.payment.service.recovery.transaction.RecoveryHistoryResult;
import com.chaeyeongmin.payment_sim.domain.model.RecoveryHistory;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/** Recovery Task의 실행 시도 이력을 저장하는 저장소 포트다. */
public interface RecoveryHistoryRepository {

    /** task의 전체 실행 이력을 실행 순서대로 조회한다. */
    List<RecoveryHistory> findByRecoveryTaskId(Long recoveryTaskId);

    /** 기존 실행 이력의 가장 큰 tryNo보다 1 큰 값을 구한다. 이력이 없으면 1이다. */
    int nextTryNo(Long recoveryTaskId);

    /**
     * Worker 실행 시작 시각을 기록한다.
     *
     * <p>아직 실행 결과가 나오기 전이므로 result, errorCode, finishedAt은 null로 저장한다.
     */
    RecoveryHistory insertStarted(Long recoveryTaskId, int tryNo, LocalDateTime startedAt);

    /**
     * 아직 종료되지 않은 실행 이력에 최종 결과를 기록한다.
     *
     * @return 정상 종료하면 1, 이미 끝났거나 대상이 없으면 0
     */
    int finish(Long historyId, RecoveryHistoryResult result, String errorCode, LocalDateTime finishedAt);

    int finishOpenByRecoveryTaskId(
            Long recoveryTaskId,
            RecoveryHistoryResult result,
            String errorCode,
            LocalDateTime finishedAt
    );

    Optional<RecoveryHistory> findById(Long historyId);

}
