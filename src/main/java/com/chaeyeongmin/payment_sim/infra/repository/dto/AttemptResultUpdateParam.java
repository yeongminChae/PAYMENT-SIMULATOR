package com.chaeyeongmin.payment_sim.infra.repository.dto;

import com.chaeyeongmin.payment_sim.api.payment.dto.enums.PaymentFinalStatus;
import com.chaeyeongmin.payment_sim.van.client.dto.enums.VanDeclineCode;

/**
 * [Repository Param] 승인 결과 저장(A7) 업데이트 파라미터
 * <p>
 * 목적
 * - VAN 응답 결과(승인/거절/타임아웃)를 PAYMENT_ATTEMPT 테이블에 "최종 확정값"으로 반영하기 위한 내부 파라미터.
 * <p>
 * 포함 필드
 * - posTrx/attemptSeq : 업데이트 대상 row를 특정하는 키(= 결제시도 1건)
 * - finalStatus       : 최종 상태(APPROVED/DECLINED/UNKNOWN_TIMEOUT)
 * - approvalNo        : 승인 성공 시에만 채움(그 외 null)
 * - declineCode       : 거절/타임아웃/에러 코드(승인 성공 시 null)
 * - vanTrxId           : VAN 추적 키(있으면 저장)
 * <p>
 * 원칙
 * - PAN/expiry 같은 민감정보는 절대 포함하지 않는다.
 * - finalStatus는 "처리중(NULL)" 상태를 "확정 상태"로 바꾸는 용도로만 사용한다.
 */
public record AttemptResultUpdateParam(
        String posTrx,
        int attemptSeq,
        PaymentFinalStatus finalStatus,
        String approvalNo,
        String declineCode,
        String vanTrxId
) {

    public static AttemptResultUpdateParam approved(
            String posTrx,
            int attemptSeq,
            String approvalNo,
            String vanTrxId
    ) {
        return new AttemptResultUpdateParam(
                posTrx,
                attemptSeq,
                PaymentFinalStatus.APPROVED,
                approvalNo,
                null,
                vanTrxId
        );
    }

    public static AttemptResultUpdateParam declined(
            String posTrx,
            int attemptSeq,
            String declineCode,
            String vanTrxId
    ) {
        return new AttemptResultUpdateParam(
                posTrx,
                attemptSeq,
                PaymentFinalStatus.DECLINED,
                null,
                declineCode,
                vanTrxId
        );
    }

    public static AttemptResultUpdateParam unknownTimeout(
            String posTrx,
            int attemptSeq,
            String declineCode,
            String vanTrxId
    ) {
        String safeDeclineCode = declineCode != null
                ? declineCode
                : VanDeclineCode.TIMEOUT.code();

        return new AttemptResultUpdateParam(
                posTrx,
                attemptSeq,
                PaymentFinalStatus.UNKNOWN_TIMEOUT,
                null,
                safeDeclineCode,
                vanTrxId
        );
    }

}
