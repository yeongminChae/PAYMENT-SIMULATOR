package com.chaeyeongmin.payment_sim.infra.mybatis.mapper;

import com.chaeyeongmin.payment_sim.domain.model.PaymentAttempt;
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
     * 취소 대상 원승인 attempt를 조회한다.
     */
    Optional<PaymentAttempt> findOriginalAttempt(
            @Param("posTrx") String posTrx,
            @Param("attemptSeq") int attemptSeq
    );

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

}
