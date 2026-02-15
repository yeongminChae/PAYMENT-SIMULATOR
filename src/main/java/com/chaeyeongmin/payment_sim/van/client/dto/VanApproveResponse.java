package com.chaeyeongmin.payment_sim.van.client.dto;

import com.chaeyeongmin.payment_sim.api.payment.dto.enums.PaymentFinalStatus;
import com.chaeyeongmin.payment_sim.van.client.dto.enums.VanDeclineCode;
import com.chaeyeongmin.payment_sim.van.client.dto.enums.VanResult;
import lombok.Builder;

import java.time.LocalDateTime;

/**
 * [VAN 응답 DTO]
 * - A6(VAN 승인 호출) 결과를 서비스 계층으로 전달한다.
 * - 서비스는 이 응답을 기반으로 A7(최종 결과 저장) 및 A9(클라이언트 응답)을 결정한다.
 * <p>
 * 원칙:
 * - 요청(posTrx/attemptSeq)을 그대로 포함해 매칭 가능해야 한다.
 * - 승인/거절/타임아웃/에러를 모두 표현할 수 있어야 한다.
 */
@Builder
public record VanApproveResponse(
        String posTrx,
        int attemptSeq,
        String cardBin,
        String cardLast4,
        VanResult vanResult,      // APPROVED/DECLINED/TIMEOUT/ERROR
        PaymentFinalStatus finalStatus, // 승인 결과
        String approvalNo,        // 승인 성공 시
        VanDeclineCode declineCode,       // 거절/타임아웃/에러 코드 (예: "05", "TIMEOUT")
        String vanTrxId,          // VAN 내부 거래키(있으면 좋음)
        // String requestId,         // TODO request_id 정책 도입 시 사용
        String message,           // 디버깅용 요약 메시지
        LocalDateTime respondedAt
) {
}