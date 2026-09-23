package com.chaeyeongmin.payment_sim.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * 애플리케이션에서 사용하는 현재 시간의 기준을 제공한다.
 *
 * <p>운영에서는 시스템 기본 시간대를 사용하는 실제 시계를 주입한다. 시간에 의존하는 클래스가
 * {@code LocalDateTime.now()}를 직접 호출하지 않고 이 Clock을 받으면, 테스트에서는 고정된 Clock으로
 * 교체해 claim 시각과 lease 만료 같은 시간 경계 조건을 재현할 수 있다.
 */
@Configuration
public class TimeConfig {

    /**
     * 운영 환경에서 실제 현재 시간을 반환하는 JDK Clock을 등록한다.
     *
     * @return 서버의 기본 시간대를 기준으로 동작하는 시스템 Clock
     */
    @Bean
    public Clock clock() {
        return Clock.systemDefaultZone();
    }
}
