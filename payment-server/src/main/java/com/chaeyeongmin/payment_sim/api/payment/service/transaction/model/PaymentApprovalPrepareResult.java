package com.chaeyeongmin.payment_sim.api.payment.service.transaction.model;

import com.chaeyeongmin.payment_sim.api.payment.dto.response.ApproveResponse;
import com.chaeyeongmin.payment_sim.common.api.ResultCode;
import com.chaeyeongmin.payment_sim.common.exception.BusinessException;
import com.chaeyeongmin.payment_sim.domain.model.CardIdentity;

/**
 * TX1 승인 준비 결과.
 *
 * <p>
 * 이 record는 두 가지 상태 중 하나만 표현한다.
 * - existing=true  : 기존 DB 결과를 재응답해야 하므로 existingResponse만 사용한다.
 * - existing=false : 신규 attempt가 만들어졌으므로 posTrx/attemptSeq/cardIdentity로 VAN 호출을 진행한다.
 *
 * <p>
 * cardIdentity는 신규 VAN 호출 경로에서만 필요하다.
 * 기존 응답 재사용 경로에서는 이미 응답 DTO가 완성되어 있으므로 cardIdentity를 null로 둔다.
 */
public record PaymentApprovalPrepareResult(
        String posTrx,
        int attemptSeq,
        CardIdentity cardIdentity,
        boolean existing,
        ApproveResponse existingResponse
) {

    private static final String APPROVAL_PREPARE_CARD_IDENTITY_REQUIRED =
            "APPROVAL_PREPARE_CARD_IDENTITY_REQUIRED";
    private static final String APPROVAL_PREPARE_REUSED_RESPONSE_REQUIRED =
            "APPROVAL_PREPARE_REUSED_RESPONSE_REQUIRED";

    /**
     * 신규 승인 attempt 생성 완료.
     *
     * <p>
     * 이후 PaymentApprovalServiceImpl은 이 값으로 A5/A6 VAN 요청을 만들고,
     * TX2 finalizeApproval에서 같은 posTrx/attemptSeq를 확정한다.
     */
    public static PaymentApprovalPrepareResult created(
            String posTrx,
            int attemptSeq,
            CardIdentity cardIdentity
    ) {
        if (cardIdentity == null) {
            throw new BusinessException(
                    ResultCode.INTERNAL_ERROR,
                    APPROVAL_PREPARE_CARD_IDENTITY_REQUIRED
            );
        }

        return new PaymentApprovalPrepareResult(
                posTrx,
                attemptSeq,
                cardIdentity,
                false,
                null
        );
    }

    /**
     * 기존 승인 결과 재사용.
     *
     * <p>
     * 이 상태에서는 VAN 호출을 하면 멱등성이 깨질 수 있으므로,
     * 호출자는 existingResponse를 그대로 반환하고 A5/A6/TX2로 내려가지 않는다.
     */
    public static PaymentApprovalPrepareResult fromExistingResponse(ApproveResponse reusedResponse) {
        if (reusedResponse == null) {
            throw new BusinessException(
                    ResultCode.INTERNAL_ERROR,
                    APPROVAL_PREPARE_REUSED_RESPONSE_REQUIRED
            );
        }

        return new PaymentApprovalPrepareResult(
                reusedResponse.posTrx(),
                reusedResponse.attemptSeq(),
                null,
                true,
                reusedResponse
        );

    }

    public boolean isExisting() {
        return existing;
    }

}
