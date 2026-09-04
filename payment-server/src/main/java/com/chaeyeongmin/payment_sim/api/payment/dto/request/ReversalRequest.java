package com.chaeyeongmin.payment_sim.api.payment.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

/**
 * [API Request] 결제 reversal 요청 DTO.
 *
 * <p>
 * amount는 API에서 받지 않고 원승인 PAYMENT_ATTEMPT에서 읽는다.
 */
public record ReversalRequest(
        @NotBlank
        String reversalPosTrx,

        @NotBlank
        String originalPosTrx,

        @Positive
        int originalAttemptSeq
) {
}
