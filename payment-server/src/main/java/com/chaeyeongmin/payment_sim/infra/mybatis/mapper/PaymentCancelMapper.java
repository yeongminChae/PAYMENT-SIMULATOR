package com.chaeyeongmin.payment_sim.infra.mybatis.mapper;

import com.chaeyeongmin.payment_sim.domain.model.PaymentCancel;
import com.chaeyeongmin.payment_sim.infra.repository.dto.CancelInsertParam;
import com.chaeyeongmin.payment_sim.infra.repository.dto.CancelResultUpdateParam;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Optional;

/**
 * PAYMENT_CANCEL 흐름에서 사용하는 MyBatis SQL Mapper.
 *
 * <p>
 * Service/Repository 계층은 이 Mapper를 통해 원거래 조회, 기존 취소 row 조회,
 * PENDING insert, 최종 결과 update를 수행한다.
 */
@Mapper
public interface PaymentCancelMapper {
    /**
     * 현거래 기준으로 만들어진 기존 PAYMENT_CANCEL row가 존재하는지 조회한다.
     */
    Optional<PaymentCancel> findByPosTrx(
            @Param("posTrx") String posTrx
    );

    /**
     * 원거래 기준으로 기존 PAYMENT_CANCEL row를 조회한다.
     */
    Optional<PaymentCancel> findByOriginalPosTrxAndOriginalAttemptSeq(
            @Param("originalPosTrx") String originalPosTrx,
            @Param("originalAttemptSeq") int originalAttemptSeq
    );

    /**
     * VAN cancel 호출 전에 PENDING cancel row를 생성한다.
     */
    Optional<PaymentCancel> insertPendingCancel(
            @Param("cancel") CancelInsertParam cancel
    );

    /**
     * PENDING cancel row를 VAN 최종 결과로 확정한다.
     */
    Optional<PaymentCancel> updateCancelResult(
            @Param("cancel") CancelResultUpdateParam param
    );

    /**
     * UNKNOWN_TIMEOUT cancel row를 VAN inquiry 최종 결과로 확정한다.
     *
     * <p>
     * 일반 취소 확정 update와 별도 SQL을 써서 PENDING 전용 WHERE 조건이 넓어지는 것을 막는다.
     */
    Optional<PaymentCancel> updateUnknownTimeoutToFinal(
            @Param("cancel") CancelResultUpdateParam param
    );

}
