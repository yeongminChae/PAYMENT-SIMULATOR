package com.chaeyeongmin.payment_sim.infra.mybatis.mapper;

import com.chaeyeongmin.payment_sim.domain.model.PaymentAttempt;
import com.chaeyeongmin.payment_sim.infra.repository.dto.AttemptResultUpdateParam;
import com.chaeyeongmin.payment_sim.infra.repository.dto.PaymentAttemptUpdatedRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Optional;

@Mapper
public interface PaymentInquiryMapper {

    Optional<PaymentAttempt> findByPosTrxAndAttemptSeq(
            @Param("posTrx") String posTrx,
            @Param("attemptSeq") int attemptSeq
    );

    Optional<PaymentAttemptUpdatedRow> updateUnknownToFinal(
            @Param("attempt") AttemptResultUpdateParam attempt
    );

}
