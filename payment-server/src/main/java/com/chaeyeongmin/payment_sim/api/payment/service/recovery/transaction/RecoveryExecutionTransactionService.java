package com.chaeyeongmin.payment_sim.api.payment.service.recovery.transaction;

import com.chaeyeongmin.payment_sim.domain.model.RecoveryHistory;
import com.chaeyeongmin.payment_sim.domain.model.RecoveryTask;
import com.chaeyeongmin.payment_sim.infra.repository.RecoveryHistoryRepository;
import com.chaeyeongmin.payment_sim.infra.repository.RecoveryTaskRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class RecoveryExecutionTransactionService {

    private final RecoveryTaskRepository recoveryTaskRepository;
    private final RecoveryHistoryRepository recoveryHistoryRepository;

    @Transactional
    public Optional<ClaimedRecoveryExecution> claimAndStart(
            String claimToken,
            LocalDateTime now,
            LocalDateTime leaseExpiresAt
    ) {
        Optional<RecoveryTask> taskOptional = recoveryTaskRepository.claimNext(
                claimToken,
                now,
                leaseExpiresAt
        );

        if (taskOptional.isEmpty()) {
            return Optional.empty();
        }

        RecoveryTask task = taskOptional.get();
        int tryNo = recoveryHistoryRepository.nextTryNo(task.id());
        RecoveryHistory history = recoveryHistoryRepository.insertStarted(task.id(), tryNo, now);

        return Optional.of(new ClaimedRecoveryExecution(task, history));
    }
}
