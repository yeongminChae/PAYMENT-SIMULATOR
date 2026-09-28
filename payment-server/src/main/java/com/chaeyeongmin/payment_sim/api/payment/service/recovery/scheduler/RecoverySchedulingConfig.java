package com.chaeyeongmin.payment_sim.api.payment.service.recovery.scheduler;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Spring의 정기 실행 기능을 켠다.
 * 실제 Recovery 스케줄러 생성 여부는 {@code payment.recovery.schedule.enabled} 설정이 결정한다.
 */
@Profile("postgres")
@Configuration
@EnableScheduling
public class RecoverySchedulingConfig {
}
