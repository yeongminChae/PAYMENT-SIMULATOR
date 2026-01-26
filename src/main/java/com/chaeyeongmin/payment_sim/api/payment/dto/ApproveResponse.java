package com.chaeyeongmin.payment_sim.api.payment.dto;

import com.chaeyeongmin.payment_sim.api.payment.dto.card.CardSummary;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * [DTO] 승인 응답(Approve) DTO
 * <p>
 * 목적:
 * - 승인 처리 결과를 클라이언트(POS)에 반환한다.
 * <p>
 * 필드(요약):
 * - posTrx     : 거래번호(EOT로 발급된 포스TR)
 * - attemptSeq : 서버가 확정한 승인 시도 번호(항상 존재)
 * - approvalNo : 승인 성공 시 발급되는 승인번호(성공일 때만)
 * - declineCode: 거절 시 사유 코드(DECLINED일 때만)
 * - cardSummary: 저장/로그 가능한 최소 카드정보(BIN/last4/brand)
 * <p>
 * 보안:
 * - PAN/expiry 등 민감정보는 포함하지 않는다.
 */

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class ApproveResponse {
    private String posTrx;
    private int attemptSeq;
    private String approvalNo;
    private String declineCode;
    private CardSummary cardSummary;

}