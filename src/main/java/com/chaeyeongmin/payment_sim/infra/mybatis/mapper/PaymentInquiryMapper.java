package com.chaeyeongmin.payment_sim.infra.mybatis.mapper;

import com.chaeyeongmin.payment_sim.domain.model.PaymentAttempt;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Optional;

@Mapper
public interface PaymentInquiryMapper {

    Optional<PaymentAttempt> findByPosTrxAndAttemptSeq(
            @Param("posTrx") String posTrx,
            @Param("attemptSeq") int attemptSeq
    );

}
