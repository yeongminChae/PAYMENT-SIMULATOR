package com.chaeyeongmin.payment_sim.api.payment.service.recovery.admin;

import com.chaeyeongmin.payment_sim.api.payment.recovery.dto.RecoveryTaskSummaryResponse;
import com.chaeyeongmin.payment_sim.api.payment.recovery.exception.RecoveryTaskNotFoundException;
import com.chaeyeongmin.payment_sim.api.payment.recovery.exception.RecoveryTaskRequeueConflictException;
import com.chaeyeongmin.payment_sim.domain.model.RecoveryTask;
import com.chaeyeongmin.payment_sim.infra.repository.RecoveryTaskRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;

/** 운영자의 Recovery Task 상태 변경 요청을 처리한다. */
@Service
@RequiredArgsConstructor
public class RecoveryAdminCommandService {

    private final RecoveryTaskRepository repository;
    private final Clock clock;

    /**
     * MANUAL_REVIEW Task를 다시 자동 복구할 수 있도록 PENDING 상태로 되돌린다.
     *
     * <p>상태 변경과 변경된 Task 재조회는 한 트랜잭션에서 실행한다. 조건부 update가 0건이면
     * Task를 다시 조회해, Task 자체가 없으면 NOT_FOUND, 다른 상태이면 CONFLICT로 구분한다.
     *
     * @return PENDING으로 변경되고 retry 정보가 초기화된 Task
     */
    @Transactional
    public RecoveryTaskSummaryResponse requeue(Long taskId) {
        // 테스트와 운영 코드가 같은 시간 기준을 사용하도록 주입받은 Clock으로 변경 시각을 구한다.
        LocalDateTime now = LocalDateTime.now(clock);

        // SQL이 MANUAL_REVIEW 상태를 조건으로 검사하므로 Service가 상태를 미리 읽어 판단하지 않는다.
        int updated = repository.requeueManualReview(taskId, now);

        if (updated == 1) {
            // update 결과를 다시 읽어 DB에 실제 저장된 값을 응답으로 돌려준다.
            RecoveryTask task = repository.findById(taskId)
                    .orElseThrow(() -> new RecoveryTaskNotFoundException(taskId));

            return RecoveryTaskSummaryResponse.from(task);
        }

        // update가 되지 않은 이유가 미존재인지 상태 충돌인지 현재 DB 값으로 구분한다.
        RecoveryTask task = repository.findById(taskId)
                .orElseThrow(() -> new RecoveryTaskNotFoundException(taskId));

        throw new RecoveryTaskRequeueConflictException(
                taskId,
                task.recoveryStatus()
        );
    }

}
