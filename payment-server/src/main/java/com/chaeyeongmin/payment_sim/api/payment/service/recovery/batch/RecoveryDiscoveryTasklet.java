package com.chaeyeongmin.payment_sim.api.payment.service.recovery.batch;

import com.chaeyeongmin.payment_sim.api.payment.service.recovery.discovery.RecoveryDiscoveryService;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 복구 후보를 한 번 조회하고 Recovery Task로 등록하는 Batch Tasklet이다.
 * 후보 선정과 등록은 직접 처리하지 않고 {@link RecoveryDiscoveryService}에 맡긴다.
 */
@Component
public class RecoveryDiscoveryTasklet implements Tasklet {

    private final RecoveryDiscoveryService recoveryDiscoveryService;

    /** UNKNOWN_TIMEOUT 상태가 된 뒤 후보로 조회하기까지 기다릴 시간이다. */
    private final Duration unknownTimeoutAge;

    /** 오래 멈춰 있는 승인 PROCESSING 거래를 후보로 볼 기준 시간이다. */
    private final Duration staleProcessingAge;

    /** 오래 멈춰 있는 취소·망취소 PENDING 거래를 후보로 볼 기준 시간이다. */
    private final Duration stalePendingAge;

    /** application.yml의 후보 조회 시간 설정과 실제 조회 서비스를 받는다. */
    public RecoveryDiscoveryTasklet(
            RecoveryDiscoveryService recoveryDiscoveryService,
            @Value("${payment.recovery.discovery.unknown-timeout-age}") Duration unknownTimeoutAge,
            @Value("${payment.recovery.discovery.stale-processing-age}") Duration staleProcessingAge,
            @Value("${payment.recovery.discovery.stale-pending-age}") Duration stalePendingAge
    ) {
        this.recoveryDiscoveryService = recoveryDiscoveryService;
        this.unknownTimeoutAge = unknownTimeoutAge;
        this.staleProcessingAge = staleProcessingAge;
        this.stalePendingAge = stalePendingAge;
    }

    /** Discovery Service를 한 번 호출한 뒤 이 Step의 작업이 끝났음을 Batch에 알린다. */
    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        recoveryDiscoveryService.discoverAndRegister(
                unknownTimeoutAge,
                staleProcessingAge,
                stalePendingAge
        );
        return RepeatStatus.FINISHED;
    }
}
