package com.chaeyeongmin.payment_sim.api.payment.service.recovery.discovery;

import com.chaeyeongmin.payment_sim.api.payment.service.RecoveryTaskService;
import com.chaeyeongmin.payment_sim.domain.model.RecoveryCandidate;
import com.chaeyeongmin.payment_sim.domain.policy.RecoveryTargetType;
import com.chaeyeongmin.payment_sim.infra.repository.RecoveryCandidateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RecoveryDiscoveryServiceTest {

    private static final ZoneId ZONE_ID = ZoneId.of("Asia/Seoul");
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 17, 12, 0);
    private static final Clock CLOCK = Clock.fixed(NOW.atZone(ZONE_ID).toInstant(), ZONE_ID);

    private RecoveryCandidateRepository repository;
    private RecoveryTaskService recoveryTaskService;
    private RecoveryDiscoveryService service;

    @BeforeEach
    void setUp() {
        repository = mock(RecoveryCandidateRepository.class);
        recoveryTaskService = mock(RecoveryTaskService.class);
        service = new RecoveryDiscoveryService(repository, recoveryTaskService, CLOCK);
    }

    @Test
    void clock기준cutoff로모든후보를등록하고결과건수를반환한다() {
        Duration unknownTimeoutAge = Duration.ofSeconds(30);
        Duration staleProcessingAge = Duration.ofMinutes(2);
        Duration stalePendingAge = Duration.ofMinutes(3);
        RecoveryCandidate approval = candidate(RecoveryTargetType.APPROVAL, "A-1", 1);
        RecoveryCandidate cancel = candidate(RecoveryTargetType.CANCEL, "C-1", null);
        RecoveryCandidate reversal = candidate(RecoveryTargetType.REVERSAL, "R-1", null);

        when(repository.findApprovalCandidates(NOW.minusSeconds(30), NOW.minusMinutes(2)))
                .thenReturn(List.of(approval));
        when(repository.findCancelCandidates(NOW.minusSeconds(30), NOW.minusMinutes(3)))
                .thenReturn(List.of(cancel));
        when(repository.findReversalCandidates(NOW.minusMinutes(3)))
                .thenReturn(List.of(reversal));
        when(recoveryTaskService.registerIfAbsent(approval)).thenReturn(1);
        when(recoveryTaskService.registerIfAbsent(cancel)).thenReturn(0);
        when(recoveryTaskService.registerIfAbsent(reversal)).thenReturn(1);

        RecoveryDiscoveryResult result = service.discoverAndRegister(
                unknownTimeoutAge,
                staleProcessingAge,
                stalePendingAge
        );

        assertThat(result).isEqualTo(new RecoveryDiscoveryResult(3, 2));
        verify(repository).findApprovalCandidates(NOW.minusSeconds(30), NOW.minusMinutes(2));
        verify(repository).findCancelCandidates(NOW.minusSeconds(30), NOW.minusMinutes(3));
        verify(repository).findReversalCandidates(NOW.minusMinutes(3));
        verify(recoveryTaskService).registerIfAbsent(approval);
        verify(recoveryTaskService).registerIfAbsent(cancel);
        verify(recoveryTaskService).registerIfAbsent(reversal);
    }

    @Test
    void 이미등록된후보만있으면registeredCount는0이다() {
        RecoveryCandidate approval = candidate(RecoveryTargetType.APPROVAL, "A-1", 1);
        when(repository.findApprovalCandidates(NOW.minusSeconds(30), NOW.minusMinutes(1)))
                .thenReturn(List.of(approval));
        when(repository.findCancelCandidates(NOW.minusSeconds(30), NOW.minusMinutes(1)))
                .thenReturn(List.of());
        when(repository.findReversalCandidates(NOW.minusMinutes(1)))
                .thenReturn(List.of());
        when(recoveryTaskService.registerIfAbsent(approval)).thenReturn(0);

        RecoveryDiscoveryResult result = service.discoverAndRegister(
                Duration.ofSeconds(30),
                Duration.ofMinutes(1),
                Duration.ofMinutes(1)
        );

        assertThat(result).isEqualTo(new RecoveryDiscoveryResult(1, 0));
    }

    private static RecoveryCandidate candidate(
            RecoveryTargetType targetType,
            String targetTrxNo,
            Integer targetAttemptSeq
    ) {
        return new RecoveryCandidate(
                targetType,
                targetTrxNo,
                targetAttemptSeq,
                "ORIGINAL-1",
                1,
                NOW.minusMinutes(5)
        );
    }
}
