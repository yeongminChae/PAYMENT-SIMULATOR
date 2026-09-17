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

@Service
@RequiredArgsConstructor
public class RecoveryAdminCommandService {

    private final RecoveryTaskRepository repository;
    private final Clock clock;

    @Transactional
    public RecoveryTaskSummaryResponse requeue(Long taskId) {
        LocalDateTime now = LocalDateTime.now(clock);

        int updated = repository.requeueManualReview(taskId, now);

        if (updated == 1) {
            RecoveryTask task = repository.findById(taskId)
                    .orElseThrow(() -> new RecoveryTaskNotFoundException(taskId));

            return RecoveryTaskSummaryResponse.from(task);
        }

        RecoveryTask task = repository.findById(taskId)
                .orElseThrow(() -> new RecoveryTaskNotFoundException(taskId));

        throw new RecoveryTaskRequeueConflictException(
                taskId,
                task.recoveryStatus()
        );
    }

}
