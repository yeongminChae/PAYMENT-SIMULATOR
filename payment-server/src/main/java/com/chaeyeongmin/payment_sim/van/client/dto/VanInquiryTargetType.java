package com.chaeyeongmin.payment_sim.van.client.dto;

/**
 * Payment 내부 VAN Inquiry 업무 DTO와 TCP Inquiry 전문이 공유하는 조회 대상 타입.
 *
 * <p>
 * APPROVAL, CANCEL, REVERSAL 조회가 같은 INQUIRY protocol을 사용한다.
 */
public enum VanInquiryTargetType {
    /**
     * 승인 조회. targetTrxNo는 approval posTrx, targetAttemptSeq는 필수다.
     */
    APPROVAL,

    /**
     * 취소 조회. targetTrxNo는 cancelPosTrx, targetAttemptSeq는 null이어야 한다.
     */
    CANCEL,

    /**
     * 망취소 조회. targetTrxNo는 reversalPosTrx, targetAttemptSeq는 null이어야 한다.
     */
    REVERSAL
}
