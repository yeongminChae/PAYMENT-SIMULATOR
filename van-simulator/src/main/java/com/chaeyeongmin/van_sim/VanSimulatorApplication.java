package com.chaeyeongmin.van_sim;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * VAN 시뮬레이터 애플리케이션의 Spring Boot 진입점이다.
 * <p>
 * 승인 요청 처리, 테스트 시나리오 제어, VAN 승인 원장 저장 기능을 하나의 서버로 기동한다.
 */
@SpringBootApplication
public class VanSimulatorApplication {

    public static void main(String[] args) {
        SpringApplication.run(VanSimulatorApplication.class, args);
    }
}
