package com.chaeyeongmin.payment_sim.infra.mybatis.mapper;

import com.chaeyeongmin.payment_sim.domain.model.PaymentAttempt;
import com.chaeyeongmin.payment_sim.domain.model.PaymentCancel;
import com.chaeyeongmin.payment_sim.infra.repository.dto.CancelInsertParam;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Optional;

@Mapper
public interface PaymentCancelMapper {

    Optional<PaymentAttempt> findOriginalAttempt(
            @Param("posTrx") String posTrx,
            @Param("attemptSeq") int attemptSeq
    );

    Optional<PaymentCancel> findByOriginalPosTrxAndOriginalAttemptSeq(
            @Param("originalPosTrx") String originalPosTrx,
            @Param("originalAttemptSeq") int originalAttemptSeq
    );

    Optional<PaymentCancel> insertPendingCancel(CancelInsertParam param);

}
