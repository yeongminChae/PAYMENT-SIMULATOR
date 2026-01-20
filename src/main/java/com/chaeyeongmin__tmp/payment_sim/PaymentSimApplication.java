package com.chaeyeongmin.payment_sim;

import com.chaeyeongmin.payment_sim.infra.mybatis.mapper.PingMapper;
import lombok.extern.slf4j.Slf4j;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@Slf4j
@MapperScan("com.chaeyeongmin.payment_sim.infra.mybatis.mapper")
public class PaymentSimApplication {
    public static void main(String[] args) {
        SpringApplication.run(PaymentSimApplication.class, args);
    }

    // 앱 시작 직후 1번 실행됨
    @org.springframework.context.annotation.Bean
    CommandLineRunner pingMybatis(PingMapper pingMapper) {
        return new CommandLineRunner() {
            @Override
            public void run(String... args) {
                int result1 = pingMapper.ping();
                int result2 = pingMapper.pingXml();
                log.info("[MYBATIS-PING] result={}", result1);
                log.info("[MYBATIS-PING-XML] result={}", result2);
            }
        };
    }

}