package com.chaeyeongmin.payment_sim.infra.mybatis.mapper;

import com.chaeyeongmin.payment_sim.domain.model.RecoveryHistory;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;

@Mapper
public interface RecoveryHistoryMapper {

    int nextTryNo(@Param("recoveryTaskId") Long recoveryTaskId);

    RecoveryHistory insertStarted(
            @Param("recoveryTaskId") Long recoveryTaskId,
            @Param("tryNo") int tryNo,
            @Param("startedAt") LocalDateTime startedAt
    );
}
