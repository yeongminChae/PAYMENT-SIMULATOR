package com.chaeyeongmin.van_sim.transaction.inquiry.service.impl;

import com.chaeyeongmin.van_sim.ledger.approval.entity.VanApproval;
import com.chaeyeongmin.van_sim.ledger.approval.repository.VanApprovalRepository;
import com.chaeyeongmin.van_sim.ledger.approval.status.VanApprovalStatus;
import com.chaeyeongmin.van_sim.transaction.inquiry.service.InquiryService;
import com.chaeyeongmin.van_sim.transaction.inquiry.service.result.InquiryResult;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * (posTrx, attemptSeq)로 VAN 승인 원장을 조회하고 저장된 업무 결과를 반환한다.
 * Inquiry는 승인을 재실행하거나 새로운 원장을 저장하지 않는다.
 */
@Service
@Profile("postgres")
@RequiredArgsConstructor
public class InquiryServiceImpl implements InquiryService {

    private final VanApprovalRepository repository;

    @Override
    @Transactional(readOnly = true)
    public InquiryResult inquire(String posTrx, int attemptSeq) {
        return repository.findByPosTrxAndAttemptSeq(posTrx, attemptSeq)
                .map(InquiryServiceImpl::toResult)
                .orElseGet(() -> unknownResult(posTrx, attemptSeq));
    }

    private static InquiryResult toResult(VanApproval approval) {
        return new InquiryResult(
                approval.getVanTrxId(),
                approval.getPosTrx(),
                approval.getAttemptSeq(),
                approval.getApprovalStatus(),
                approval.getApprovalNo(),
                approval.getDeclineCode(),
                approval.getProcessedAt()
        );
    }

    private static InquiryResult unknownResult(String posTrx, int attemptSeq) {
        return new InquiryResult(
                null,
                posTrx,
                attemptSeq,
                VanApprovalStatus.UNKNOWN,
                null,
                null,
                null
        );
    }
}
