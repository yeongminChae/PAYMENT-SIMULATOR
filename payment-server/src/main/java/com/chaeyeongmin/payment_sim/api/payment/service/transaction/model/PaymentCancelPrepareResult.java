package com.chaeyeongmin.payment_sim.api.payment.service.transaction.model;

import com.chaeyeongmin.payment_sim.api.payment.dto.response.CancelResponse;
import com.chaeyeongmin.payment_sim.common.api.ResultCode;
import com.chaeyeongmin.payment_sim.common.exception.BusinessException;
import com.chaeyeongmin.payment_sim.domain.model.PaymentAttempt;


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
        return completed;
    }

}
