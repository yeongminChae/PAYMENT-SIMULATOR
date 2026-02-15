package com.chaeyeongmin.payment_sim.van.factory;

import com.chaeyeongmin.payment_sim.api.payment.dto.enums.PaymentFinalStatus;
import com.chaeyeongmin.payment_sim.van.client.dto.VanApproveResponse;
import com.chaeyeongmin.payment_sim.van.client.dto.enums.VanDeclineCode;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * [VAN 응답 생성 Factory/Assembler]
 *
 * 책임:
 * - VAN 승인 결과(approved/declined/timeout)에 대한 응답 DTO를 생성한다.
 * - 응답 생성 시 공통 필드(posTrx/attemptSeq/vanTrxId/respondedAt)를 일관되게 채운다.
 *
 * 의도:
 * - DTO(VanApproveResponse)는 순수 데이터 컨테이너로 유지하고,
 * - 응답 생성 규칙은 Factory에서만 관리한다.
 *
 * 주의:
 * - PAN/expiry 등 민감정보는 응답에 포함하지 않는다.
 */
@Component
public class VanApproveResponseFactory {

    private VanApproveResponse.VanApproveResponseBuilder baseBuilder(
            String posTrx,
            int attemptSeq,
            String vanTrxId,
            LocalDateTime respondedAt
    ) {
        return VanApproveResponse.builder()
                .posTrx(posTrx)
                .attemptSeq(attemptSeq)
                .vanTrxId(vanTrxId)
                .respondedAt(respondedAt);
    }

    public VanApproveResponse approved(
            String posTrx,
            int attemptSeq,
            String approvalNo,
            String vanTrxId,
            LocalDateTime respondedAt
    ) {
        return baseBuilder(posTrx, attemptSeq, vanTrxId, respondedAt)
                .finalStatus(PaymentFinalStatus.APPROVED)
                .approvalNo(approvalNo)
                .declineCode(null)
                .build();
    }

    public VanApproveResponse declined(
            String posTrx,
            int attemptSeq,
            VanDeclineCode declineCode,
            String vanTrxId,
            LocalDateTime respondedAt
    ) {
        return baseBuilder(posTrx, attemptSeq, vanTrxId, respondedAt)
                .finalStatus(PaymentFinalStatus.DECLINED)
                .approvalNo(null)
                // TODO declineCode 고도화 예정
                .declineCode(VanDeclineCode.DO_NOT_HONOR)
                .build();
    }

    public VanApproveResponse unknownTimeout(
            String posTrx,
            int attemptSeq,
            VanDeclineCode declineCode,
            String vanTrxId,
            LocalDateTime respondedAt
    ) {
        return baseBuilder(posTrx, attemptSeq, vanTrxId, respondedAt)
                .finalStatus(PaymentFinalStatus.UNKNOWN_TIMEOUT)
                .approvalNo(null)
                .declineCode(VanDeclineCode.valueOf(declineCode.code()))
                .build();
    }
}
