package com.chaeyeongmin.payment_sim.api.payment.service;

import com.chaeyeongmin.payment_sim.domain.model.RecoveryCandidate;
import com.chaeyeongmin.payment_sim.infra.repository.RecoveryTaskRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 복구 task 등록만 담당하는 얇은 서비스.
 *
 * <p>후보 선정은 Discovery Service가 담당하고, VAN Inquiry·retry·claim 판단은
 * 기존 Recovery Worker와 Handler가 담당한다. 이 서비스는 전달받은 후보를 등록만 한다.
 */
@Service
@RequiredArgsConstructor
public class RecoveryTaskService {

    private final RecoveryTaskRepository repository;

    /**
     * 후보를 PENDING Recovery Task로 등록한다. 같은 대상의 Task가 이미 있으면 새로 만들지 않는다.
     *
     * @return 1이면 신규 생성, 0이면 이미 같은 target의 task가 존재함
     */
    public int registerIfAbsent(RecoveryCandidate candidate) {
        return repository.insertIfAbsent(candidate);
    }
}
