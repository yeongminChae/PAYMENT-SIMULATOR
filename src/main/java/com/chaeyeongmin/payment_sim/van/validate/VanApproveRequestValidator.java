package com.chaeyeongmin.payment_sim.van.validate;

import com.chaeyeongmin.payment_sim.api.payment.dto.card.CardInput;
import com.chaeyeongmin.payment_sim.common.policy.CardValidationPolicy;
import com.chaeyeongmin.payment_sim.van.client.dto.VanApproveRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class VanApproveRequestValidator {

    private final CardValidationPolicy cardValidationPolicy;

    public void validate(VanApproveRequest request) {

        VanValidationError error = validateAndGetError(request);

        if (error != null) {
            // 지금은 IllegalArgumentException으로 빠르게
            // 추후 VanBusinessException 같은 걸로 교체 가능
            throw new IllegalArgumentException(error.code());
        }
    }

    private VanValidationError validateAndGetError(VanApproveRequest request) {

        if (request == null) return VanValidationError.INVALID_REQUEST;

        if (isNotNullOrBlank(request.posTrx()) == false)
            return VanValidationError.INVALID_POS_TRX;

        if (request.attemptSeq() <= 0)
            return VanValidationError.INVALID_ATTEMPT_SEQ;

        if (request.amount() <= 0)
            return VanValidationError.INVALID_AMOUNT;

        // 카드 입력은 별도 함수에서 상세 검증(형식/만료/Luhn)
        CardInput card = new CardInput(request.pan(), request.expiryYyMm());
        if (cardValidationPolicy.isValidCard(card) == false)
            return VanValidationError.INVALID_CARD;

        return null;
    }

    private boolean isNotNullOrBlank(String str) {
        return str != null && str.isBlank() == false;
    }

    private boolean isNumeric(String str) {
        if (str == null || str.isEmpty()) return false;

        for (char c : str.toCharArray()) {
            if (Character.isDigit(c) == false) return false;
        }
        return true;
    }

}
