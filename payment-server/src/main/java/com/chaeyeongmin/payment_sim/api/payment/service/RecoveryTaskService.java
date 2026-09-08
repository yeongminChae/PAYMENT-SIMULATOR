package com.chaeyeongmin.payment_sim.api.payment.service;

import com.chaeyeongmin.payment_sim.domain.model.RecoveryCandidate;
import com.chaeyeongmin.payment_sim.infra.repository.RecoveryTaskRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 복구 task 등록만 담당하는 얇은 서비스.
 *
 * <p>후보 stale 판정, VAN Inquiry 여부, retry, claim 판단은 이후 phase의 책임으로 남긴다.
 */
@Service
@RequiredArgsConstructor
public class RecoveryTaskService {

    private final RecoveryTaskRepository repository;

    /**
     * 후보를 PENDING recovery task로 등록한다.
     *
     * @return 1이면 신규 생성, 0이면 이미 같은 target의 task가 존재함
     */
    public int registerIfAbsent(RecoveryCandidate candidate) {
        return repository.insertIfAbsent(candidate);
    }
}
