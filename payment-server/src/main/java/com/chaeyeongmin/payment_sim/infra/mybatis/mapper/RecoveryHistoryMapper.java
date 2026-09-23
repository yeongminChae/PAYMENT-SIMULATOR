package com.chaeyeongmin.payment_sim.infra.mybatis.mapper;

import com.chaeyeongmin.payment_sim.api.payment.service.recovery.transaction.RecoveryHistoryResult;
import com.chaeyeongmin.payment_sim.domain.model.RecoveryHistory;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/** PAYMENT_RECOVERY_HISTORY의 실행 시작과 종료 결과를 저장하는 MyBatis mapper다. */
@Mapper
public interface RecoveryHistoryMapper {

    /** task의 전체 실행 이력을 tryNo 오름차순으로 조회한다. */
    List<RecoveryHistory> findByRecoveryTaskId(@Param("recoveryTaskId") Long recoveryTaskId);

    /** 해당 task의 다음 실행 번호를 계산한다. */
    int nextTryNo(@Param("recoveryTaskId") Long recoveryTaskId);

    /** STARTED_AT만 채운 실행 시작 이력을 만들고 생성된 행을 반환한다. */
    RecoveryHistory insertStarted(
            @Param("recoveryTaskId") Long recoveryTaskId,
            @Param("tryNo") int tryNo,
            @Param("startedAt") LocalDateTime startedAt
    );

    /**
     * 아직 끝나지 않은 실행 이력 한 건에 결과와 종료 시각을 기록한다.
     *
     * @return 정상 종료하면 1, 이미 끝났거나 대상이 없으면 0
     */
    int finish(
            @Param("historyId") Long historyId,
            @Param("result") RecoveryHistoryResult result,
            @Param("errorCode") String errorCode,
            @Param("finishedAt") LocalDateTime finishedAt
    );

    /**
     * Task 재claim 전에 이전 Worker가 남긴 열린 History를 모두 종료한다.
     *
     * @return 종료한 History 수. 열린 History가 없으면 0
     */
    int finishOpenByRecoveryTaskId(
            @Param("recoveryTaskId") Long recoveryTaskId,
            @Param("result") RecoveryHistoryResult result,
            @Param("errorCode") String errorCode,
            @Param("finishedAt") LocalDateTime finishedAt
    );

    /** History 종료 충돌 시 현재 저장된 결과를 확인하기 위해 ID로 한 건을 조회한다. */
    RecoveryHistory findById(@Param("historyId") Long historyId);

}
