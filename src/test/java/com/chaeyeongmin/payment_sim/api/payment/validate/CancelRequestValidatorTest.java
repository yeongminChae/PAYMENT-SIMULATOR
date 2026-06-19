package com.chaeyeongmin.payment_sim.api.payment.validate;

import com.chaeyeongmin.payment_sim.api.payment.dto.request.CancelRequest;
import com.chaeyeongmin.payment_sim.common.policy.CardValidationPolicy;
import org.junit.jupiter.api.BeforeEach;

class CancelRequestValidatorTest {

    private static final String VALID_POS_TRX = "2376-20260519-9991-2001";
    private static final String VALID_ORIGINAL_POS_TRX = "2376-20260519-9991-1001";
    private static final int VALID_ORIGINAL_ATTEMPT_SEQ = 1;
    private static final String VALID_CARD_NO = "4242424242424242";

    private CancelRequestValidator validator;

    @BeforeEach
    void setUp() {
        CardValidationPolicy cardValidationPolicy = new CardValidationPolicy();
        validator = new CancelRequestValidator(cardValidationPolicy);
    }

    private CancelRequest requestWithCardNo(String cardNo) {
        return new CancelRequest(
                VALID_POS_TRX,
                VALID_ORIGINAL_POS_TRX,
                VALID_ORIGINAL_ATTEMPT_SEQ,
                cardNo
        );
    }
}
