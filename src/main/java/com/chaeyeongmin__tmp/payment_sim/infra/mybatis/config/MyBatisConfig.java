package com.chaeyeongmin.payment_sim.infra.mybatis.config;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

@Configuration
//@MapperScan(basePackages = "com.chaeyeongmin.payment_sim.infra")
// MapperScan은 PaymentSimApplication 클래스 에서만 등록 되도록, config 에서는 주석 처리
public class MyBatisConfig {
    // TODO: 필요 시 SqlSessionFactory/TypeHandler/MapperLocations 설정
}