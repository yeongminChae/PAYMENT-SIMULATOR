package com.chaeyeongmin.payment_sim.api.payment.validate;

import com.chaeyeongmin.payment_sim.api.payment.dto.request.CancelRequest;
import com.chaeyeongmin.payment_sim.api.payment.validate.enums.CancelValidationError;
import com.chaeyeongmin.payment_sim.common.api.ResultCode;
import com.chaeyeongmin.payment_sim.common.exception.BusinessException;
import com.chaeyeongmin.payment_sim.common.policy.CardValidationPolicy;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class CancelRequestValidator {

    private final CardValidationPolicy cardValidationPolicy;

    public void validate(CancelRequest request) {

        CancelValidationError error = validateAndGetError(request);

        if (error != null) {
            // 수동 검증 실패는 INVALID 업무 예외로 통일한다.
            throw new BusinessException(ResultCode.INVALID, error.code());
        }

    }

    private CancelValidationError validateAndGetError(CancelRequest request) {
        // request null 체크
        if (request == null) return CancelValidationError.INVALID_REQUEST;

        // posTrx null/blank 체크
        if (isNotNullOrBlank(request.posTrx()) == false)
            return CancelValidationError.INVALID_POS_TRX;

        if (isNotNullOrBlank(request.originalPosTrx()) == false)
            return CancelValidationError.INVALID_ORIGINAL_POS_TRX;

        // attemptSeq <= 0 체크
        if (request.originalAttemptSeq() <= 0)
            return CancelValidationError.INVALID_ORIGINAL_ATTEMPT_SEQ;

        CancelValidationError invalidCard = getCardValidationError(request);
        if (invalidCard != null) return invalidCard;

        return null; // OK

    }

    /**
     * 카드번호 유효성 체크.
     * - null/blank, 길이, 숫자, Luhn 실패는 INVALID_CARD로 통일한다.
     */
    private CancelValidationError getCardValidationError(CancelRequest request) {
        String cardNo = request.cardNo();
        if (cardValidationPolicy.isValidCardNo(cardNo) == false)
            return CancelValidationError.INVALID_CARD;

        return null;
    }

    /**
     * 문자열이 null이 아니고, 공백만으로 이루어지지 않았는지 검사한다.
     * (검증 로직에서 반복되는 null/blank 체크를 단순화하기 위한 유틸)
     */
    private boolean isNotNullOrBlank(String str) {
        return str != null && str.isBlank() == false;
    }

}
