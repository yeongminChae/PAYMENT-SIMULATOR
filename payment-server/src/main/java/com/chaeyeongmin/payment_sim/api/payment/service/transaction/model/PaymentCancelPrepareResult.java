package com.chaeyeongmin.payment_sim.api.payment.service.transaction.model;

import com.chaeyeongmin.payment_sim.api.payment.dto.response.CancelResponse;
import com.chaeyeongmin.payment_sim.common.api.ResultCode;
import com.chaeyeongmin.payment_sim.common.exception.BusinessException;
import com.chaeyeongmin.payment_sim.domain.model.PaymentAttempt;

/**
 * 취소 TX1(prepare) 결과를 TX 밖 오케스트레이션 계층에 전달하는 값 객체다.
 *
 * <p>
 * 취소는 원승인 확인과 PENDING row 선점이 끝난 요청만 외부 VAN cancel을 호출해야 한다.
 * 이 record는 그 경계를 명확히 표현한다.
 *
 * <p>
 * 상태는 두 가지다.
 * - created: 신규 PENDING row를 만든 요청. VAN cancel 호출 후 finalizeCancel로 내려간다.
 * - completed: prepare 단계에서 이미 응답이 확정된 요청. VAN cancel을 호출하면 안 된다.
 */
public record PaymentCancelPrepareResult(
        String posTrx,
        String originalPosTrx,
        int originalAttemptSeq,
        PaymentAttempt originalAttempt,
        boolean completed,
        CancelResponse completedResponse
) {

    private static final String CANCEL_PREPARE_ORIGINAL_ATTEMPT_REQUIRED =
            "CANCEL_PREPARE_ORIGINAL_ATTEMPT_REQUIRED";
    private static final String CANCEL_PREPARE_COMPLETED_RESPONSE_REQUIRED =
            "CANCEL_PREPARE_COMPLETED_RESPONSE_REQUIRED";

    /**
     * 신규 취소 준비 완료.
     *
     * <p>
     * TX1에서 PAYMENT_CANCEL PENDING row를 생성한 요청만 이 상태가 된다.
     * 호출자는 이 값으로 트랜잭션 밖에서 VAN cancel을 호출하고,
     * TX2에서 같은 cancel posTrx와 original 식별자로 결과를 확정한다.
     */
    public static PaymentCancelPrepareResult created(
            String posTrx,
            String originalPosTrx,
            int originalAttemptSeq,
            PaymentAttempt originalAttempt
    ) {
        if (originalAttempt == null) {
            throw new BusinessException(
                    ResultCode.INTERNAL_ERROR,
                    CANCEL_PREPARE_ORIGINAL_ATTEMPT_REQUIRED
            );
        }

        return new PaymentCancelPrepareResult(
                posTrx,
                originalPosTrx,
                originalAttemptSeq,
                originalAttempt,
                false,
                null
        );
    }

    /**
     * TX1 안에서 취소 요청 처리가 완료된 응답.
     *
     * <p>
     * 기존 cancel row 재응답, 취소 불가, PENDING insert miss 복구처럼
     * VAN cancel을 호출하면 안 되는 경로에서 사용한다.
     * 호출자는 completedResponse를 그대로 반환하고 외부 호출/TX2로 내려가지 않는다.
     */
    public static PaymentCancelPrepareResult completed(CancelResponse completedResponse) {
        if (completedResponse == null) {
            throw new BusinessException(
                    ResultCode.INTERNAL_ERROR,
                    CANCEL_PREPARE_COMPLETED_RESPONSE_REQUIRED
            );
        }

        return new PaymentCancelPrepareResult(
                completedResponse.posTrx(),
                completedResponse.originalPosTrx(),
                completedResponse.originalAttemptSeq(),
                null,
                true,
                completedResponse
        );

    }

    public boolean isCompleted() {
        // true이면 completedResponse가 최종 응답이다.
        // 호출자는 VAN cancel 호출과 TX2 finalize를 모두 건너뛰어야 한다.
        return completed;
    }

}
