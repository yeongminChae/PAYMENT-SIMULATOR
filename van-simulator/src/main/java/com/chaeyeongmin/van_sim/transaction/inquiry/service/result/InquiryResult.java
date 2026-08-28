package com.chaeyeongmin.van_sim.transaction.inquiry.service.result;

import com.chaeyeongmin.van_sim.ledger.approval.status.VanApprovalStatus;

import java.time.LocalDateTime;

/**
 * VAN 승인 원장 조회 결과를 상위 계층으로 전달하는 결과 객체다.
 * <p>
 * 이 record는 서비스 계층의 결과 모델이다.
 * TCP 전문 DTO와 JPA 엔티티 사이에 이 모델을 둔 이유는
 * "DB에서 읽은 업무 결과"와 "외부로 내보낼 JSON 필드"를 분리하기 위해서다.
 * <p>
 * 필드 의미:
 * - vanTrxId: VAN 원장의 내부 추적 ID. 원장이 없으면 null이다.
 * - posTrx/attemptSeq: Payment와 VAN이 같은 승인 시도를 식별하는 복합 키다.
 * - status: VAN 원장 기준 승인 상태다. Payment의 UNKNOWN_TIMEOUT은 여기서는 UNKNOWN에 대응한다.
 * - approvalNo: APPROVED일 때만 값이 있다.
 * - declineCode: DECLINED일 때만 값이 있다.
 * - processedAt: VAN 승인 처리가 원장에 기록된 시각이다. 원장이 없으면 null이다.
 */
public record InquiryResult(
        String vanTrxId,
        String posTrx,
        int attemptSeq,
        VanApprovalStatus status,
        String approvalNo,
        String declineCode,
        LocalDateTime processedAt
) {
}
