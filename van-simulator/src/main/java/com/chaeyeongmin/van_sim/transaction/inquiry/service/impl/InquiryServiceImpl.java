package com.chaeyeongmin.van_sim.transaction.inquiry.service.impl;

import com.chaeyeongmin.van_sim.ledger.approval.entity.VanApproval;
import com.chaeyeongmin.van_sim.ledger.approval.repository.VanApprovalRepository;
import com.chaeyeongmin.van_sim.ledger.approval.status.VanApprovalStatus;
import com.chaeyeongmin.van_sim.ledger.cancel.entity.VanCancel;
import com.chaeyeongmin.van_sim.ledger.cancel.repository.VanCancelRepository;
import com.chaeyeongmin.van_sim.ledger.cancel.status.VanCancelStatus;
import com.chaeyeongmin.van_sim.transaction.inquiry.service.InquiryService;
import com.chaeyeongmin.van_sim.transaction.inquiry.service.result.CancelInquiryResult;
import com.chaeyeongmin.van_sim.transaction.inquiry.service.result.ApprovalInquiryResult;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * (posTrx, attemptSeq)로 VAN 승인 원장을 조회하고 저장된 업무 결과를 반환한다.
 * Inquiry는 승인을 재실행하거나 새로운 원장을 저장하지 않는다.
 * <p>
 * Release 4의 핵심 복구 흐름은 "승인은 VAN에서 이미 commit됐지만 Payment가 TCP 응답을 못 받은 경우"다.
 * 이 서비스는 그 상황에서 VAN DB의 정본인 van_approval row를 읽어 Payment가 APPROVED/DECLINED로 복구할 수 있게 한다.
 * 메서드 전체가 readOnly transaction인 것도 이 계층이 조회만 담당한다는 의도를 분명히 하기 위함이다.
 */
@Service
@Profile("postgres")
@RequiredArgsConstructor
public class InquiryServiceImpl implements InquiryService {

    private final VanApprovalRepository approvalRepository;
    private final VanCancelRepository cancelRepository;

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

    @Override
    @Transactional(readOnly = true)
    public Optional<CancelInquiryResult> inquireCancel(String cancelPosTrx) {
        return cancelRepository
                .findByCancelPosTrx(cancelPosTrx)
                .map(cancel ->  InquiryServiceImpl.toCancelResult(cancel));
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

}
