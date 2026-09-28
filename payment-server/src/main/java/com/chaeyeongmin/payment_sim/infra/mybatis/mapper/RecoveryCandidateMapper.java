package com.chaeyeongmin.payment_sim.infra.mybatis.mapper;

import com.chaeyeongmin.payment_sim.domain.model.RecoveryCandidate;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 복구 작업 생성 전 단계에서 미확정 거래 후보를 읽는 MyBatis Mapper.
 *
 * <p>후보 판정 시간은 정책을 가진 호출자가 계산해 전달한다. 이 Mapper는 원거래 상태를
 * 변경하거나 recovery task를 생성하지 않고, 거래 유형별 후보 조회만 수행한다.
 */
@Mapper
public interface RecoveryCandidateMapper {

    List<RecoveryCandidate> findApprovalCandidates(
            @Param("unknownTimeoutBefore") LocalDateTime unknownTimeoutBefore,
            @Param("staleProcessingBefore") LocalDateTime staleProcessingBefore
    );

    List<RecoveryCandidate> findCancelCandidates(
            @Param("unknownTimeoutBefore") LocalDateTime unknownTimeoutBefore,
            @Param("stalePendingBefore") LocalDateTime stalePendingBefore
    );

    List<RecoveryCandidate> findReversalCandidates(
            @Param("stalePendingBefore") LocalDateTime stalePendingBefore
    );
}
