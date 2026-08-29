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
 * <p>
 * Release 4의 핵심 복구 흐름은 "승인은 VAN에서 이미 commit됐지만 Payment가 TCP 응답을 못 받은 경우"다.
 * 이 서비스는 그 상황에서 VAN DB의 정본인 van_approval row를 읽어 Payment가 APPROVED/DECLINED로 복구할 수 있게 한다.
 * 메서드 전체가 readOnly transaction인 것도 이 계층이 조회만 담당한다는 의도를 분명히 하기 위함이다.
 */
@Service
@Profile("postgres")
@RequiredArgsConstructor
public class InquiryServiceImpl implements InquiryService {

    private final VanApprovalRepository repository;

    /**
     * VAN 승인 원장을 조회하는 Inquiry 업무의 본문이다.
     * <p>
     * 원장이 존재하면 DB에 저장된 값을 그대로 {@link InquiryResult}로 변환한다.
     * 원장이 없을 때 새 승인 row를 만들지 않는 점이 중요하다.
     * Payment의 후속 Inquiry는 재승인이 아니라 사후 조회이므로,
     * 여기서 insert/update가 발생하면 같은 거래가 두 번 승인된 것처럼 보일 수 있다.
     */
    @Override
    @Transactional(readOnly = true)
    public InquiryResult inquire(String posTrx, int attemptSeq) {
        return repository.findByPosTrxAndAttemptSeq(posTrx, attemptSeq)
                .map(InquiryServiceImpl::toResult)
                .orElseGet(() -> unknownResult(posTrx, attemptSeq));
    }

    /**
     * van_approval 엔티티를 TCP/HTTP 같은 외부 표현과 분리된 서비스 결과 객체로 옮긴다.
     * <p>
     * 이 메서드는 값을 해석하거나 보정하지 않는다.
     * VAN 원장에 APPROVED와 approvalNo가 저장되어 있으면 그대로 반환하고,
     * DECLINED와 declineCode 또는 UNKNOWN도 DB에 기록된 상태 그대로 상위 계층에 넘긴다.
     */
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

    /**
     * 조회 대상 원장이 없을 때 사용하는 방어적 결과를 만든다.
     * <p>
     * VAN이 원장을 찾지 못했다는 사실만으로 승인/거절을 단정할 수 없으므로 UNKNOWN으로 응답한다.
     * approvalNo, declineCode, vanTrxId를 모두 null로 두어 "확정 정보 없음"을 명확히 표현한다.
     */
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
