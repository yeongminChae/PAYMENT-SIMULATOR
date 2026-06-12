package com.chaeyeongmin.payment_sim.infra.mybatis.mapper;

import com.chaeyeongmin.payment_sim.infra.repository.dto.PaymentEventLogInsertParam;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * PAYMENT_EVENT_LOG MyBatis mapper.
 *
 * <p>
 * 1차 이벤트 로그는 결제 트랜잭션 안에서 일반 insert로 기록한다.
 */
@Mapper
public interface PaymentEventLogMapper {

    void insert(@Param("event") PaymentEventLogInsertParam event);

}
