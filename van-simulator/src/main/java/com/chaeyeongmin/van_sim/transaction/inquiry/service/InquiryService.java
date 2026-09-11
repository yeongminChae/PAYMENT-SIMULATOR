package com.chaeyeongmin.van_sim.transaction.inquiry.service;

import com.chaeyeongmin.van_sim.transaction.inquiry.service.result.CancelInquiryResult;
import com.chaeyeongmin.van_sim.transaction.inquiry.service.result.ApprovalInquiryResult;
import com.chaeyeongmin.van_sim.transaction.inquiry.service.result.ReversalInquiryResult;

import java.util.Optional;

/**
 * VAN의 승인·취소·망취소 원장을 조회하는 Inquiry 유스케이스의 진입 계약이다.
 * <p>
 * Payment Server가 거래 결과를 확정하지 못했을 때 VAN 원장에 저장된 사실을 확인하기 위해 사용한다.
 * 이 유스케이스는 조회 전용이며 승인·취소·망취소를 실행하거나 원장을 새로 만들지 않는다.
 */
public interface InquiryService {

    /**
     * posTrx와 attemptSeq로 이미 저장된 VAN 승인 원장을 1건 조회한다.
     * <p>
     * 두 값을 함께 쓰는 이유는 같은 posTrx로 여러 승인 시도가 생길 수 있기 때문이다.
     * 원장이 있으면 저장된 APPROVED/DECLINED/UNKNOWN 결과를 그대로 반환하고,
     * 원장이 없으면 Optional.empty()를 반환해 상위 계층이 NOT_FOUND로 응답하게 한다.
     */
    Optional<ApprovalInquiryResult> inquireApproval(String posTrx, int attemptSeq);

    /** cancelPosTrx로 저장된 VAN 취소 원장을 조회하며, 없으면 Optional.empty()를 반환한다. */
    Optional<CancelInquiryResult> inquireCancel(String cancelPosTrx);

    /** reversalPosTrx로 저장된 VAN 망취소 원장을 조회하며, 없으면 Optional.empty()를 반환한다. */
    Optional<ReversalInquiryResult> inquireReversal(String reversalPosTrx);

}
