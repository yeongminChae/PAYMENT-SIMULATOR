package com.chaeyeongmin.van_sim.transaction.inquiry.service;

import com.chaeyeongmin.van_sim.transaction.inquiry.service.result.InquiryResult;

/**
 * VAN 승인 원장을 조회하는 Inquiry 유스케이스의 진입 계약이다.
 * <p>
 * Payment Server가 승인 응답을 받지 못해 UNKNOWN_TIMEOUT으로 저장한 뒤,
 * 나중에 "VAN 쪽 실제 승인 결과가 무엇이었는지" 확인할 때 이 계약을 사용한다.
 * 이 유스케이스는 조회 전용이며, 승인 처리나 원장 재생성을 담당하지 않는다.
 */
public interface InquiryService {

    /**
     * posTrx와 attemptSeq로 이미 저장된 VAN 승인 원장을 1건 조회한다.
     * <p>
     * 두 값을 함께 쓰는 이유는 같은 posTrx로 여러 승인 시도가 생길 수 있기 때문이다.
     * 원장이 있으면 저장된 APPROVED/DECLINED/UNKNOWN 결과를 그대로 반환하고,
     * 원장이 없으면 상위 계층이 "아직 모름"으로 응답할 수 있도록 UNKNOWN 성격의 결과를 반환한다.
     */
    InquiryResult inquire(String posTrx, int attemptSeq);
}
