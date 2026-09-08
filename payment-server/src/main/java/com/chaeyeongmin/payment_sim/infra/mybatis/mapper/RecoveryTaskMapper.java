package com.chaeyeongmin.payment_sim.infra.mybatis.mapper;

import com.chaeyeongmin.payment_sim.domain.model.RecoveryCandidate;
import com.chaeyeongmin.payment_sim.domain.model.RecoveryTask;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;

/**
 * PAYMENT_RECOVERY_TASK MyBatis mapper.
 *
 * <p>복구 task row 저장만 담당하고, 후보 판정이나 복구 실행 판단은 하지 않는다.
 */
@Mapper
public interface RecoveryTaskMapper {

    /**
     * 동일 복구 target의 task가 없을 때만 PENDING task를 생성한다.
     *
     * <p>중복 판정은 application SELECT가 아니라 DB unique constraint와
     * ON CONFLICT DO NOTHING에 맡긴다.
     *
     * @return 1이면 신규 생성, 0이면 이미 같은 target의 task가 존재함
     */
    int insertIfAbsent(@Param("candidate") RecoveryCandidate candidate);


    RecoveryTask claimNext(
            @Param("claimToken") String claimToken,
            @Param("now") LocalDateTime now,
            @Param("leaseExpiresAt") LocalDateTime leaseExpiresAt
    );

}
