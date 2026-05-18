package com.chaeyeongmin.payment_sim.api.payment.validate;

import com.chaeyeongmin.payment_sim.api.payment.dto.request.InquiryRequest;
import com.chaeyeongmin.payment_sim.api.payment.validate.enums.InquiryValidationError;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class InquiryRequestValidator {

    public void validate(InquiryRequest request) {

        InquiryValidationError error = validateAndGetError(request);

        if (error != null) {
            // TODO: 추후 BusinessException + result_code 표준으로 교체 예정
            throw new IllegalArgumentException(error.code());
        }

    }

    private InquiryValidationError validateAndGetError(InquiryRequest request) {
        // request null 체크
        if (request == null) return InquiryValidationError.INVALID_REQUEST;

        // posTrx null/blank 체크
        if (isNotNullOrBlank(request.posTrx()) == false)
            return InquiryValidationError.INVALID_POS_TRX;

        // attemptSeq <= 0 체크
        if (request.attemptSeq() <= 0) return InquiryValidationError.INVALID_ATTEMPT_SEQ;

        return null; // OK

    }

    /**
     * 문자열이 null이 아니고, 공백만으로 이루어지지 않았는지 검사한다.
     * (검증 로직에서 반복되는 null/blank 체크를 단순화하기 위한 유틸)
     */
    private boolean isNotNullOrBlank(String str) {
        return str != null && str.isBlank() == false;
    }

}
