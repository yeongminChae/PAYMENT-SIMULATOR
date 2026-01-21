package com.chaeyeongmin.payment_sim.infra.mybatis.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

public interface PingMapper {
    @Select("SELECT 1")
    int ping();

    int pingXml();
}
