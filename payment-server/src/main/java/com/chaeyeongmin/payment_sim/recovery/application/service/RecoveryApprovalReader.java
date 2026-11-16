package com.chaeyeongmin.payment_sim.recovery.application.service;

import com.chaeyeongmin.payment_sim.infra.repository.PaymentAttemptRepository;
import com.chaeyeongmin.payment_sim.payment.domain.approval.PaymentAttempt;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.Optional;

/** Materialize the attempt inside a short transaction; release its SqlSession before VAN I/O. */
@Service
@RequiredArgsConstructor
public class RecoveryApprovalReader {
    private final PaymentAttemptRepository repository;

    @Transactional(readOnly = true)
    public Optional<PaymentAttempt> find(String posTrx, int attemptSeq) {
        return repository.findByPosTrxAndAttemptSeq(posTrx, attemptSeq);
    }
}
