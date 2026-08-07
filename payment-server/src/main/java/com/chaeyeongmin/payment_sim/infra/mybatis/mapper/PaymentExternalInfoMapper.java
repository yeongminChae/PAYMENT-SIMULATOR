package com.chaeyeongmin.payment_sim.infra.mybatis.mapper;

import com.chaeyeongmin.payment_sim.infra.repository.dto.PaymentExternalInfoInsertParam;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface PaymentExternalInfoMapper {
    void insert(@Param("externalInfo") PaymentExternalInfoInsertParam externalInfo);
}
