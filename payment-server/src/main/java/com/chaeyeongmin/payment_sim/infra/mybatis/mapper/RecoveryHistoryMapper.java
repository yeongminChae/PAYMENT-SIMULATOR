package com.chaeyeongmin.payment_sim.infra.mybatis.mapper;

import com.chaeyeongmin.payment_sim.api.payment.service.recovery.transaction.RecoveryHistoryResult;
import com.chaeyeongmin.payment_sim.domain.model.RecoveryHistory;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;

/** PAYMENT_RECOVERY_HISTORY의 실행 시작과 종료 결과를 저장하는 MyBatis mapper다. */
@Mapper
public interface RecoveryHistoryMapper {

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
}
