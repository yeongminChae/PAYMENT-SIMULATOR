package com.chaeyeongmin.payment_sim.api.payment.service.recovery.batch;

import com.chaeyeongmin.payment_sim.api.payment.service.recovery.worker.RecoveryWorker;
import com.chaeyeongmin.payment_sim.api.payment.service.recovery.worker.RecoveryWorkerResult;
import com.chaeyeongmin.payment_sim.api.payment.service.recovery.worker.RecoveryWorkerResultType;
import lombok.RequiredArgsConstructor;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.stereotype.Component;

/** Recovery Worker를 반복 실행해 현재 처리 가능한 Recovery Task를 모두 처리한다. */
@Component
@RequiredArgsConstructor
public class RecoveryProcessTasklet implements Tasklet {

    private final RecoveryWorker recoveryWorker;

    /**
     * Worker가 {@code NO_TASK}를 반환할 때까지 Recovery Task를 한 건씩 처리한다.
     *
     * <p>{@code RESOLVED}, {@code RETRY_WAIT}, {@code MANUAL_REVIEW}, {@code OWNERSHIP_LOST}는
     * Worker가 이미 결정한 업무 결과이므로 Batch에서 다시 판단하지 않고 다음 Task를 처리한다.
     * Worker가 예외를 던지면 잡지 않고 그대로 전달해 Step과 Job을 실패 처리한다.
     */
    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        while (true) {
            // Worker가 기존 정책과 트랜잭션 경계 안에서 Recovery Task 한 건을 처리한다.
            RecoveryWorkerResult result = recoveryWorker.executeOne();

            if (result.resultType() == RecoveryWorkerResultType.NO_TASK) {
                // 지금 실행할 수 있는 Task가 없으므로 Process Step을 정상 종료한다.
                return RepeatStatus.FINISHED;
            }
        }
    }
}
