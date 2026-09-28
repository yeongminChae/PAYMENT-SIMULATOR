package com.chaeyeongmin.van_sim.transaction.inquiry.service.impl;

import com.chaeyeongmin.van_sim.ledger.approval.entity.VanApproval;
import com.chaeyeongmin.van_sim.ledger.approval.repository.VanApprovalRepository;
import com.chaeyeongmin.van_sim.ledger.approval.status.VanApprovalStatus;
import com.chaeyeongmin.van_sim.ledger.cancel.entity.VanCancel;
import com.chaeyeongmin.van_sim.ledger.cancel.repository.VanCancelRepository;
import com.chaeyeongmin.van_sim.ledger.cancel.status.VanCancelStatus;
import com.chaeyeongmin.van_sim.ledger.reversal.entity.VanReversal;
import com.chaeyeongmin.van_sim.ledger.reversal.repository.VanReversalRepository;
import com.chaeyeongmin.van_sim.transaction.inquiry.service.InquiryService;
import com.chaeyeongmin.van_sim.transaction.inquiry.service.result.CancelInquiryResult;
import com.chaeyeongmin.van_sim.transaction.inquiry.service.result.ApprovalInquiryResult;
import com.chaeyeongmin.van_sim.transaction.inquiry.service.result.ReversalInquiryResult;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 거래 종류별 식별 키로 VAN 원장을 조회하고 저장된 업무 결과를 반환한다.
 * Inquiry는 거래를 재실행하거나 새로운 원장을 저장하지 않는다.
 * <p>
 * Payment가 TCP 응답을 받지 못했더라도 VAN에는 거래 결과가 이미 저장됐을 수 있다.
 * 이 서비스는 VAN DB의 승인·취소·망취소 원장을 정본으로 읽어 Payment가 결과를 복구할 수 있게 한다.
 * 각 조회를 readOnly transaction으로 실행해 조회 과정에서 원장 상태가 바뀌지 않게 한다.
 */
@Service
@Profile("postgres")
@RequiredArgsConstructor
public class InquiryServiceImpl implements InquiryService {

    private final VanApprovalRepository approvalRepository;
    private final VanCancelRepository cancelRepository;
    private final VanReversalRepository reversalRepository;

    /**
     * VAN 승인 원장을 조회한다.
     *
     * 원장이 존재하면 저장된 APPROVED/DECLINED/UNKNOWN 상태를 그대로 반환하고,
     * 원장이 존재하지 않으면 Optional.empty()를 반환한다.
     * 원장 부재는 UNKNOWN 상태와 구분하며 TCP 계층에서 NOT_FOUND로 표현한다.
     */
    @Override
    @Transactional(readOnly = true)
    public Optional<ApprovalInquiryResult> inquireApproval(String posTrx, int attemptSeq) {
        return approvalRepository
                .findByPosTrxAndAttemptSeq(posTrx, attemptSeq)
                .map(approval ->  InquiryServiceImpl.toApprovalResult(approval));
    }

    /** cancelPosTrx로 기존 취소 원장을 조회하고 저장된 결과만 반환한다. */
    @Override
    @Transactional(readOnly = true)
    public Optional<CancelInquiryResult> inquireCancel(String cancelPosTrx) {
        return cancelRepository
                .findByCancelPosTrx(cancelPosTrx)
                .map(cancel ->  InquiryServiceImpl.toCancelResult(cancel));
    }

    /**
     * reversalPosTrx로 기존 망취소 원장을 조회한다.
     * 원장이 없으면 Optional.empty()를 반환하며 망취소를 새로 만들거나 재실행하지 않는다.
     */
    @Override
    @Transactional(readOnly = true)
    public Optional<ReversalInquiryResult> inquireReversal(String reversalPosTrx) {
        return reversalRepository
                .findByReversalPosTrx(reversalPosTrx)
                .map(InquiryServiceImpl::toReversalResult);
    }

    /**
     * van_approval 엔티티를 TCP/HTTP 같은 외부 표현과 분리된 서비스 결과 객체로 옮긴다.
     * <p>
     * 이 메서드는 값을 해석하거나 보정하지 않는다.
     * VAN 원장에 APPROVED와 approvalNo가 저장되어 있으면 그대로 반환하고,
     * DECLINED와 declineCode 또는 UNKNOWN도 DB에 기록된 상태 그대로 상위 계층에 넘긴다.
     */
    private static ApprovalInquiryResult toApprovalResult(VanApproval approval) {
        return new ApprovalInquiryResult(
                approval.getVanTrxId(),
                approval.getPosTrx(),
                approval.getAttemptSeq(),
                approval.getApprovalStatus(),
                approval.getApprovalNo(),
                approval.getDeclineCode(),
                approval.getProcessedAt()
        );
    }

    /** 취소 원장 값을 해석하거나 보정하지 않고 서비스 조회 결과로 옮긴다. */
    private static CancelInquiryResult toCancelResult(VanCancel cancel) {
        return new CancelInquiryResult(
                cancel.getVanCancelTrxId(),
                cancel.getCancelPosTrx(),
                cancel.getCancelStatus(),
                cancel.getCancelApprovalNo(),
                cancel.getDeclineCode(),
                cancel.getProcessedAt()
        );
    }

    /** 망취소 원장 값을 해석하거나 보정하지 않고 서비스 조회 결과로 옮긴다. */
    private static ReversalInquiryResult toReversalResult(VanReversal reversal) {
        return new ReversalInquiryResult(
                reversal.getVanReversalTrxId(),
                reversal.getReversalPosTrx(),
                reversal.getReversalStatus(),
                reversal.getReversalApprovalNo(),
                reversal.getDeclineCode(),
                reversal.getProcessedAt()
        );
    }

}
