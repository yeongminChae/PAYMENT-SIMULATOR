package com.chaeyeongmin.payment_sim.infra.mybatis.mapper;

import com.chaeyeongmin.payment_sim.domain.model.PaymentReversal;
import com.chaeyeongmin.payment_sim.infra.repository.dto.ReversalInsertParam;
import com.chaeyeongmin.payment_sim.infra.repository.dto.ReversalResultUpdateParam;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Optional;

@Mapper
public interface PaymentReversalMapper {

    Optional<PaymentReversal> findByReversalPosTrx(
            @Param("reversalPosTrx") String reversalPosTrx
    );

    Optional<PaymentReversal> findByOriginalPosTrxAndOriginalAttemptSeq(
            @Param("originalPosTrx") String originalPosTrx,
            @Param("originalAttemptSeq") int originalAttemptSeq
    );

    Optional<PaymentReversal> insertPendingReversal(
            @Param("reversal") ReversalInsertParam reversal
    );

    Optional<PaymentReversal> updateReversalResult(
            @Param("reversal") ReversalResultUpdateParam reversal
    );

    int deletePendingReversal(
            @Param("reversalPosTrx") String reversalPosTrx,
            @Param("originalPosTrx") String originalPosTrx,
            @Param("originalAttemptSeq") int originalAttemptSeq
    );
}
